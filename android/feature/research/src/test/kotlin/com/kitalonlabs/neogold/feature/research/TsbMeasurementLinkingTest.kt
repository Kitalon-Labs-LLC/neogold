package com.kitalonlabs.neogold.feature.research

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.pin.ResearchPinManager
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.FakePinHmacKeyProvider
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.TrustList
import com.kitalonlabs.neogold.feature.research.data.ResearchCaptureInput
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * TC-RSH-11 and TC-RSH-12: Paired TSB deferred entry and session linking (FR-28, NG-031).
 * Verifies that deferred TSB entry by code requires method and links correctly,
 * and linking unlinked TSB to a session increments outbox revision with a fresh idempotency key.
 */
@RunWith(RobolectricTestRunner::class)
class TsbMeasurementLinkingTest {

    private lateinit var database: NeoGoldDatabase
    private lateinit var repository: ResearchRepository
    private val studyId = "STUDY-TSB-01"

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val fakeMasterKey = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(context, fakeMasterKey, File(context.filesDir, "file_keys"))
        val encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, File(context.filesDir, "test_images"))

        val dummyTrustList = TrustList(1, 1, java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2028, 10, 1), emptyList(), emptyList(), emptyList())
        val pinHasher = Argon2PinHasher(FakePinHmacKeyProvider())
        val pinManager = ResearchPinManager(database.researchStudyDao(), database.auditEventDao(), pinHasher)

        repository = ResearchRepository(
            database = database,
            encryptedFileStore = encryptedFileStore,
            trustList = dummyTrustList,
            pinManager = pinManager,
            cardTrustVerifier = CardTrustVerifier(dummyTrustList),
            pinHasher = pinHasher
        )

        // Seed study
        database.researchStudyDao().insert(
            ResearchStudyEntity(
                id = studyId,
                studyCode = "SKP01",
                siteCode = "SKP01",
                displayName = "Sakhipur Study",
                serverUrl = null,
                deviceKeyAlias = null,
                serverDeviceId = null,
                pinHash = "dummyHash".toByteArray(),
                pinSalt = "dummySalt".toByteArray(),
                failedPinAttempts = 0,
                lockedUntil = null,
                timestampPrecision = "DAY_PLUS_OFFSETS",
                timeZone = "Asia/Dhaka",
                dataGeneration = null,
                localRetentionAfterAckDays = 14,
                status = "ACTIVE",
                enrolledAt = System.currentTimeMillis()
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * TC-RSH-11: TSB entry later by code (FR-28, NG-031).
     * Enter TSB two days later by participant code.
     * Linked to the right participant; method required; valid range enforced.
     */
    @Test
    fun tcRsh11_tsbEntryLaterByCode() = runBlocking {
        val participantCode = "SKP-0142"
        val participant = ResearchParticipantEntity(
            id = UUID.randomUUID().toString(),
            studyId = studyId,
            participantCode = participantCode,
            consentFormId = "SKP-CF-001",
            consentRecordedAt = System.currentTimeMillis() - 2 * 86_400_000L,
            createdAt = System.currentTimeMillis() - 2 * 86_400_000L
        )
        database.researchParticipantDao().insert(participant)

        val drawTime = System.currentTimeMillis() - 86_400_000L // Yesterday

        // 1. Invalid TSB value rejected
        val negTsb = repository.recordTsb(participantCode, -1.0, drawTime, "LAB")
        assertThat(negTsb.isFailure).isTrue()

        val highTsb = repository.recordTsb(participantCode, 45.0, drawTime, "LAB")
        assertThat(highTsb.isFailure).isTrue()

        // 2. Invalid method rejected
        val badMethod = repository.recordTsb(participantCode, 12.5, drawTime, "INVALID_METHOD")
        assertThat(badMethod.isFailure).isTrue()

        // 3. Unknown participant code rejected
        val badCode = repository.recordTsb("NON-EXISTENT", 12.5, drawTime, "LAB")
        assertThat(badCode.isFailure).isTrue()

        // 4. Valid deferred TSB recorded successfully
        val result = repository.recordTsb(participantCode, 14.8, drawTime, "POINT_OF_CARE")
        assertThat(result.isSuccess).isTrue()
        val tsbEntity = result.getOrNull()
        assertThat(tsbEntity).isNotNull()
        assertThat(tsbEntity!!.participantId).isEqualTo(participant.id)
        assertThat(tsbEntity.tsbMgDl).isEqualTo(14.8)
        assertThat(tsbEntity.method).isEqualTo("POINT_OF_CARE")
        assertThat(tsbEntity.sessionId).isNull()

        // Verify outbox entry queued
        val outboxItem = database.outboxDao().getItemByEntity("TSB", tsbEntity.id)
        assertThat(outboxItem).isNotNull()
        assertThat(outboxItem!!.state).isEqualTo("PENDING")
        assertThat(outboxItem.revision).isEqualTo(0)
    }

    /**
     * TC-RSH-12: TSB linked to a session later (FR-28).
     * Enter TSB before the session exists; link afterwards.
     * Outbox row re-queued as a new revision; idempotency key renewed.
     */
    @Test
    fun tcRsh12_tsbLinkedToSessionLater() = runBlocking {
        val participantCode = "SKP-0142"
        val participant = ResearchParticipantEntity(
            id = UUID.randomUUID().toString(),
            studyId = studyId,
            participantCode = participantCode,
            consentFormId = "SKP-CF-001",
            consentRecordedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        database.researchParticipantDao().insert(participant)

        // 1. Enter TSB prior to session
        val tsbResult = repository.recordTsb(participantCode, 13.2, System.currentTimeMillis(), "LAB")
        assertThat(tsbResult.isSuccess).isTrue()
        val initialTsb = tsbResult.getOrNull()!!

        val initialOutbox = database.outboxDao().getItemByEntity("TSB", initialTsb.id)
        assertThat(initialOutbox).isNotNull()
        val initialRevision = initialOutbox!!.revision
        val initialIdempotencyKey = initialOutbox.idempotencyKey

        // 2. Later, session is completed
        val sessionResult = repository.saveBlindedResearchSession(
            participant = participant,
            ageHours = 48.0,
            gestationalAgeWeeks = 39,
            flagEarlyJaundice = false,
            flagDangerSigns = false,
            flagPriorPhototherapy = false,
            captures = listOf(ResearchCaptureInput("STERNUM", 1, "STERNUM_IMAGE".toByteArray())),
            cardBatchBytes = null,
            tsbInput = null // No initial TSB
        )
        assertThat(sessionResult.isSuccess).isTrue()
        val sessionId = sessionResult.getOrNull()!!.sessionId

        // 3. Link existing unlinked TSB to session
        val linkResult = repository.linkTsbToSession(initialTsb.id, sessionId)
        assertThat(linkResult.isSuccess).isTrue()

        // 4. Verify TSB entity updated with session_id
        val updatedTsb = database.tsbMeasurementDao().getTsbById(initialTsb.id)
        assertThat(updatedTsb).isNotNull()
        assertThat(updatedTsb!!.sessionId).isEqualTo(sessionId)

        // 5. Verify Outbox row re-queued as new revision with new idempotency key (TC-RSH-12)
        val updatedOutbox = database.outboxDao().getItemByEntity("TSB", initialTsb.id)
        assertThat(updatedOutbox).isNotNull()
        assertThat(updatedOutbox!!.revision).isEqualTo(initialRevision + 1)
        assertThat(updatedOutbox.idempotencyKey).isNotEqualTo(initialIdempotencyKey)
        assertThat(updatedOutbox.state).isEqualTo("PENDING")
    }
}

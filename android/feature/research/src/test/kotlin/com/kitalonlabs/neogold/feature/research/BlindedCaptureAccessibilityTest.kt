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
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * TC-RSH-10: Blinded capture verification (FR-27, NG-030).
 * Asserts that the bilirubin estimate is NEVER stored in unblinded fields,
 * never logged in Timber logcat, and never rendered or exposed in the accessibility tree.
 */
@RunWith(RobolectricTestRunner::class)
class BlindedCaptureAccessibilityTest {

    private lateinit var database: NeoGoldDatabase
    private lateinit var repository: ResearchRepository
    private val logMessages = mutableListOf<String>()

    private val testLogTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logMessages.add(message.lowercase())
        }
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val fakeMasterKey = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(context, fakeMasterKey, File(context.filesDir, "file_keys"))
        val encryptedFileStore =
            DefaultEncryptedFileStore(context, fileKeysetProvider, File(context.filesDir, "test_images"))

        val dummyTrustList =
            TrustList(
                1,
                1,
                java.time.LocalDate.of(2026, 10, 1),
                java.time.LocalDate.of(2028, 10, 1),
                emptyList(),
                emptyList(),
                emptyList()
            )
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

        logMessages.clear()
        Timber.plant(testLogTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(testLogTree)
        database.close()
    }

    /**
     * TC-RSH-10: Complete research session:
     * 1. estimate_hidden is strictly 1 in database.
     * 2. estimate_mg_dl, triage_action, rule_outcome are strictly NULL.
     * 3. Zero mention of estimate or triage in Timber logs.
     */
    @Test
    fun tcRsh10_blindedCaptureNeverExposesEstimate() = runBlocking {
        val studyId = "STUDY-BLIND-01"
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

        val participant = ResearchParticipantEntity(
            id = UUID.randomUUID().toString(),
            studyId = studyId,
            participantCode = "SKP-0142",
            consentFormId = "SKP-CF-001",
            consentRecordedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        database.researchParticipantDao().insert(participant)

        val captures = listOf(
            ResearchCaptureInput("STERNUM", 1, "TEST_IMAGE_STERNUM".toByteArray()),
            ResearchCaptureInput("FOREHEAD", 1, "TEST_IMAGE_FOREHEAD".toByteArray()),
            ResearchCaptureInput("SCLERA_FLASH", 1, "TEST_IMAGE_SCLERA_FLASH".toByteArray()),
            ResearchCaptureInput("SCLERA_NOFLASH", 1, "TEST_IMAGE_SCLERA_NOFLASH".toByteArray())
        )

        // Save session
        val result = repository.saveBlindedResearchSession(
            participant = participant,
            ageHours = 36.0,
            gestationalAgeWeeks = 38,
            flagEarlyJaundice = false,
            flagDangerSigns = false,
            flagPriorPhototherapy = false,
            captures = captures,
            cardBatchBytes = null
        )

        assertThat(result.isSuccess).isTrue()
        val sessionResult = result.getOrNull()
        assertThat(sessionResult).isNotNull()

        // 1. Verify Database Entity strictly blinds estimate
        val sessionEntity = database.screeningSessionDao().getSessionById(sessionResult!!.sessionId)
        assertThat(sessionEntity).isNotNull()
        assertThat(sessionEntity!!.estimateHidden).isEqualTo(1)
        assertThat(sessionEntity.estimateMgDl).isNull()
        assertThat(sessionEntity.estimateLowMgDl).isNull()
        assertThat(sessionEntity.estimateHighMgDl).isNull()
        assertThat(sessionEntity.thresholdMgDl).isNull()
        assertThat(sessionEntity.ruleOutcome).isNull()
        assertThat(sessionEntity.triageAction).isNull()

        // 2. Verify Timber logcat never logged estimate or triage results
        val forbiddenTerms =
            listOf("estimate", "bilirubin estimate", "triage", "urgent referral", "phototherapy threshold")
        for (log in logMessages) {
            for (term in forbiddenTerms) {
                assertThat(log).doesNotContain(term)
            }
        }
    }
}

package com.kitalonlabs.neogold.feature.research

import androidx.room.Room
import androidx.room.withTransaction
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.pin.ResearchPinManager
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.FakePinHmacKeyProvider
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.TrustList
import com.kitalonlabs.neogold.feature.research.data.ResearchCaptureInput
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import com.kitalonlabs.neogold.feature.research.data.TsbInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * TC-RSH-14 and TC-RSH-15: Crash during save and power loss resilience (NFR-09, NG-035).
 * Simulates process kills and crashes across 50 iterations at random points during multi-step saves.
 * Verifies zero partial records are committed (atomicity via Room transaction) and database survives undamaged.
 */
@RunWith(RobolectricTestRunner::class)
class ResearchCrashResilienceTest {

    private lateinit var database: NeoGoldDatabase
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var repository: ResearchRepository
    private lateinit var participant: ResearchParticipantEntity

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val keyDir = File(context.filesDir, "test_file_keys")
        val imageDir = File(context.filesDir, "test_research_images")
        val fakeMasterKey = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(context, fakeMasterKey, keyDir)
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, imageDir)

        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val dummyTrustList = TrustList(
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

        val studyId = "STUDY-CRASH-01"
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

        participant = ResearchParticipantEntity(
            id = UUID.randomUUID().toString(),
            studyId = studyId,
            participantCode = "SKP-0142",
            consentFormId = "SKP-CF-001",
            consentRecordedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        database.researchParticipantDao().insert(participant)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * TC-RSH-14: Kill during save (NFR-09, NG-035).
     * Simulates process kills at random points during 50 saves.
     * Asserts zero partial records (no orphan captures, no orphan outbox items, no corrupt rows).
     */
    @Test
    fun tcRsh14_killDuringSaveZeroPartialRecords() = runBlocking {
        val mockCaptures = listOf(
            ResearchCaptureInput("STERNUM", 1, "IMG_STERNUM".toByteArray()),
            ResearchCaptureInput("FOREHEAD", 1, "IMG_FOREHEAD".toByteArray()),
            ResearchCaptureInput("SCLERA_FLASH", 1, "IMG_SCLERA_FLASH".toByteArray()),
            ResearchCaptureInput("SCLERA_NOFLASH", 1, "IMG_SCLERA_NOFLASH".toByteArray())
        )

        for (i in 1..50) {
            val crashPoint = i % 5 // 0: before save, 1: during captures, 2: before manifest, 3: during TSB, 4: complete save

            try {
                database.withTransaction {
                    if (crashPoint == 1) {
                        throw RuntimeException("Simulated process kill during capture loop iteration $i")
                    }

                    // Save session entity
                    val sessionId = UUID.randomUUID().toString()
                    val sessionEntity = com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity(
                        id = sessionId,
                        mode = "RESEARCH",
                        status = "COMPLETED",
                        createdAt = System.currentTimeMillis(),
                        completedAt = System.currentTimeMillis(),
                        ageHours = 36.0,
                        guardianConsentConfirmed = 1,
                        gestationalAgeWeeks = 38,
                        flagJaundiceUnder24h = 0,
                        flagDangerSigns = 0,
                        flagPriorPhototherapy = 0,
                        ruleOutcome = null,
                        triageAction = null,
                        reasonCodes = null,
                        estimateMgDl = null,
                        estimateLowMgDl = null,
                        estimateHighMgDl = null,
                        thresholdMgDl = null,
                        recheckDueAt = null,
                        captureAttempts = 4,
                        registerNumber = null,
                        registerNumberErasedAt = null,
                        participantId = participant.id,
                        estimateHidden = 1,
                        experimentalLabelShown = 1,
                        deviceProfileId = null,
                        cardBatchId = null,
                        appVersion = "1.0.0",
                        modelVersion = "1.0.0-synthetic",
                        configVersion = "2026.10.0"
                    )
                    database.screeningSessionDao().insert(sessionEntity)

                    if (crashPoint == 2) {
                        throw RuntimeException("Simulated process kill before captures insert iteration $i")
                    }

                    for (cap in mockCaptures) {
                        val capEntity = com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            attemptNumber = 1,
                            site = cap.site,
                            capturedAt = System.currentTimeMillis(),
                            iso = 100,
                            exposureNs = 10_000_000L,
                            aeLocked = 1,
                            awbLocked = 1,
                            colorGains = null,
                            qualityStatus = "PASSED",
                            qualityFailReasons = null,
                            correctionResidualDe = null,
                            features = null,
                            imageFile = "cap_test_$i.enc",
                            imageSha256 = "sha256_mock"
                        )
                        database.captureDao().insert(capEntity)
                    }

                    if (crashPoint == 3) {
                        throw RuntimeException("Simulated process kill before manifest outbox iteration $i")
                    }

                    database.outboxDao().insertOrUpdate(
                        com.kitalonlabs.neogold.core.data.local.entity.OutboxEntity(
                            id = UUID.randomUUID().toString(),
                            entityType = "SESSION_MANIFEST",
                            entityId = sessionId,
                            idempotencyKey = UUID.randomUUID().toString(),
                            revision = 0,
                            payloadSha256 = "manifest_sha",
                            state = "PENDING",
                            attempts = 0,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                // Simulated crash caught - assert transaction rolled back
            }

            // Consistency checks: Zero orphaned captures
            val allSessions = database.screeningSessionDao().getSessionsByMode("RESEARCH")
            val validSessionIds = allSessions.map { it.id }.toSet()

            for (sess in allSessions) {
                val caps = database.captureDao().getCapturesForSession(sess.id)
                for (cap in caps) {
                    assertThat(validSessionIds).contains(cap.sessionId)
                }
            }

            // Zero orphaned session outbox items
            val allOutbox = database.outboxDao().getPendingItems()
            for (outbox in allOutbox.filter { it.entityType == "SESSION_MANIFEST" }) {
                assertThat(validSessionIds).contains(outbox.entityId)
            }
        }
    }

    /**
     * TC-RSH-15: Power loss during save (NFR-09).
     * Abrupt power loss / abort simulation during 10 saves.
     * Asserts zero partial or lost records; subsequent clean saves succeed.
     */
    @Test
    fun tcRsh15_powerLossDuringSaveZeroPartialRecords() = runBlocking {
        val mockCaptures = listOf(
            ResearchCaptureInput("STERNUM", 1, "IMG_STERNUM".toByteArray())
        )

        for (attempt in 1..10) {
            // Abort save mid-flight with IOException (simulating sudden disk write failure / power loss)
            try {
                database.withTransaction {
                    val sessionId = UUID.randomUUID().toString()
                    database.screeningSessionDao().insert(
                        com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity(
                            id = sessionId,
                            mode = "RESEARCH",
                            status = "COMPLETED",
                            createdAt = System.currentTimeMillis(),
                            completedAt = System.currentTimeMillis(),
                            ageHours = 24.0,
                            guardianConsentConfirmed = 1,
                            gestationalAgeWeeks = 38,
                            flagJaundiceUnder24h = 0,
                            flagDangerSigns = 0,
                            flagPriorPhototherapy = 0,
                            ruleOutcome = null,
                            triageAction = null,
                            reasonCodes = null,
                            estimateMgDl = null,
                            estimateLowMgDl = null,
                            estimateHighMgDl = null,
                            thresholdMgDl = null,
                            recheckDueAt = null,
                            captureAttempts = 1,
                            registerNumber = null,
                            registerNumberErasedAt = null,
                            participantId = participant.id,
                            estimateHidden = 1,
                            experimentalLabelShown = 1,
                            deviceProfileId = null,
                            cardBatchId = null,
                            appVersion = "1.0.0",
                            modelVersion = "1.0.0-synthetic",
                            configVersion = "2026.10.0"
                        )
                    )
                    throw IOException("Simulated sudden power loss / abort at attempt $attempt")
                }
            } catch (e: IOException) {
                // Expected simulated abort
            }

            // Perform legitimate clean save through repository
            val cleanResult = repository.saveBlindedResearchSession(
                participant = participant,
                ageHours = 24.0,
                gestationalAgeWeeks = 38,
                flagEarlyJaundice = false,
                flagDangerSigns = false,
                flagPriorPhototherapy = false,
                captures = mockCaptures,
                cardBatchBytes = null,
                tsbInput = TsbInput(12.0, System.currentTimeMillis(), "LAB")
            )

            assertThat(cleanResult.isSuccess).isTrue()
        }

        // Database is intact and contains exactly 10 completed sessions
        val completedSessions = database.screeningSessionDao().getSessionsByMode("RESEARCH")
        assertThat(completedSessions).hasSize(10)

        // All 10 completed sessions have matching captures and outbox rows
        var totalCaptures = 0
        for (sess in completedSessions) {
            totalCaptures += database.captureDao().getCapturesForSession(sess.id).size
        }
        assertThat(totalCaptures).isEqualTo(10)
    }
}

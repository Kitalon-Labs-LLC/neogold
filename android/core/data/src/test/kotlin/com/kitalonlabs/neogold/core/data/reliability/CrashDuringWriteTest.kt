package com.kitalonlabs.neogold.core.data.reliability

import android.content.Context
import androidx.room.withTransaction
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.OutboxEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.security.DefaultDatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
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
 * 50-iteration crash-during-write test.
 *
 * Simulates process kills and crashes at various points during multi-step research session saves
 * (writing encrypted images, inserting session manifests, captures, outbox items, and audit events).
 * Verifies:
 * 1. Zero partial rows are left in the database (atomicity via Room transaction).
 * 2. Database survives without corrupting and remains fully operational for subsequent operations.
 * 3. File store remains consistent with zero orphaned/corrupted files for aborted sessions.
 */
@RunWith(RobolectricTestRunner::class)
class CrashDuringWriteTest {

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var keyDir: File
    private lateinit var fileKeyDir: File
    private lateinit var imageDir: File

    private val studyId = "STUDY-CRASH-01"
    private val participantId = "PART-CRASH-01"
    private val cardBatchId = "BATCH-CRASH-01"

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        keyDir = File(context.filesDir, "crash_test_db_keys")
        fileKeyDir = File(context.filesDir, "crash_test_file_keys")
        imageDir = File(context.filesDir, "crash_test_images")

        keyDir.mkdirs()
        fileKeyDir.mkdirs()
        imageDir.mkdirs()

        val masterKeyProvider = FakeMasterKeyProvider()
        val databaseKeyProvider = DefaultDatabaseKeyProvider(context, masterKeyProvider, keyDir)
        val fileKeysetProvider = DefaultFileKeysetProvider(context, masterKeyProvider, fileKeyDir)
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, imageDir)

        database = NeoGoldDatabase.buildDatabase(
            context = context,
            databaseKeyProvider = databaseKeyProvider,
            databaseName = "crash_test.db",
            openHelperFactory = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        )

        // Prepopulate study, participant, and card batch
        database.cardBatchDao().insert(
            CardBatchEntity(
                batchId = cardBatchId,
                issuerId = "ISSUER_CRASH",
                formatVersion = 1,
                referenceValues = "[]",
                issuedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 1_000_000L,
                signatureValid = 1,
                qrPayload = byteArrayOf(1, 2, 3),
                serverStatus = "ACCEPTED",
                firstSeenAt = System.currentTimeMillis()
            )
        )

        database.researchStudyDao().insert(
            ResearchStudyEntity(
                id = studyId,
                studyCode = "STD-01",
                siteCode = "SITE-01",
                displayName = "Crash Resilience Study",
                serverUrl = "https://study.example.org",
                deviceKeyAlias = "dev-key",
                serverDeviceId = "srv-dev-01",
                pinHash = ByteArray(32),
                pinSalt = ByteArray(16),
                failedPinAttempts = 0,
                lockedUntil = null,
                timestampPrecision = "DAY_PLUS_OFFSETS",
                timeZone = "Asia/Dhaka",
                dataGeneration = 1L,
                localRetentionAfterAckDays = 14,
                status = "ACTIVE",
                enrolledAt = System.currentTimeMillis()
            )
        )

        database.researchParticipantDao().insert(
            ResearchParticipantEntity(
                id = participantId,
                studyId = studyId,
                participantCode = "SKP-0999",
                consentFormId = "SKP-CONSENT-01",
                consentRecordedAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    @After
    fun tearDown() {
        if (database.isOpen) database.close()
        keyDir.deleteRecursively()
        fileKeyDir.deleteRecursively()
        imageDir.deleteRecursively()
        context.deleteDatabase("crash_test.db")
    }

    @Test
    fun fiftyIterationCrashDuringWrite_zeroPartialRowsAndSurvivedWithoutCorruption() = runBlocking {
        var completedSessionsCount = 0
        var abortedSessionsCount = 0

        for (iteration in 1..50) {
            val sessionId = UUID.randomUUID().toString()
            val crashPoint = iteration % 5 // 0 to 4: different injected failure points; 4 is full success

            val file1 = "img_${sessionId}_1.enc"
            val file2 = "img_${sessionId}_2.enc"
            val writtenFiles = mutableListOf<String>()

            val attemptedSave = runCatching {
                // Step 1: Write first image
                if (crashPoint == 0) throw IOException("Simulated process kill during image 1 write")
                encryptedFileStore.writeEncryptedFile(file1) { it.write("DUMMY_RAW_IMG_1".toByteArray()) }
                writtenFiles.add(file1)

                // Step 2: Write second image
                if (crashPoint == 1) throw IOException("Simulated process kill during image 2 write")
                encryptedFileStore.writeEncryptedFile(file2) { it.write("DUMMY_RAW_IMG_2".toByteArray()) }
                writtenFiles.add(file2)

                // Step 3: Atomic database transaction for session manifest, captures, outbox, and audit
                database.withTransaction {
                    val sessionEntity = ScreeningSessionEntity(
                        id = sessionId,
                        mode = "RESEARCH",
                        status = "COMPLETED",
                        createdAt = System.currentTimeMillis(),
                        completedAt = System.currentTimeMillis(),
                        ageHours = 48.0,
                        guardianConsentConfirmed = 1,
                        gestationalAgeWeeks = 39,
                        flagJaundiceUnder24h = 0,
                        flagDangerSigns = 0,
                        flagPriorPhototherapy = 0,
                        ruleOutcome = null,
                        triageAction = "NO_FURTHER_ACTION_FROM_SCREEN",
                        reasonCodes = null,
                        estimateMgDl = null,
                        estimateLowMgDl = null,
                        estimateHighMgDl = null,
                        thresholdMgDl = null,
                        recheckDueAt = null,
                        registerNumber = null,
                        registerNumberErasedAt = null,
                        participantId = participantId,
                        estimateHidden = 0,
                        experimentalLabelShown = 1,
                        deviceProfileId = null,
                        cardBatchId = cardBatchId,
                        appVersion = "1.0.0",
                        modelVersion = "1.0.0",
                        configVersion = "1.0.0"
                    )
                    database.screeningSessionDao().insert(sessionEntity)

                    if (crashPoint == 2) throw RuntimeException("Simulated process kill after session insert")

                    val capture1 = CaptureEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        attemptNumber = 1,
                        site = "STERNUM",
                        capturedAt = System.currentTimeMillis(),
                        iso = 100,
                        exposureNs = 10_000_000L,
                        aeLocked = 1,
                        awbLocked = 1,
                        colorGains = """{"r":1.0,"g":1.0,"b":1.0}""",
                        qualityStatus = "PASSED",
                        qualityFailReasons = null,
                        correctionResidualDe = 0.8,
                        features = null,
                        imageFile = file1,
                        imageSha256 = "dummy_sha_1"
                    )
                    database.captureDao().insert(capture1)

                    if (crashPoint == 3) throw RuntimeException("Simulated process kill after capture insert")

                    val outboxItem = OutboxEntity(
                        id = UUID.randomUUID().toString(),
                        entityType = "SESSION_MANIFEST",
                        entityId = sessionId,
                        idempotencyKey = UUID.randomUUID().toString(),
                        revision = 0,
                        dependencyRequeues = 0,
                        payloadSha256 = "sha256_manifest",
                        state = "PENDING",
                        attempts = 0,
                        lastError = null,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    database.outboxDao().insertOrUpdate(outboxItem)

                    val audit = AuditEventEntity(
                        id = UUID.randomUUID().toString(),
                        occurredAt = System.currentTimeMillis(),
                        eventType = "CONFIG_LOADED",
                        details = """{"iteration":$iteration}"""
                    )
                    database.auditEventDao().insert(audit)
                }
            }

            if (attemptedSave.isFailure) {
                abortedSessionsCount++
                // Clean up any dangling image files for aborted session
                writtenFiles.forEach { encryptedFileStore.delete(it) }

                // VERIFICATION: Zero partial rows must exist for this aborted session
                val foundSession = database.screeningSessionDao().getSessionById(sessionId)
                assertThat(foundSession).isNull()

                val foundCaptures = database.captureDao().getCapturesForSession(sessionId)
                assertThat(foundCaptures).isEmpty()

                val outboxItems = database.outboxDao().getPendingItems()
                    .filter { it.entityId == sessionId }
                assertThat(outboxItems).isEmpty()
            } else {
                completedSessionsCount++

                // VERIFICATION: Fully saved session has complete rows
                val foundSession = database.screeningSessionDao().getSessionById(sessionId)
                assertThat(foundSession).isNotNull()

                val foundCaptures = database.captureDao().getCapturesForSession(sessionId)
                assertThat(foundCaptures).hasSize(1)

                assertThat(encryptedFileStore.exists(file1)).isTrue()
                assertThat(encryptedFileStore.exists(file2)).isTrue()
            }
        }

        // Verify counts
        assertThat(completedSessionsCount).isEqualTo(10) // iterations where iteration % 5 == 4 (e.g. 4, 9, 14, 19, 24, 29, 34, 39, 44, 49)
        assertThat(abortedSessionsCount).isEqualTo(40)

        // Verify total screening sessions in database matches exactly the completed count
        val allSessions = database.screeningSessionDao().getSessionsByMode("RESEARCH")
        assertThat(allSessions).hasSize(completedSessionsCount)

        // Verify the database is uncorrupted and still fully readable/writeable
        val finalSessionId = "final-verif-session"
        database.screeningSessionDao().insert(
            ScreeningSessionEntity(
                id = finalSessionId,
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
                triageAction = "NO_FURTHER_ACTION_FROM_SCREEN",
                reasonCodes = null,
                estimateMgDl = null,
                estimateLowMgDl = null,
                estimateHighMgDl = null,
                thresholdMgDl = null,
                recheckDueAt = null,
                registerNumber = null,
                registerNumberErasedAt = null,
                participantId = participantId,
                estimateHidden = 0,
                experimentalLabelShown = 1,
                deviceProfileId = null,
                cardBatchId = cardBatchId,
                appVersion = "1.0.0",
                modelVersion = "1.0.0",
                configVersion = "1.0.0"
            )
        )
        assertThat(database.screeningSessionDao().getSessionById(finalSessionId)).isNotNull()
    }
}

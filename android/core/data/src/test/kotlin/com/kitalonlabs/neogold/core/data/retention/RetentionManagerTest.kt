package com.kitalonlabs.neogold.core.data.retention

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.datastore.DefaultNeoGoldDataStore
import com.kitalonlabs.neogold.core.data.datastore.NeoGoldDataStore
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.OutboxEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RetentionManagerTest {

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var dataStore: NeoGoldDataStore
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var storageDir: File
    private lateinit var keyDir: File
    private lateinit var dataStoreFile: File
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private var virtualTimeMs: Long = 1_700_000_000_000L // arbitrary epoch baseline

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = NeoGoldDatabase.buildInMemory(context)

        dataStoreFile = File(context.filesDir, "test_prefs.preferences_pb")
        dataStoreFile.delete()
        val rawDataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { dataStoreFile }
        )
        dataStore = DefaultNeoGoldDataStore(rawDataStore)

        storageDir = File(context.filesDir, "test_retention_storage")
        keyDir = File(context.filesDir, "test_retention_keys")
        storageDir.mkdirs()
        keyDir.mkdirs()

        val masterKeyProvider = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(context, masterKeyProvider, keyDir)
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, storageDir)
    }

    @After
    fun tearDown() {
        database.close()
        encryptedFileStore.deleteAllFiles()
        storageDir.deleteRecursively()
        keyDir.deleteRecursively()
        dataStoreFile.delete()
    }

    private fun createRetentionManager(
        confirmationHook: ResearchRetentionServerConfirmationHook = NoOpResearchRetentionServerConfirmationHook()
    ): RetentionManager {
        return RetentionManager(
            database = database,
            dataStore = dataStore,
            encryptedFileStore = encryptedFileStore,
            serverConfirmationHook = confirmationHook,
            currentTimeProvider = { virtualTimeMs }
        )
    }

    @Test
    fun tcLog04_registerNumberErasedAt60Days() = runTest(testDispatcher) {
        val manager = createRetentionManager()
        val sessionDao = database.screeningSessionDao()
        val auditDao = database.auditEventDao()

        // 1. Save two screening sessions with register numbers
        val sessionId1 = UUID.randomUUID().toString()
        val session1 = ScreeningSessionEntity(
            id = sessionId1,
            mode = "SCREENING",
            status = "COMPLETED",
            createdAt = virtualTimeMs,
            completedAt = virtualTimeMs + 5000L,
            ageHours = 48.0,
            guardianConsentConfirmed = 1,
            gestationalAgeWeeks = 38,
            flagJaundiceUnder24h = 0,
            flagDangerSigns = 0,
            flagPriorPhototherapy = 0,
            ruleOutcome = null,
            triageAction = "NO_FURTHER_ACTION_FROM_SCREEN",
            reasonCodes = null,
            estimateMgDl = 8.5,
            estimateLowMgDl = 7.0,
            estimateHighMgDl = 10.0,
            thresholdMgDl = 15.0,
            recheckDueAt = null,
            registerNumber = "OPD-2027-15",
            registerNumberErasedAt = null,
            participantId = null,
            estimateHidden = 0,
            experimentalLabelShown = 0,
            deviceProfileId = null,
            cardBatchId = null,
            appVersion = "1.0.0",
            modelVersion = "1.0.0",
            configVersion = "1.0.0"
        )
        sessionDao.insert(session1)

        val sessionId2 = UUID.randomUUID().toString()
        val session2 = session1.copy(id = sessionId2, registerNumber = "12/345")
        sessionDao.insert(session2)

        // 2. Advance clock by 30 days -> job should NOT erase register number (retention is 60 days)
        virtualTimeMs += 30L * RetentionManager.MILLIS_PER_DAY
        val erased30 = manager.eraseRegisterNumbers()
        assertThat(erased30).isEqualTo(0)
        assertThat(sessionDao.getSessionById(sessionId1)?.registerNumber).isEqualTo("OPD-2027-15")

        // 3. Advance clock past 60 days (e.g. 31 more days, total 61 days)
        virtualTimeMs += 31L * RetentionManager.MILLIS_PER_DAY
        val erased60 = manager.eraseRegisterNumbers()
        assertThat(erased60).isEqualTo(2)

        // 4. Verify register_number is null and register_number_erased_at is set to current virtual time
        val updated1 = sessionDao.getSessionById(sessionId1)!!
        assertThat(updated1.registerNumber).isNull()
        assertThat(updated1.registerNumberErasedAt).isEqualTo(virtualTimeMs)

        val updated2 = sessionDao.getSessionById(sessionId2)!!
        assertThat(updated2.registerNumber).isNull()
        assertThat(updated2.registerNumberErasedAt).isEqualTo(virtualTimeMs)

        // 5. Verify REGISTER_ERASURE audit event was recorded with count only (no IDs)
        val auditEvents = auditDao.getEventsByType("REGISTER_ERASURE")
        assertThat(auditEvents).hasSize(1)
        assertThat(auditEvents[0].occurredAt).isEqualTo(virtualTimeMs)
        assertThat(auditEvents[0].details).isEqualTo("""{"count":2}""")
    }

    @Test
    fun tcLog05_screeningPurgedAt180Days_andConfigurableRangeEnforced() = runTest(testDispatcher) {
        val manager = createRetentionManager()
        val sessionDao = database.screeningSessionDao()
        val captureDao = database.captureDao()
        val auditDao = database.auditEventDao()

        // 1. Verify range validation on retention setting (allowed 30 to 365, PRD D7)
        dataStore.setScreeningRetentionDays(30)
        dataStore.setScreeningRetentionDays(365)
        dataStore.setScreeningRetentionDays(180) // reset to default 180

        // Test boundary rejections: 29 and 366 must be refused
        assertThrows(IllegalArgumentException::class.java) {
            runTest(testDispatcher) { dataStore.setScreeningRetentionDays(29) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runTest(testDispatcher) { dataStore.setScreeningRetentionDays(366) }
        }

        // 2. Insert session with a capture
        val sessionId = UUID.randomUUID().toString()
        val session = ScreeningSessionEntity(
            id = sessionId,
            mode = "SCREENING",
            status = "COMPLETED",
            createdAt = virtualTimeMs,
            completedAt = virtualTimeMs + 5000L,
            ageHours = 50.0,
            guardianConsentConfirmed = 1,
            gestationalAgeWeeks = 39,
            flagJaundiceUnder24h = 0,
            flagDangerSigns = 0,
            flagPriorPhototherapy = 0,
            ruleOutcome = null,
            triageAction = "NO_FURTHER_ACTION_FROM_SCREEN",
            reasonCodes = null,
            estimateMgDl = 9.0,
            estimateLowMgDl = 7.5,
            estimateHighMgDl = 10.5,
            thresholdMgDl = 15.0,
            recheckDueAt = null,
            registerNumber = null,
            registerNumberErasedAt = null,
            participantId = null,
            estimateHidden = 0,
            experimentalLabelShown = 0,
            deviceProfileId = null,
            cardBatchId = null,
            appVersion = "1.0.0",
            modelVersion = "1.0.0",
            configVersion = "1.0.0"
        )
        sessionDao.insert(session)

        val capture = CaptureEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            attemptNumber = 1,
            site = "STERNUM",
            capturedAt = virtualTimeMs,
            iso = 100,
            exposureNs = 10_000_000L,
            aeLocked = 1,
            awbLocked = 1,
            colorGains = null,
            qualityStatus = "PASSED",
            qualityFailReasons = null,
            correctionResidualDe = 0.5,
            features = null,
            imageFile = null,
            imageSha256 = null
        )
        captureDao.insert(capture)

        // 3. Advance clock 179 days -> not purged yet
        virtualTimeMs += 179L * RetentionManager.MILLIS_PER_DAY
        val purged179 = manager.purgeScreeningSessions()
        assertThat(purged179).isEqualTo(0)
        assertThat(sessionDao.getSessionById(sessionId)).isNotNull()

        // 4. Advance clock past 180 days (e.g. 2 more days -> 181 days total)
        virtualTimeMs += 2L * RetentionManager.MILLIS_PER_DAY
        val purged181 = manager.purgeScreeningSessions()
        assertThat(purged181).isEqualTo(1)

        // 5. Verify session and cascading capture are deleted
        assertThat(sessionDao.getSessionById(sessionId)).isNull()
        assertThat(captureDao.getCapturesForSession(sessionId)).isEmpty()

        // 6. Verify RETENTION_PURGE audit event recorded with count only
        val purgeEvents = auditDao.getEventsByType("RETENTION_PURGE")
        assertThat(purgeEvents).hasSize(1)
        assertThat(purgeEvents[0].details).isEqualTo("""{"count":1}""")
    }

    @Test
    fun abandonedSessionsOlderThan24HoursAreCleanedUp() = runTest(testDispatcher) {
        val manager = createRetentionManager()
        val sessionDao = database.screeningSessionDao()

        // Session 1: IN_PROGRESS created 25 hours ago
        val session1 = ScreeningSessionEntity(
            id = UUID.randomUUID().toString(),
            mode = "SCREENING",
            status = "IN_PROGRESS",
            createdAt = virtualTimeMs - (25L * 60L * 60L * 1000L),
            completedAt = null,
            ageHours = 30.0,
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
            registerNumber = null,
            registerNumberErasedAt = null,
            participantId = null,
            estimateHidden = 0,
            experimentalLabelShown = 0,
            deviceProfileId = null,
            cardBatchId = null,
            appVersion = "1.0.0",
            modelVersion = null,
            configVersion = "1.0.0"
        )
        sessionDao.insert(session1)

        // Session 2: IN_PROGRESS created 2 hours ago
        val session2 = session1.copy(
            id = UUID.randomUUID().toString(),
            createdAt = virtualTimeMs - (2L * 60L * 60L * 1000L)
        )
        sessionDao.insert(session2)

        // Session 3: COMPLETED created 25 hours ago
        val session3 = session1.copy(
            id = UUID.randomUUID().toString(),
            status = "COMPLETED"
        )
        sessionDao.insert(session3)

        val deleted = manager.cleanupAbandonedSessions()
        assertThat(deleted).isEqualTo(1)

        // Session 1 deleted; session 2 and 3 preserved
        assertThat(sessionDao.getSessionById(session1.id)).isNull()
        assertThat(sessionDao.getSessionById(session2.id)).isNotNull()
        assertThat(sessionDao.getSessionById(session3.id)).isNotNull()
    }

    @Test
    fun researchRetentionPurgesAfterAckAndServerConfirmation() = runTest(testDispatcher) {
        val sessionDao = database.screeningSessionDao()
        val captureDao = database.captureDao()
        val outboxDao = database.outboxDao()
        val studyDao = database.researchStudyDao()
        val participantDao = database.researchParticipantDao()

        // Enroll study and participant
        val study = ResearchStudyEntity(
            id = "study_01",
            studyCode = "STD-01",
            siteCode = "SITE-A",
            displayName = "Study A",
            serverUrl = "https://study.example.org",
            deviceKeyAlias = "alias",
            serverDeviceId = "dev_01",
            pinHash = ByteArray(32),
            pinSalt = ByteArray(16),
            failedPinAttempts = 0,
            lockedUntil = null,
            timestampPrecision = "DAY_PLUS_OFFSETS",
            timeZone = "Asia/Dhaka",
            dataGeneration = 1L,
            localRetentionAfterAckDays = 14,
            status = "ACTIVE",
            enrolledAt = virtualTimeMs
        )
        studyDao.insert(study)

        val participant = ResearchParticipantEntity(
            id = "part_01",
            studyId = "study_01",
            participantCode = "SKP-0142",
            consentFormId = "CF-01",
            consentRecordedAt = virtualTimeMs,
            createdAt = virtualTimeMs
        )
        participantDao.insert(participant)

        // Research session
        val sessionId = UUID.randomUUID().toString()
        val session = ScreeningSessionEntity(
            id = sessionId,
            mode = "RESEARCH",
            status = "COMPLETED",
            createdAt = virtualTimeMs,
            completedAt = virtualTimeMs + 5000L,
            ageHours = 60.0,
            guardianConsentConfirmed = 0,
            gestationalAgeWeeks = 40,
            flagJaundiceUnder24h = 0,
            flagDangerSigns = 0,
            flagPriorPhototherapy = 0,
            ruleOutcome = null,
            triageAction = null,
            reasonCodes = null,
            estimateMgDl = 11.0,
            estimateLowMgDl = 9.0,
            estimateHighMgDl = 13.0,
            thresholdMgDl = 15.0,
            recheckDueAt = null,
            registerNumber = null,
            registerNumberErasedAt = null,
            participantId = "part_01",
            estimateHidden = 1,
            experimentalLabelShown = 0,
            deviceProfileId = null,
            cardBatchId = null,
            appVersion = "1.0.0",
            modelVersion = "1.0.0",
            configVersion = "1.0.0"
        )
        sessionDao.insert(session)

        // Encrypted image file
        val imageFileName = "research_${UUID.randomUUID()}.enc"
        encryptedFileStore.writeEncryptedFile(imageFileName) { it.write("IMAGE_BYTES".toByteArray()) }
        assertThat(encryptedFileStore.exists(imageFileName)).isTrue()

        val capture = CaptureEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            attemptNumber = 1,
            site = "STERNUM",
            capturedAt = virtualTimeMs,
            iso = 100,
            exposureNs = 10_000_000L,
            aeLocked = 1,
            awbLocked = 1,
            colorGains = null,
            qualityStatus = "PASSED",
            qualityFailReasons = null,
            correctionResidualDe = 0.5,
            features = null,
            imageFile = imageFileName,
            imageSha256 = "dummy_sha256"
        )
        captureDao.insert(capture)

        // Outbox item for this session
        val outboxItem = OutboxEntity(
            id = UUID.randomUUID().toString(),
            entityType = "SESSION_MANIFEST",
            entityId = sessionId,
            idempotencyKey = UUID.randomUUID().toString(),
            payloadSha256 = "hash",
            state = "ACKED",
            createdAt = virtualTimeMs,
            updatedAt = virtualTimeMs
        )
        outboxDao.insertOrUpdate(outboxItem)

        // 1. Before 14 days cutoff: retention job does nothing
        virtualTimeMs += 10L * RetentionManager.MILLIS_PER_DAY
        val hook = object : ResearchRetentionServerConfirmationHook {
            override suspend fun isServerConfirmed(sessionId: String): Boolean = true
        }
        val manager = createRetentionManager(hook)
        assertThat(manager.purgeResearchSessions()).isEqualTo(0)
        assertThat(encryptedFileStore.exists(imageFileName)).isTrue()

        // 2. Past 14 days (15 days total) and server confirms COMPLETE
        virtualTimeMs += 5L * RetentionManager.MILLIS_PER_DAY
        val purged = manager.purgeResearchSessions()
        assertThat(purged).isEqualTo(1)

        // File and rows should be purged
        assertThat(encryptedFileStore.exists(imageFileName)).isFalse()
        assertThat(sessionDao.getSessionById(sessionId)).isNull()
    }
}

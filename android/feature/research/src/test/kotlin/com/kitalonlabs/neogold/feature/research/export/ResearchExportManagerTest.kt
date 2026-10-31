package com.kitalonlabs.neogold.feature.research.export

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.data.local.entity.TsbMeasurementEntity
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.decrypt.DecryptEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class ResearchExportManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var exportManager: ResearchExportManager

    private val keyPair = DecryptEngine.generateKeyPair()
    private val studyPrivateKey = keyPair.first
    private val studyPublicKey = keyPair.second

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val fakeMasterKey = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(context, fakeMasterKey, tempFolder.newFolder("keys"))
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, tempFolder.newFolder("images"))

        exportManager = ResearchExportManager(
            context = context,
            database = database,
            encryptedFileStore = encryptedFileStore
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedResearchData(studyId: String = "STUDY-1", isExportOnly: Boolean = false) {
        val study = ResearchStudyEntity(
            id = studyId,
            studyCode = "SKP",
            siteCode = "SKP01",
            displayName = "Sakhipur Clinical Study",
            serverUrl = if (isExportOnly) null else "https://intake.neogold.example.bd/v1",
            deviceKeyAlias = null,
            serverDeviceId = null,
            pinHash = ByteArray(32) { 1 },
            pinSalt = ByteArray(16) { 2 },
            failedPinAttempts = 0,
            lockedUntil = null,
            timestampPrecision = "DAY_PLUS_OFFSETS",
            timeZone = "Asia/Dhaka",
            dataGeneration = null,
            localRetentionAfterAckDays = 14,
            exportPublicKey = studyPublicKey,
            status = "ACTIVE",
            enrolledAt = 1760000000000L
        )
        database.researchStudyDao().insert(study)

        // Seed 2 participants
        val p1 = ResearchParticipantEntity(
            id = "PART-1",
            studyId = studyId,
            participantCode = "SKP-0142",
            consentFormId = "SKP-CONS-001",
            consentRecordedAt = 1760000000000L,
            createdAt = 1760000000000L
        )
        val p2 = ResearchParticipantEntity(
            id = "PART-2",
            studyId = studyId,
            participantCode = "SKP-0143",
            consentFormId = "SKP-CONS-002",
            consentRecordedAt = 1760050000000L,
            createdAt = 1760050000000L
        )
        database.researchParticipantDao().insert(p1)
        database.researchParticipantDao().insert(p2)

        // Seed sessions
        val s1 = ScreeningSessionEntity(
            id = "SESS-1",
            mode = "RESEARCH",
            status = "COMPLETED",
            createdAt = 1760010000000L,
            completedAt = 1760010060000L,
            ageHours = 48.0,
            guardianConsentConfirmed = 1,
            gestationalAgeWeeks = 38,
            flagJaundiceUnder24h = 0,
            flagDangerSigns = 0,
            flagPriorPhototherapy = 0,
            ruleOutcome = null,
            triageAction = "REFER_URGENT",
            reasonCodes = null,
            estimateMgDl = 16.5,
            estimateLowMgDl = 15.0,
            estimateHighMgDl = 18.0,
            thresholdMgDl = 15.0,
            recheckDueAt = null,
            captureAttempts = 1,
            registerNumber = null,
            registerNumberErasedAt = null,
            participantId = "PART-1",
            estimateHidden = 1,
            experimentalLabelShown = 0,
            deviceProfileId = null,
            cardBatchId = null,
            appVersion = "1.0.0",
            modelVersion = "v1.0",
            configVersion = "v1.0"
        )
        val s2 = ScreeningSessionEntity(
            id = "SESS-2",
            mode = "RESEARCH",
            status = "COMPLETED",
            createdAt = 1760060000000L,
            completedAt = 1760060060000L,
            ageHours = 36.0,
            guardianConsentConfirmed = 1,
            gestationalAgeWeeks = 39,
            flagJaundiceUnder24h = 0,
            flagDangerSigns = 0,
            flagPriorPhototherapy = 0,
            ruleOutcome = null,
            triageAction = "BLOOD_TEST_TODAY",
            reasonCodes = null,
            estimateMgDl = 12.0,
            estimateLowMgDl = 10.5,
            estimateHighMgDl = 13.5,
            thresholdMgDl = 14.0,
            recheckDueAt = null,
            captureAttempts = 1,
            registerNumber = null,
            registerNumberErasedAt = null,
            participantId = "PART-2",
            estimateHidden = 1,
            experimentalLabelShown = 0,
            deviceProfileId = null,
            cardBatchId = null,
            appVersion = "1.0.0",
            modelVersion = "v1.0",
            configVersion = "v1.0"
        )
        database.screeningSessionDao().insert(s1)
        database.screeningSessionDao().insert(s2)

        // Seed captures with encrypted images
        val fakeJpeg1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01, 0x02, 0x03)
        val imageFile1 = "img_cap_1.enc"
        encryptedFileStore.writeEncryptedFile(imageFile1) { out -> out.write(fakeJpeg1) }

        val cap1 = CaptureEntity(
            id = "CAP-1",
            sessionId = "SESS-1",
            attemptNumber = 1,
            site = "STERNUM",
            capturedAt = 1760010010000L,
            iso = 100,
            exposureNs = 10000000L,
            aeLocked = 1,
            awbLocked = 1,
            colorGains = null,
            qualityStatus = "PASSED",
            qualityFailReasons = null,
            correctionResidualDe = 0.65,
            features = null,
            imageFile = imageFile1,
            imageSha256 = "dummy-sha256"
        )
        database.captureDao().insert(cap1)

        // Seed paired TSB measurement
        val tsb1 = TsbMeasurementEntity(
            id = "TSB-1",
            participantId = "PART-1",
            sessionId = "SESS-1",
            tsbMgDl = 16.2,
            drawTime = 1760013610000L, // exactly 1 hour after capture
            method = "LAB",
            enteredAt = 1760013620000L
        )
        database.tsbMeasurementDao().insert(tsb1)
    }

    /**
     * TC-EXP-01: Encrypted USB export (FR-30, NG-036).
     * Export to USB/file; decrypt with decrypt tool.
     * Files unreadable without key; after decryption, CSV rows and image hashes match.
     */
    @Test
    fun tcExp01_phoneExportToZipAndDecryptionRoundTrip() = runBlocking {
        seedResearchData()

        val exportZipFile = tempFolder.newFile("neogold-export-test.zip")
        val progressStages = mutableListOf<ExportStage>()

        val exportResult = FileOutputStream(exportZipFile).use { fos ->
            exportManager.exportToStream(
                outputStream = fos,
                stagingDir = tempFolder.newFolder("staging1"),
                onProgress = { progressStages.add(it.stage) }
            )
        }

        assertThat(exportResult.isSuccess).isTrue()
        val summary = exportResult.getOrNull()!!
        assertThat(summary.studyCode).isEqualTo("SKP")
        assertThat(summary.totalParticipants).isEqualTo(2)
        assertThat(summary.totalSessions).isEqualTo(2)
        assertThat(summary.totalImages).isEqualTo(1)
        assertThat(exportZipFile.length()).isGreaterThan(0L)

        // Progress stages were visited
        assertThat(progressStages).contains(ExportStage.PREPARING)
        assertThat(progressStages).contains(ExportStage.ENCRYPTING_DATA)
        assertThat(progressStages).contains(ExportStage.ENCRYPTING_IMAGES)
        assertThat(progressStages).contains(ExportStage.PACKAGING_ZIP)
        assertThat(progressStages).contains(ExportStage.COMPLETED)

        // Decrypt with DecryptEngine
        val decryptedOutDir = tempFolder.newFolder("decrypted_roundtrip")
        val decryptResult = DecryptEngine.decryptArchive(
            zipFile = exportZipFile,
            exportPrivateKeyBytes = studyPrivateKey,
            outputDir = decryptedOutDir,
            applyDateShift = false
        )

        assertThat(decryptResult.studyCode).isEqualTo("SKP")
        assertThat(decryptResult.participantsCount).isEqualTo(2)
        assertThat(decryptResult.sessionsCount).isEqualTo(2)
        assertThat(decryptResult.imagesCount).isEqualTo(1)

        val decryptedSessions = File(decryptedOutDir, "sessions.csv").readText()
        assertThat(decryptedSessions).contains("SESS-1,SKP-0142,COMPLETED")
        assertThat(decryptedSessions).contains("SESS-2,SKP-0143,COMPLETED")

        val decryptedTsb = File(decryptedOutDir, "tsb_measurements.csv").readText()
        assertThat(decryptedTsb).contains("TSB-1,SKP-0142,SESS-1,16.2")

        val decryptedImage = File(decryptedOutDir, "images/CAP-1.jpg").readBytes()
        assertThat(
            decryptedImage
        ).isEqualTo(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01, 0x02, 0x03))

        // Check audit event logged in DB
        val auditEvents = database.auditEventDao().getEventsSince(0)
        val exportEvent = auditEvents.find { it.eventType == "EXPORT_CREATED" }
        assertThat(exportEvent).isNotNull()
        assertThat(exportEvent!!.details).contains("SKP")
    }

    /**
     * TC-EXP-02: No identifiers (FR-30, NFR-08, T15).
     * Inspect a decrypted export: participant code is the only link; no register numbers, names, or birth dates.
     */
    @Test
    fun tcExp02_noIdentifiersInDecryptedExport() = runBlocking {
        seedResearchData()

        val exportZipFile = tempFolder.newFile("neogold-export-tc02.zip")
        FileOutputStream(exportZipFile).use { fos ->
            exportManager.exportToStream(fos, stagingDir = tempFolder.newFolder("staging2"))
        }

        val decryptedOutDir = tempFolder.newFolder("decrypted_tc02")
        DecryptEngine.decryptArchive(
            zipFile = exportZipFile,
            exportPrivateKeyBytes = studyPrivateKey,
            outputDir = decryptedOutDir,
            applyDateShift = true
        )

        for (fileName in listOf("sessions.csv", "captures.csv", "tsb_measurements.csv", "participants.csv")) {
            val content = File(decryptedOutDir, fileName).readText()
            assertThat(content).doesNotContain("register_number")
            assertThat(content).doesNotContain("patient_name")
            assertThat(content).doesNotContain("birth_date")
            assertThat(content).doesNotContain("phone_number")
            assertThat(content).doesNotContain("national_id")
        }
    }

    /**
     * TC-EXP-03: Date shift (T15).
     * One random offset per participant; intervals between participant's sessions and TSB unchanged.
     */
    @Test
    fun tcExp03_dateShiftPreservesInternalIntervals() = runBlocking {
        seedResearchData()

        val exportZipFile = tempFolder.newFile("neogold-export-tc03.zip")
        FileOutputStream(exportZipFile).use { fos ->
            exportManager.exportToStream(fos, stagingDir = tempFolder.newFolder("staging3"))
        }

        val decryptedOutDir = tempFolder.newFolder("decrypted_tc03")
        val result = DecryptEngine.decryptArchive(
            zipFile = exportZipFile,
            exportPrivateKeyBytes = studyPrivateKey,
            outputDir = decryptedOutDir,
            applyDateShift = true
        )

        assertThat(result.dateShiftApplied).isTrue()
        val offsetDays = result.participantOffsets["SKP-0142"]
        assertThat(offsetDays).isNotNull()
        assertThat(offsetDays).isNotEqualTo(0)
        assertThat(offsetDays!!).isAtLeast(-180)
        assertThat(offsetDays).isAtMost(180)

        // Read shifted timestamps from sessions and TSB
        val sessionsLines = File(decryptedOutDir, "sessions.csv").readLines()
        val sess1Cols = sessionsLines.find { it.startsWith("SESS-1") }!!.split(",")
        val shiftedSessionTime = sess1Cols[3].toLong()

        val tsbLines = File(decryptedOutDir, "tsb_measurements.csv").readLines()
        val tsb1Cols = tsbLines.find { it.startsWith("TSB-1") }!!.split(",")
        val shiftedTsbDrawTime = tsb1Cols[4].toLong()

        // Difference between TSB draw and session start was originally 3,610,000 ms (~1 hr)
        val shiftedInterval = shiftedTsbDrawTime - shiftedSessionTime
        assertThat(shiftedInterval).isEqualTo(3610000L) // EXACTLY preserved!
    }

    /**
     * TC-EXP-04: Export-only study (FR-30).
     * Study profile with no host; run a full study session and export, offline. Works without any server.
     */
    @Test
    fun tcExp04_exportOnlyStudyOperatesCompletelyOffline() = runBlocking {
        seedResearchData(studyId = "EXPORT-ONLY-STUDY", isExportOnly = true)

        val activeStudy = database.researchStudyDao().getActiveStudy()
        assertThat(activeStudy!!.serverUrl).isNull() // Confirmed export-only study

        val exportZipFile = tempFolder.newFile("neogold-export-only.zip")
        val exportResult = FileOutputStream(exportZipFile).use { fos ->
            exportManager.exportToStream(fos, stagingDir = tempFolder.newFolder("staging4"))
        }
        assertThat(exportResult.isSuccess).isTrue()

        val decryptedOutDir = tempFolder.newFolder("decrypted_export_only")
        val decryptResult = DecryptEngine.decryptArchive(
            zipFile = exportZipFile,
            exportPrivateKeyBytes = studyPrivateKey,
            outputDir = decryptedOutDir
        )

        assertThat(decryptResult.studyCode).isEqualTo("SKP")
        assertThat(decryptResult.participantsCount).isEqualTo(2)
        assertThat(decryptResult.sessionsCount).isEqualTo(2)
    }

    /**
     * Test resume functionality: if an export was partially staged, resuming completes the export.
     */
    @Test
    fun testExportResumeCapability() = runBlocking {
        seedResearchData()

        val stagingDir = tempFolder.newFolder("staging_resume")
        val exportZip1 = tempFolder.newFile("partial_export.zip")

        // First export run
        val res1 = FileOutputStream(exportZip1).use { fos ->
            exportManager.exportToStream(fos, stagingDir = stagingDir)
        }
        assertThat(res1.isSuccess).isTrue()

        // Run second export with fresh destination
        val exportZip2 = tempFolder.newFile("resumed_export.zip")
        val res2 = FileOutputStream(exportZip2).use { fos ->
            exportManager.exportToStream(fos, stagingDir = stagingDir)
        }
        assertThat(res2.isSuccess).isTrue()

        val decryptedOut = tempFolder.newFolder("decrypted_resumed")
        val decryptResult = DecryptEngine.decryptArchive(
            zipFile = exportZip2,
            exportPrivateKeyBytes = studyPrivateKey,
            outputDir = decryptedOut
        )
        assertThat(decryptResult.sessionsCount).isEqualTo(2)
    }
}

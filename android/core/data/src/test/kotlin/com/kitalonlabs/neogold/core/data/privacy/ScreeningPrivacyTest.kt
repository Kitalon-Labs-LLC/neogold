package com.kitalonlabs.neogold.core.data.privacy

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ScreeningPrivacyTest {

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var imageDir: File
    private lateinit var keyDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = NeoGoldDatabase.buildInMemory(context)
        imageDir = File(context.filesDir, "research_images")
        keyDir = File(context.filesDir, "file_keys")
        imageDir.mkdirs()
        keyDir.mkdirs()

        val masterKeyProvider = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(context, masterKeyProvider, keyDir)
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, imageDir)
    }

    @After
    fun tearDown() {
        database.close()
        imageDir.deleteRecursively()
        keyDir.deleteRecursively()
    }

    @Test
    fun tcLog07_screeningNeverCreatesImageFileOnDisk() = runTest {
        // Complete a screening journey session and captures
        val sessionId = UUID.randomUUID().toString()
        val session = ScreeningSessionEntity(
            id = sessionId,
            mode = "SCREENING",
            status = "COMPLETED",
            createdAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis() + 1000L,
            ageHours = 40.0,
            guardianConsentConfirmed = 1,
            gestationalAgeWeeks = 38,
            flagJaundiceUnder24h = 0,
            flagDangerSigns = 0,
            flagPriorPhototherapy = 0,
            ruleOutcome = null,
            triageAction = "NO_FURTHER_ACTION_FROM_SCREEN",
            reasonCodes = null,
            estimateMgDl = 8.0,
            estimateLowMgDl = 6.5,
            estimateHighMgDl = 9.5,
            thresholdMgDl = 15.0,
            recheckDueAt = null,
            registerNumber = "OPD-101",
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
        database.screeningSessionDao().insert(session)

        // Capture in screening mode: image_file and image_sha256 are ALWAYS null
        val capture = CaptureEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            attemptNumber = 1,
            site = "STERNUM",
            capturedAt = System.currentTimeMillis(),
            iso = 100,
            exposureNs = 10_000_000L,
            aeLocked = 1,
            awbLocked = 1,
            colorGains = null,
            qualityStatus = "PASSED",
            qualityFailReasons = null,
            correctionResidualDe = 0.4,
            features = null,
            imageFile = null, // Screening mode: images never touch disk (FR-24, doc 4 Section 8)
            imageSha256 = null
        )
        database.captureDao().insert(capture)

        // Verify no image file from screening mode exists on disk anywhere
        val filesOnDisk = imageDir.listFiles() ?: emptyArray()
        assertThat(filesOnDisk).isEmpty()

        // List all files in app filesDir
        val allAppFiles = context.filesDir.walkTopDown().filter {
            it.isFile && (it.extension == "jpg" || it.extension == "enc" || it.extension == "tmp")
        }.toList()
        assertThat(allAppFiles).isEmpty()
    }
}

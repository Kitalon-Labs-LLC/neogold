package com.kitalonlabs.neogold.feature.research.export

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.core.security.export.ExportCryptoEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What an export leaves on the phone (BUG-2026-038, TC-EXP-01): the data key never reaches the disk, and a failed, a
 * cancelled or a finished export leaves nothing behind in the staging directory. A failed or cancelled export also
 * discards the file it was writing, and a failure never shows the message of an exception.
 */
@RunWith(RobolectricTestRunner::class)
class ResearchExportCleanupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var store: EncryptedFileStore
    private lateinit var staging: File

    private val publicKey = ExportCryptoEngine.generateKeyPair().second

    /** Opens the image of the capture whose number is [failOn] with an IOException that names a path. */
    private class FlakyStore(private val real: EncryptedFileStore, var failOn: Int = 0) : EncryptedFileStore by real {
        private var opened = 0

        override fun openDecryptedStream(fileName: String, associatedData: ByteArray): InputStream {
            opened++
            if (opened == failOn) throw IOException("cannot read /data/user/0/secret-path/$fileName")
            return real.openDecryptedStream(fileName, associatedData)
        }
    }

    private lateinit var flaky: FlakyStore
    private lateinit var manager: ResearchExportManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java).allowMainThreadQueries().build()
        val keys = DefaultFileKeysetProvider(context, FakeMasterKeyProvider(), tempFolder.newFolder("keys"))
        store = DefaultEncryptedFileStore(context, keys, tempFolder.newFolder("images"))
        flaky = FlakyStore(store)
        manager = ResearchExportManager(context, database, flaky)
        staging = File(tempFolder.root, "export_staging")
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed() {
        database.researchStudyDao().insert(
            ResearchStudyEntity(
                id = "STUDY-1",
                studyCode = "SKP01",
                siteCode = "SKP01",
                displayName = "Synthetic study",
                serverUrl = null,
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
                exportPublicKey = publicKey,
                status = "ACTIVE",
                enrolledAt = 1_760_000_000_000L
            )
        )
        database.researchParticipantDao().insert(
            ResearchParticipantEntity(
                id = "PART-1",
                studyId = "STUDY-1",
                participantCode = "SKP-0142",
                consentFormId = "SKP-CONS-001",
                consentRecordedAt = 1_760_000_000_000L,
                createdAt = 1_760_000_000_000L
            )
        )
        database.screeningSessionDao().insert(
            ScreeningSessionEntity(
                id = "SESS-1",
                mode = "RESEARCH",
                status = "COMPLETED",
                createdAt = 1_760_010_000_000L,
                completedAt = 1_760_010_060_000L,
                ageHours = 48.0,
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
                participantId = "PART-1",
                estimateHidden = 1,
                experimentalLabelShown = 0,
                deviceProfileId = null,
                cardBatchId = null,
                appVersion = "1.0.0",
                modelVersion = null,
                configVersion = "test"
            )
        )
        for (n in 1..IMAGES) {
            val file = "img_$n.enc"
            store.writeEncryptedFile(file) { out -> out.write(ByteArray(64) { (it + n).toByte() }) }
            database.captureDao().insert(
                CaptureEntity(
                    id = "CAP-$n",
                    sessionId = "SESS-1",
                    attemptNumber = 1,
                    site = "STERNUM",
                    capturedAt = 1_760_010_010_000L + n,
                    iso = 100,
                    exposureNs = 10_000_000L,
                    aeLocked = 0,
                    awbLocked = 0,
                    colorGains = null,
                    qualityStatus = "PASSED",
                    qualityFailReasons = null,
                    correctionResidualDe = null,
                    features = null,
                    imageFile = file,
                    imageSha256 = "synthetic"
                )
            )
        }
    }

    private fun filesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    private fun stagingIsEmpty() = !staging.exists() || filesUnder(staging).isEmpty()

    private fun archive() = tempFolder.newFile()

    @Test
    fun tcExp01_theDataKeyIsNeverOnTheDiskWhileAnExportRuns_BUG_2026_038() = runBlocking {
        val seen = mutableSetOf<String>()
        val leaked = mutableListOf<String>()

        val result = manager.exportToStream(archive().outputStream(), staging) {
            for (f in filesUnder(staging)) {
                seen += f.name
                // a keyset, a checkpoint, or anything that is not ciphertext
                if (f.name.contains("checkpoint") || f.name.contains("keyset") || f.readText(Charsets.ISO_8859_1).contains("raw_keyset")) {
                    leaked += f.name
                }
            }
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(seen).isNotEmpty()
        assertThat(leaked).isEmpty()
        assertThat(seen.all { it.endsWith(".enc") }).isTrue()
    }

    @Test
    fun tcExp01_aFinishedExportLeavesNothingInStaging_BUG_2026_038() = runBlocking {
        val result = manager.exportToStream(archive().outputStream(), staging)

        assertThat(result.isSuccess).isTrue()
        assertThat(stagingIsEmpty()).isTrue()
    }

    @Test
    fun tcExp01_anExportThatFailsInTheMiddleOfTheImagesLeavesNothingAndShowsNoExceptionText_BUG_2026_038() = runBlocking {
        flaky.failOn = 2
        val messages = mutableListOf<String>()

        val result = manager.exportToStream(archive().outputStream(), staging) { messages += it.message }

        assertThat(result.isFailure).isTrue()
        assertThat(stagingIsEmpty()).isTrue()
        assertThat(messages.joinToString("\n")).doesNotContain("secret-path")
        assertThat(messages.last()).isEqualTo("Export failed")
    }

    @Test
    fun tcExp01_anExportThatFailsWhileWritingTheArchiveLeavesNothing_BUG_2026_038() = runBlocking {
        val brokenDisk = object : OutputStream() {
            private var written = 0

            override fun write(b: Int) {
                if (++written > BREAK_AFTER) throw IOException("disk full at /storage/secret")
            }
        }

        val result = manager.exportToStream(brokenDisk, staging)

        assertThat(result.isFailure).isTrue()
        assertThat(stagingIsEmpty()).isTrue()
    }

    @Test
    fun tcExp01_leftoversFromAnEarlierRunAreRemovedBeforeANewExportStarts_BUG_2026_038() = runBlocking {
        File(staging, "SKP01/data").mkdirs()
        File(staging, "SKP01/data/old.csv.enc").writeText("left by a killed export")
        File(staging, "SKP01/checkpoint.json").writeText("{\"raw_keyset_b64\":\"old\"}")

        val result = manager.exportToStream(archive().outputStream(), staging)

        assertThat(result.isSuccess).isTrue()
        assertThat(stagingIsEmpty()).isTrue()
        assertThat(File(staging, "SKP01/checkpoint.json").exists()).isFalse()
    }

    @Test
    fun tcExp01_aFailedExportDiscardsTheFileItWasWriting() = runBlocking {
        flaky.failOn = 1
        val written = ByteArrayOutputStream()
        var discarded = false

        val result = manager.exportToDestination(ExportDestination({ written }, { discarded = true }), staging)

        assertThat(result.isFailure).isTrue()
        assertThat(discarded).isTrue()
    }

    @Test
    fun tcExp01_aFinishedExportKeepsItsFile() = runBlocking {
        val written = ByteArrayOutputStream()
        var discarded = false

        val result = manager.exportToDestination(ExportDestination({ written }, { discarded = true }), staging)

        assertThat(result.isSuccess).isTrue()
        assertThat(discarded).isFalse()
        assertThat(written.size()).isGreaterThan(0)
    }

    @Test
    fun tcExp01_aDestinationThatCannotBeOpenedIsAFailureAndIsDiscarded() = runBlocking {
        var discarded = false

        val result = manager.exportToDestination(ExportDestination({ null }, { discarded = true }), staging)

        assertThat(result.isFailure).isTrue()
        assertThat(discarded).isTrue()
    }

    @Test
    fun tcExp01_aCancelledExportStopsAtTheNextFileLeavesNothingAndDiscardsItsFile() {
        val discarded = AtomicBoolean(false)
        val imagesDone = AtomicInteger(0)
        val written = ByteArrayOutputStream()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val job = scope.async {
            manager.exportToDestination(ExportDestination({ written }, { discarded.set(true) }), staging) {
                if (it.stage == ExportStage.ENCRYPTING_IMAGES && it.message.startsWith("Encrypted image")) {
                    if (imagesDone.incrementAndGet() == 1) scope.cancel() // the screen was left after the first image
                }
            }
        }
        val outcome = runBlocking { runCatching { job.await() }.exceptionOrNull() }

        assertThat(outcome).isInstanceOf(CancellationException::class.java)
        assertThat(imagesDone.get()).isLessThan(IMAGES) // it stopped, it did not run to the end
        assertThat(discarded.get()).isTrue()
        assertThat(stagingIsEmpty()).isTrue()
    }

    @Test
    fun theFileNameFollowsTheSpecAndSortsByTime() {
        val name = exportFileName("SKP01", Instant.parse("2026-10-30T07:15:09Z"))

        assertThat(name).isEqualTo("neogold-export-SKP01-20261030T071509Z.zip")
        val later = exportFileName("SKP01", Instant.parse("2026-10-30T07:15:10Z"))
        assertThat(later).isGreaterThan(name)
    }

    @Test
    fun aStudyCodeWithOddCharactersGivesAFileNameThatIsSafeOnEveryFileSystem() {
        val at = Instant.parse("2026-01-02T03:04:05Z")

        assertThat(exportFileName("SK/P 01:..\\x", at)).isEqualTo("neogold-export-SKP01x-20260102T030405Z.zip")
        assertThat(exportFileName("", at)).isEqualTo("neogold-export-STUDY-20260102T030405Z.zip")
        assertThat(exportFileName("এক", at)).isEqualTo("neogold-export-STUDY-20260102T030405Z.zip")
    }

    private companion object {
        const val IMAGES = 3
        const val BREAK_AFTER = 40
    }
}

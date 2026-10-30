package com.kitalonlabs.neogold.feature.research

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
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
import com.kitalonlabs.neogold.engine.camera.research.ResearchJpegSanitizer
import com.kitalonlabs.neogold.engine.cardtrust.TrustListParser
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus
import com.kitalonlabs.neogold.feature.research.data.ResearchCaptureInput
import com.kitalonlabs.neogold.feature.research.data.ResearchProvenance
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * What a saved research session says about itself (package C2): the card batch it used is stored before the session
 * names it, the record names the app, model, config and profile that ran, each photo keeps its own quality verdict, a
 * photo that is not a clean JPEG is refused, and nothing is left behind when saving fails (TC-RSH-10, TC-RSH-13,
 * TC-CARD-02, TC-CARD-05, TC-PRIV-01).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchSessionRecordTest {

    private lateinit var database: NeoGoldDatabase
    private lateinit var store: EncryptedFileStore
    private lateinit var storageDir: File
    private lateinit var participant: ResearchParticipantEntity

    private val provenance = ResearchProvenance(
        appVersion = "1.0.0-rc1",
        modelVersion = "model-7",
        configVersion = "config-3",
        deviceProfileId = null
    )

    private fun repoFile(relative: String): File =
        listOf(relative, "../$relative", "../../$relative", "../../../$relative").map(::File).first { it.exists() }

    private fun cardPayload(vector: String): ByteArray {
        val text = repoFile("card/spec/testvectors/$vector").readText()
        val hex = Regex("\"payload_hex\"\\s*:\\s*\"([0-9a-f]+)\"").find(text)!!.groupValues[1]
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun repository(
        files: EncryptedFileStore = store,
        isAllowedImage: ((ByteArray) -> Boolean)? = null
    ): ResearchRepository {
        val trustList = TrustListParser.parse(repoFile("card/spec/testvectors/test_trust_list.json").readText())
        val hasher = Argon2PinHasher(FakePinHmacKeyProvider())
        val pins = ResearchPinManager(database.researchStudyDao(), database.auditEventDao(), hasher)
        return if (isAllowedImage == null) {
            ResearchRepository(
                database = database,
                encryptedFileStore = files,
                trustList = trustList,
                pinManager = pins,
                pinHasher = hasher,
                provenance = { provenance }
            )
        } else {
            ResearchRepository(
                database = database,
                encryptedFileStore = files,
                trustList = trustList,
                pinManager = pins,
                pinHasher = hasher,
                provenance = { provenance },
                isAllowedImage = isAllowedImage
            )
        }
    }

    /** A real JPEG with only its orientation, as the capture screen makes it. */
    private fun cleanJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.YELLOW)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return ResearchJpegSanitizer.sanitize(out.toByteArray())
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        storageDir = File(context.filesDir, "test_research_record_images").apply {
            deleteRecursively()
            mkdirs()
        }
        val keys = DefaultFileKeysetProvider(
            context,
            FakeMasterKeyProvider(),
            File(context.filesDir, "test_record_keys")
        )
        store = DefaultEncryptedFileStore(context, keys, storageDir)
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java).allowMainThreadQueries().build()
        participant = ResearchParticipantEntity(
            id = "p-1",
            studyId = "s-1",
            participantCode = "SKP-0001",
            consentFormId = "SKP-CF-001",
            consentRecordedAt = 1L,
            createdAt = 1L
        )
        runBlocking {
            database.researchStudyDao().insert(
                ResearchStudyEntity(
                    id = "s-1",
                    studyCode = "SKP-2026",
                    siteCode = "SKP01",
                    displayName = "Synthetic study",
                    serverUrl = null,
                    deviceKeyAlias = null,
                    serverDeviceId = null,
                    pinHash = ByteArray(32),
                    pinSalt = ByteArray(16),
                    lockedUntil = null,
                    dataGeneration = null,
                    status = "ACTIVE",
                    enrolledAt = 1L
                )
            )
            database.researchParticipantDao().insert(participant)
        }
    }

    @After
    fun tearDown() {
        database.close()
        storageDir.deleteRecursively()
    }

    private suspend fun save(
        repository: ResearchRepository,
        captures: List<ResearchCaptureInput>,
        card: ByteArray?
    ) = repository.saveBlindedResearchSession(
        participant = participant,
        ageHours = 40.0,
        gestationalAgeWeeks = 38,
        flagEarlyJaundice = false,
        flagDangerSigns = false,
        flagPriorPhototherapy = false,
        captures = captures,
        cardBatchBytes = card
    )

    private fun shot(site: String, passed: Boolean = true, reasons: List<String> = emptyList(), residual: Double? = null) =
        ResearchCaptureInput(
            site = site,
            imageBytes = cleanJpeg(),
            qualityPassed = passed,
            qualityFailReasons = reasons,
            correctionResidualDe = residual,
            iso = 200,
            exposureNs = 8_000_000L,
            aeLocked = 0,
            awbLocked = 1,
            colorGains = "[1.9,1.0,1.0,2.1]"
        )

    @Test
    fun theCardBatchIsStoredBeforeTheSessionNamesIt_BUG_2026_060() = runBlocking<Unit> {
        val card = cardPayload("card_batch_valid.json")

        val result = save(repository(), listOf(shot("STERNUM")), card).getOrThrow()

        assertThat(result.cardStatus).isEqualTo(CardTrustStatus.VERIFIED)
        val session = database.screeningSessionDao().getSessionsByMode("RESEARCH").single()
        assertThat(session.cardBatchId).isEqualTo("NG-2026-001")
        val batch = database.cardBatchDao().getBatch("NG-2026-001")!!
        assertThat(batch.signatureValid).isEqualTo(1)
        assertThat(batch.qrPayload).isEqualTo(card)
    }

    @Test
    fun anExpiredCardIsRecordedAsExpiredWithItsSignatureValid_TC_CARD_05() = runBlocking<Unit> {
        val result = save(
            repository(),
            listOf(shot("STERNUM")),
            cardPayload("card_batch_invalid_expired.json")
        ).getOrThrow()

        assertThat(result.cardStatus).isEqualTo(CardTrustStatus.EXPIRED)
        assertThat(database.cardBatchDao().getAllBatches().single().signatureValid).isEqualTo(1)
    }

    @Test
    fun aCardFromAnIssuerNotOnTheTrustListIsKeptButNotTrusted_TC_CARD_03() = runBlocking<Unit> {
        val result = save(
            repository(),
            listOf(shot("STERNUM")),
            cardPayload("card_batch_invalid_unknown_issuer.json")
        ).getOrThrow()

        assertThat(result.cardStatus).isEqualTo(CardTrustStatus.UNVERIFIED)
        assertThat(database.cardBatchDao().getAllBatches().single().signatureValid).isEqualTo(0)
        assertThat(database.screeningSessionDao().getSessionsByMode("RESEARCH")).hasSize(1)
    }

    @Test
    fun aCardThatCannotBeReadIsNotKeptAndTheSessionNamesNoBatch_TC_CARD_02() = runBlocking<Unit> {
        val result = save(
            repository(),
            listOf(shot("STERNUM")),
            cardPayload("card_batch_invalid_tampered.json")
        ).getOrThrow()

        assertThat(result.cardStatus).isEqualTo(CardTrustStatus.UNVERIFIED)
        assertThat(database.cardBatchDao().getAllBatches()).isEmpty()
        assertThat(database.screeningSessionDao().getSessionsByMode("RESEARCH").single().cardBatchId).isNull()
    }

    @Test
    fun aDifferentCardUnderAnIdAlreadyOnThePhoneIsNotTaken_TC_CARD_02() = runBlocking<Unit> {
        val card = cardPayload("card_batch_valid.json")
        save(repository(), listOf(shot("STERNUM")), card).getOrThrow()
        // the same ID with other signed bytes (the last signature byte changed): the stored batch stays, and the new
        // session does not name it
        val sameIdOtherBytes = card.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        val result = save(repository(), listOf(shot("STERNUM")), sameIdOtherBytes).getOrThrow()

        assertThat(result.cardStatus).isEqualTo(CardTrustStatus.UNVERIFIED)
        val batch = database.cardBatchDao().getBatch("NG-2026-001")!!
        assertThat(batch.qrPayload).isEqualTo(card)
        assertThat(batch.signatureValid).isEqualTo(1)
        val sessions = database.screeningSessionDao().getSessionsByMode("RESEARCH")
        assertThat(sessions.map { it.cardBatchId }).containsExactly("NG-2026-001", null)
    }

    @Test
    fun aSessionWithNoCardIsSavedWithNoBatch() = runBlocking<Unit> {
        val result = save(repository(), listOf(shot("STERNUM")), card = null).getOrThrow()

        assertThat(result.cardStatus).isEqualTo(CardTrustStatus.UNVERIFIED)
        assertThat(database.screeningSessionDao().getSessionsByMode("RESEARCH").single().cardBatchId).isNull()
        assertThat(database.cardBatchDao().getAllBatches()).isEmpty()
    }

    @Test
    fun theRecordNamesTheAppModelAndConfigThatRan_A11() = runBlocking<Unit> {
        save(repository(), listOf(shot("STERNUM")), card = null).getOrThrow()

        val session = database.screeningSessionDao().getSessionsByMode("RESEARCH").single()
        assertThat(session.appVersion).isEqualTo("1.0.0-rc1")
        assertThat(session.modelVersion).isEqualTo("model-7")
        assertThat(session.configVersion).isEqualTo("config-3")
    }

    @Test
    fun eachPhotoKeepsItsOwnQualityVerdictAndWhatTheCameraReported_TC_RSH_13() = runBlocking<Unit> {
        val captures = listOf(
            shot("STERNUM", passed = true, residual = 1.4),
            shot("FOREHEAD", passed = false, reasons = listOf("GLARE", "BLUR"))
        )

        val saved = save(repository(), captures, card = null).getOrThrow()

        // the positive side of the cleanup test below: a saved photo is one encrypted file in this folder
        assertThat(storageDir.listFiles().orEmpty().count { it.name.endsWith(".enc") }).isEqualTo(2)
        val rows = database.captureDao().getCapturesForSession(saved.sessionId)
        val forehead = rows.first { it.site == "FOREHEAD" }
        val sternum = rows.first { it.site == "STERNUM" }
        assertThat(forehead.qualityStatus).isEqualTo("FAILED")
        assertThat(forehead.qualityFailReasons).isEqualTo("[\"GLARE\",\"BLUR\"]")
        assertThat(sternum.qualityStatus).isEqualTo("PASSED")
        assertThat(sternum.correctionResidualDe).isEqualTo(1.4)
        assertThat(sternum.iso).isEqualTo(200)
        assertThat(sternum.aeLocked).isEqualTo(0)
        assertThat(sternum.awbLocked).isEqualTo(1)
        assertThat(sternum.colorGains).isEqualTo("[1.9,1.0,1.0,2.1]")
    }

    @Test
    fun aPhotoThatIsNotACleanJpegIsRefusedAndNothingIsKept_TC_API_20() = runBlocking<Unit> {
        val notAJpeg = ResearchCaptureInput(
            site = "STERNUM",
            imageBytes = "NOT A JPEG".toByteArray(),
            qualityPassed = true
        )

        val result = save(repository(), listOf(shot("FOREHEAD"), notAJpeg), card = null)

        assertThat(result.isFailure).isTrue()
        assertThat(database.screeningSessionDao().getSessionsByMode("RESEARCH")).isEmpty()
        assertThat(storageDir.listFiles().orEmpty().filter { it.name.endsWith(".enc") }).isEmpty()
    }

    @Test
    fun aPhotoWithGpsInItIsRefused_TC_API_20() = runBlocking<Unit> {
        val withComment = cleanJpeg().let { jpeg ->
            val text = "GPS: 23.8103, 90.4125".toByteArray()
            val out = ByteArrayOutputStream()
            out.write(jpeg, 0, 2)
            out.write(0xFF)
            out.write(0xFE)
            out.write(((text.size + 2) shr 8) and 0xFF)
            out.write((text.size + 2) and 0xFF)
            out.write(text)
            out.write(jpeg, 2, jpeg.size - 2)
            out.toByteArray()
        }

        val result = save(
            repository(),
            listOf(ResearchCaptureInput(site = "STERNUM", imageBytes = withComment, qualityPassed = true)),
            card = null
        )

        assertThat(result.isFailure).isTrue()
    }

    private class FailsOnTheSecondWrite(private val inner: EncryptedFileStore) : EncryptedFileStore by inner {
        private var writes = 0

        override fun writeEncryptedFile(
            fileName: String,
            associatedData: ByteArray,
            writeAction: (OutputStream) -> Unit
        ): String {
            if (++writes == 2) throw IOException("disk full")
            return inner.writeEncryptedFile(fileName, associatedData, writeAction)
        }
    }

    @Test
    fun whenSavingFailsHalfwayTheEncryptedFilesAlreadyWrittenAreRemoved_TC_PRIV_01() = runBlocking<Unit> {
        val failing = FailsOnTheSecondWrite(store)

        val result = save(repository(files = failing), listOf(shot("STERNUM"), shot("FOREHEAD")), card = null)

        assertThat(result.isFailure).isTrue()
        assertThat(database.screeningSessionDao().getSessionsByMode("RESEARCH")).isEmpty()
        assertThat(database.outboxDao().getPendingItems()).isEmpty()
        assertThat(storageDir.listFiles().orEmpty().filter { it.name.endsWith(".enc") }).isEmpty()
    }
}

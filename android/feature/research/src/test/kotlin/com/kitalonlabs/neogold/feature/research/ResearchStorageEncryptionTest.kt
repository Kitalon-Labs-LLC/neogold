package com.kitalonlabs.neogold.feature.research

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
 * TC-RSH-13 and TC-RSH-16: Image encryption at rest and Sclera Flash/No-Flash capture pair.
 * Verifies Tink encryption on disk (FR-29, NG-034) and dual sclera pair persistence (NG-041).
 */
@RunWith(RobolectricTestRunner::class)
class ResearchStorageEncryptionTest {

    private lateinit var database: NeoGoldDatabase
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var repository: ResearchRepository
    private lateinit var storageDir: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        storageDir = File(context.filesDir, "test_research_images")
        if (storageDir.exists()) storageDir.deleteRecursively()
        storageDir.mkdirs()

        val keyDir = File(context.filesDir, "test_file_keys")
        val fakeMasterKey = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(context, fakeMasterKey, keyDir)
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, storageDir)

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
            pinHasher = pinHasher,
            provenance = { TEST_PROVENANCE },
            isAllowedImage = { true }
        )
    }

    @After
    fun tearDown() {
        database.close()
        storageDir.deleteRecursively()
    }

    private suspend fun seedStudy(id: String) {
        database.researchStudyDao().insert(
            ResearchStudyEntity(
                id = id,
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

    /**
     * TC-RSH-13: Research images encrypted (FR-29, NG-034).
     * Raw image files read directly from disk are Tink ciphertext (not plaintext JPEG).
     * Can only be decrypted through EncryptedFileStore.
     */
    @Test
    fun tcRsh13_researchImagesEncryptedAtRest() = runBlocking {
        seedStudy("STUDY-ENC-01")
        val participant = ResearchParticipantEntity(
            id = UUID.randomUUID().toString(),
            studyId = "STUDY-ENC-01",
            participantCode = "SKP-0142",
            consentFormId = "SKP-CF-001",
            consentRecordedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        database.researchParticipantDao().insert(participant)

        // Mock JPEG image bytes starting with standard JPEG magic bytes: FF D8 FF E0
        val plaintextJpegBytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x01, 0x00, 0x60, 0x00, 0x60, 0x00, 0x00
        )

        val captures = listOf(
            ResearchCaptureInput("STERNUM", 1, plaintextJpegBytes, qualityPassed = true)
        )

        val result = repository.saveBlindedResearchSession(
            participant = participant,
            ageHours = 30.0,
            gestationalAgeWeeks = 38,
            flagEarlyJaundice = false,
            flagDangerSigns = false,
            flagPriorPhototherapy = false,
            captures = captures,
            cardBatchBytes = null
        )

        assertThat(result.isSuccess).isTrue()
        val sessionResult = result.getOrNull()!!

        // Find saved capture record
        val savedCaptures = database.captureDao().getCapturesForSession(sessionResult.sessionId)
        assertThat(savedCaptures).hasSize(1)
        val captureRecord = savedCaptures.first()
        val filename = captureRecord.imageFile
        assertThat(filename).isNotNull()

        // Read raw file bytes directly from storage directory on disk
        val rawDiskFile = File(storageDir, filename!!)
        assertThat(rawDiskFile.exists()).isTrue()
        val rawDiskBytes = rawDiskFile.readBytes()

        // 1. Asserts raw bytes are NOT plaintext JPEG (magic bytes are encrypted / scrambled)
        assertThat(
            rawDiskBytes[0] == 0xFF.toByte() && rawDiskBytes[1] == 0xD8.toByte() && rawDiskBytes[2] == 0xFF.toByte()
        )
            .isFalse()
        assertThat(rawDiskBytes).isNotEqualTo(plaintextJpegBytes)

        // 2. Asserts reading through EncryptedFileStore successfully decrypts to exact original plaintext
        val decryptedBytes = encryptedFileStore.openDecryptedStream(filename).use { it.readBytes() }
        assertThat(decryptedBytes).isEqualTo(plaintextJpegBytes)
    }

    /**
     * TC-RSH-16: Sclera pair (NG-041).
     * Captures flash and no-flash sclera images as part of 4-site protocol.
     * Both stored, encrypted on disk, and linked to the research session.
     */
    @Test
    fun tcRsh16_scleraPairStoredAndLinked() = runBlocking {
        seedStudy("STUDY-SCLERA-01")
        val participant = ResearchParticipantEntity(
            id = UUID.randomUUID().toString(),
            studyId = "STUDY-SCLERA-01",
            participantCode = "SKP-0142",
            consentFormId = "SKP-CF-001",
            consentRecordedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        database.researchParticipantDao().insert(participant)

        val sternumBytes = "STERNUM_IMAGE_BYTES".toByteArray()
        val foreheadBytes = "FOREHEAD_IMAGE_BYTES".toByteArray()
        val scleraFlashBytes = "SCLERA_FLASH_IMAGE_BYTES".toByteArray()
        val scleraNoFlashBytes = "SCLERA_NOFLASH_IMAGE_BYTES".toByteArray()

        val protocolCaptures = listOf(
            ResearchCaptureInput("STERNUM", 1, sternumBytes, qualityPassed = true),
            ResearchCaptureInput("FOREHEAD", 1, foreheadBytes, qualityPassed = true),
            ResearchCaptureInput("SCLERA_FLASH", 1, scleraFlashBytes, qualityPassed = true),
            ResearchCaptureInput("SCLERA_NOFLASH", 1, scleraNoFlashBytes, qualityPassed = true)
        )

        val result = repository.saveBlindedResearchSession(
            participant = participant,
            ageHours = 40.0,
            gestationalAgeWeeks = 39,
            flagEarlyJaundice = false,
            flagDangerSigns = false,
            flagPriorPhototherapy = false,
            captures = protocolCaptures,
            cardBatchBytes = null
        )

        assertThat(result.isSuccess).isTrue()
        val sessionId = result.getOrNull()!!.sessionId

        val capturesInDb = database.captureDao().getCapturesForSession(sessionId)
        assertThat(capturesInDb).hasSize(4)

        // Verify SCLERA_FLASH capture
        val flashCap = capturesInDb.find { it.site == "SCLERA_FLASH" }
        assertThat(flashCap).isNotNull()
        assertThat(flashCap!!.sessionId).isEqualTo(sessionId)
        val flashDecrypted = encryptedFileStore.openDecryptedStream(flashCap.imageFile!!).use { it.readBytes() }
        assertThat(flashDecrypted).isEqualTo(scleraFlashBytes)

        // Verify SCLERA_NOFLASH capture
        val noFlashCap = capturesInDb.find { it.site == "SCLERA_NOFLASH" }
        assertThat(noFlashCap).isNotNull()
        assertThat(noFlashCap!!.sessionId).isEqualTo(sessionId)
        val noFlashDecrypted = encryptedFileStore.openDecryptedStream(noFlashCap.imageFile!!).use { it.readBytes() }
        assertThat(noFlashDecrypted).isEqualTo(scleraNoFlashBytes)
    }
}

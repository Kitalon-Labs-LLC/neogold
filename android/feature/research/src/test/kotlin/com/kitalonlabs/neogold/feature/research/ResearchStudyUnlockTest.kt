package com.kitalonlabs.neogold.feature.research

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.pin.ResearchPinManager
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.FakePinHmacKeyProvider
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.TrustList
import com.kitalonlabs.neogold.engine.cardtrust.TrustListParser
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.LocalDate

/**
 * Verification of study enrollment and QR signature verification
 * (TC-RSH-01, TC-RSH-02, TC-RSH-03).
 */
@RunWith(RobolectricTestRunner::class)
class ResearchStudyUnlockTest {

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var trustList: TrustList
    private lateinit var repository: ResearchRepository
    private lateinit var pinManager: ResearchPinManager
    private lateinit var pinHasher: Argon2PinHasher

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val fakeMasterKey = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(
            context,
            fakeMasterKey,
            File(context.filesDir, "test_file_keys")
        )
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, File(context.filesDir, "test_images"))

        val tlFile = findRepoFile("card/spec/testvectors/test_trust_list.json")
        trustList = TrustListParser.parse(tlFile.readText(Charsets.UTF_8))

        val hmacKeyProvider = FakePinHmacKeyProvider()
        pinHasher = Argon2PinHasher(hmacKeyProvider)
        pinManager = ResearchPinManager(database.researchStudyDao(), database.auditEventDao(), pinHasher)

        repository = ResearchRepository(
            database = database,
            encryptedFileStore = encryptedFileStore,
            trustList = trustList,
            pinManager = pinManager,
            cardTrustVerifier = CardTrustVerifier(trustList),
            pinHasher = pinHasher
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * TC-RSH-01: Unlock with signed study QR (FR-25, NG-028).
     * Scan genuine test study QR; set PIN.
     * Study name, partner, and host shown; research mode unlocked and persisted.
     */
    @Test
    fun tcRsh01_unlockWithSignedStudyQr() = runBlocking {
        val vectorJson = loadVectorJson("card/spec/testvectors/study_profile_valid.json")
        val payloadBytes = hexToBytes(vectorJson.getString("payload_hex"))
        val pin = "839174" // Valid 6-digit PIN

        val evalDate = LocalDate.of(2026, 10, 17)
        val result = repository.unlockWithStudyQr(payloadBytes, pin, evaluationDate = evalDate)

        assertThat(result.isSuccess).isTrue()
        val study = result.getOrNull()
        assertThat(study).isNotNull()
        assertThat(study!!.studyCode).isEqualTo("SKP01")
        assertThat(study.displayName).isEqualTo("Sakhipur Community Screening Study")
        assertThat(study.serverUrl).isEqualTo("https://intake.neogold.example.bd/v1")

        // Verified in database
        val saved = database.researchStudyDao().getStudyByCode("SKP01")
        assertThat(saved).isNotNull()
        assertThat(saved!!.displayName).isEqualTo("Sakhipur Community Screening Study")

        // Verified audit event recorded
        val auditEvents = database.auditEventDao().getAllEvents()
        assertThat(auditEvents.any { it.eventType == "STUDY_UNLOCKED" }).isTrue()

        // Unlocking existing study with same PIN succeeds
        val unlockAgain = repository.unlockWithStudyQr(payloadBytes, pin, evaluationDate = evalDate)
        assertThat(unlockAgain.isSuccess).isTrue()

        // Unlocking existing study with incorrect PIN fails
        val wrongPinResult = repository.unlockWithStudyQr(payloadBytes, "999888", evaluationDate = evalDate)
        assertThat(wrongPinResult.isFailure).isTrue()
    }

    /**
     * TC-RSH-02: Forged study QR (FR-25, T6).
     * Host/display name tampered in QR payload.
     * Signature fails; unlock refused; no study stored.
     */
    @Test
    fun tcRsh02_forgedStudyQrRefused() = runBlocking {
        val vectorJson = loadVectorJson("card/spec/testvectors/study_profile_invalid_tampered.json")
        val payloadBytes = hexToBytes(vectorJson.getString("payload_hex"))
        val pin = "839174"

        val evalDate = LocalDate.of(2026, 10, 17)
        val result = repository.unlockWithStudyQr(payloadBytes, pin, evaluationDate = evalDate)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Invalid signature on study profile QR")

        // Database has zero studies
        assertThat(database.researchStudyDao().getActiveStudy()).isNull()
    }

    /**
     * TC-RSH-03: Expired study profile (FR-25).
     * Profile past expires_at (2025-01-01 evaluated in 2026).
     * Refused; no study stored.
     */
    @Test
    fun tcRsh03_expiredStudyProfileRefused() = runBlocking {
        val vectorJson = loadVectorJson("card/spec/testvectors/study_profile_invalid_expired.json")
        val payloadBytes = hexToBytes(vectorJson.getString("payload_hex"))
        val pin = "839174"

        val evalDate = LocalDate.of(2026, 10, 17)
        val result = repository.unlockWithStudyQr(payloadBytes, pin, evaluationDate = evalDate)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Study profile has expired")

        // Database has zero studies
        assertThat(database.researchStudyDao().getActiveStudy()).isNull()
    }

    private fun loadVectorJson(path: String): JSONObject {
        val file = findRepoFile(path)
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            val h = Character.digit(clean[i * 2], 16)
            val l = Character.digit(clean[i * 2 + 1], 16)
            bytes[i] = ((h shl 4) or l).toByte()
        }
        return bytes
    }

    private fun findRepoFile(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            File("../../../$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find file '$relative'. Checked: $candidates")
    }
}

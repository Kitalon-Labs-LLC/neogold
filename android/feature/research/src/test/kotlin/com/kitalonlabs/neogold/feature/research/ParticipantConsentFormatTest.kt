package com.kitalonlabs.neogold.feature.research

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.pin.ResearchPinManager
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.FakePinHmacKeyProvider
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.TrustList
import com.kitalonlabs.neogold.feature.research.data.ResearchFormats
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import androidx.room.Room

/**
 * Verification of consent form ID and participant code formatting
 * (TC-RSH-08, TC-RSH-09, FR-26, T15).
 */
@RunWith(RobolectricTestRunner::class)
class ParticipantConsentFormatTest {

    private lateinit var database: NeoGoldDatabase
    private lateinit var repository: ResearchRepository
    private val studyId = "STUDY-TEST-01"

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val fakeMasterKey = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(context, fakeMasterKey, File(context.filesDir, "file_keys"))
        val encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider, File(context.filesDir, "test_images"))

        val dummyTrustList = TrustList(
            schemaVersion = 1,
            version = 1,
            validFrom = java.time.LocalDate.of(2026, 10, 1),
            validUntil = java.time.LocalDate.of(2028, 10, 1),
            keys = emptyList(),
            revokedCardBatches = emptyList(),
            revokedStudyCodes = emptyList()
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

        // Seed active research study
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * TC-RSH-08: Consent ID required (FR-26, NG-029).
     * Missing or malformed consent form ID blocks session registration.
     */
    @Test
    fun tcRsh08_consentIdRequired() = runBlocking {
        val validCode = "SKP-0142"

        // 1. Missing / blank consent form ID -> rejected
        assertThat(ResearchFormats.isValidConsentFormId("")).isFalse()
        assertThat(ResearchFormats.isValidConsentFormId("   ")).isFalse()
        val emptyResult = repository.registerParticipant(studyId, validCode, "")
        assertThat(emptyResult.isFailure).isTrue()
        assertThat(emptyResult.exceptionOrNull()?.message).contains("Invalid consent form ID format")

        // 2. Malformed consent IDs -> rejected
        val malformedIds = listOf(
            "skp-consent-01", // lowercase
            "S-1",            // prefix too short (< 2 chars)
            "TOOLONGPREFIX-01", // prefix too long (> 5 chars)
            "SKP@CONSENT#01", // invalid special characters
            "SKP-A"           // suffix too short (< 2 chars)
        )
        for (malformed in malformedIds) {
            assertThat(ResearchFormats.isValidConsentFormId(malformed)).isFalse()
            val res = repository.registerParticipant(studyId, validCode, malformed)
            assertThat(res.isFailure).isTrue()
        }

        // 3. Valid consent ID -> accepted
        val validConsent = "SKP-CF-2026-001"
        assertThat(ResearchFormats.isValidConsentFormId(validConsent)).isTrue()
        val validResult = repository.registerParticipant(studyId, validCode, validConsent)
        assertThat(validResult.isSuccess).isTrue()
        assertThat(validResult.getOrNull()?.consentFormId).isEqualTo(validConsent)
    }

    /**
     * TC-RSH-09: Participant code format (FR-26, T15).
     * Accepts 'SKP-0142'; rejects '12345678901234567'.
     * Validates format: ^[A-Z]{2,5}-[0-9]{3,6}$
     */
    @Test
    fun tcRsh09_participantCodeFormat() = runBlocking {
        val validConsent = "SKP-CF-001"

        // Accepted format: SKP-0142
        assertThat(ResearchFormats.isValidParticipantCode("SKP-0142")).isTrue()
        val acceptResult = repository.registerParticipant(studyId, "SKP-0142", validConsent)
        assertThat(acceptResult.isSuccess).isTrue()

        // Additional valid code test vectors
        val validCodes = listOf("DM-100", "CHIT-999999", "DHK-12345", "BGD-0001")
        for (code in validCodes) {
            assertThat(ResearchFormats.isValidParticipantCode(code)).isTrue()
        }

        // Refused format: 12345678901234567 (national id / phone style)
        assertThat(ResearchFormats.isValidParticipantCode("12345678901234567")).isFalse()
        val refuseResult = repository.registerParticipant(studyId, "12345678901234567", validConsent)
        assertThat(refuseResult.isFailure).isTrue()
        assertThat(refuseResult.exceptionOrNull()?.message).contains("Invalid participant code format")

        // Additional invalid code test vectors
        val invalidCodes = listOf(
            "skp-0142",         // lowercase
            "A-100",            // prefix < 2 chars
            "TOOLONG-100",      // prefix > 5 chars
            "SKP-12",           // numeric suffix < 3 digits
            "SKP-1234567",      // numeric suffix > 6 digits
            "SKP_0142",         // underscore instead of hyphen
            "SKP-014A"          // non-numeric suffix
        )
        for (invalid in invalidCodes) {
            assertThat(ResearchFormats.isValidParticipantCode(invalid)).isFalse()
        }
    }
}

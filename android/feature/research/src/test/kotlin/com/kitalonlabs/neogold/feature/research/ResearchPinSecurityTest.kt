package com.kitalonlabs.neogold.feature.research

import android.content.Context
import android.content.Intent
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.pin.PinCheckResult
import com.kitalonlabs.neogold.core.data.pin.ResearchPinManager
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.FakePinHmacKeyProvider
import com.kitalonlabs.neogold.core.security.pin.PinPolicyValidator
import com.kitalonlabs.neogold.core.security.pin.PinValidationResult
import com.kitalonlabs.neogold.core.security.pin.ResearchSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * Verification of research PIN security, policy validation, lockout durability,
 * device Keystore binding, and session re-lock (TC-RSH-04, TC-RSH-05, TC-RSH-06, TC-RSH-07).
 */
@RunWith(RobolectricTestRunner::class)
class ResearchPinSecurityTest {

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var hmacKeyProvider: FakePinHmacKeyProvider
    private lateinit var pinHasher: Argon2PinHasher
    private lateinit var pinManager: ResearchPinManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("test_research_pin.db")

        database = Room.databaseBuilder(context, NeoGoldDatabase::class.java, "test_research_pin.db")
            .allowMainThreadQueries()
            .build()

        hmacKeyProvider = FakePinHmacKeyProvider()
        pinHasher = Argon2PinHasher(hmacKeyProvider)
        pinManager = ResearchPinManager(database.researchStudyDao(), database.auditEventDao(), pinHasher)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("test_research_pin.db")
    }

    /**
     * TC-RSH-04: Weak PINs refused (FR-25).
     * Rejects repeated digits (000000), sequences (123456, 654321),
     * repeated patterns (121212), and top-100 list (112233).
     */
    @Test
    fun tcRsh04_weakPinsRefused() {
        // Repeated digits
        val r0 = PinPolicyValidator.validate("000000")
        assertThat(r0).isInstanceOf(PinValidationResult.Invalid::class.java)
        val r1 = PinPolicyValidator.validate("111111")
        assertThat(r1).isInstanceOf(PinValidationResult.Invalid::class.java)

        // Sequences
        val seqAsc = PinPolicyValidator.validate("123456")
        assertThat(seqAsc).isInstanceOf(PinValidationResult.Invalid::class.java)
        val seqDesc = PinPolicyValidator.validate("654321")
        assertThat(seqDesc).isInstanceOf(PinValidationResult.Invalid::class.java)

        // Repetitions
        val rep12 = PinPolicyValidator.validate("121212")
        assertThat(rep12).isInstanceOf(PinValidationResult.Invalid::class.java)

        // Top 100 list
        val top100 = PinPolicyValidator.validate("112233")
        assertThat(top100).isInstanceOf(PinValidationResult.Invalid::class.java)

        // Short PINs (< 6 digits)
        val shortPin = PinPolicyValidator.validate("12345")
        assertThat(shortPin).isInstanceOf(PinValidationResult.Invalid::class.java)

        // Non-digits
        val nonDigit = PinPolicyValidator.validate("12345a")
        assertThat(nonDigit).isInstanceOf(PinValidationResult.Invalid::class.java)

        // Strong valid PINs
        val valid1 = PinPolicyValidator.validate("839174")
        assertThat(valid1).isInstanceOf(PinValidationResult.Valid::class.java)
        val valid2 = PinPolicyValidator.validate("941728")
        assertThat(valid2).isInstanceOf(PinValidationResult.Valid::class.java)
    }

    /**
     * TC-RSH-05: PIN lockout survives force-stop (FR-25, T1).
     * 5 wrong PINs -> 1 minute lockout; survive force stop; continue to 10 (15 min)
     * and 15 (1 hour). Counter kept across force stop in SQLite; zero data wipe.
     */
    @Test
    fun tcRsh05_pinLockoutSurvivesForceStop() = runBlocking {
        val studyId = UUID.randomUUID().toString()
        val correctPin = "839174"
        val salt = pinHasher.generateSalt()
        val hash = pinHasher.hashPin(correctPin, salt)

        var now = 1_000_000L

        database.researchStudyDao().insert(
            ResearchStudyEntity(
                id = studyId,
                studyCode = "SKP01",
                siteCode = "SKP01",
                displayName = "Sakhipur Study",
                serverUrl = "https://intake.neogold.example.bd/v1",
                deviceKeyAlias = null,
                serverDeviceId = null,
                pinHash = hash,
                pinSalt = salt,
                failedPinAttempts = 0,
                lockedUntil = null,
                timestampPrecision = "DAY_PLUS_OFFSETS",
                timeZone = "Asia/Dhaka",
                dataGeneration = null,
                localRetentionAfterAckDays = 14,
                status = "ACTIVE",
                enrolledAt = now
            )
        )

        // 1. Enter 4 wrong PINs
        repeat(4) {
            val res = pinManager.verifyPin(studyId, "999888", now)
            assertThat(res).isInstanceOf(PinCheckResult.WrongPin::class.java)
            now += 1000L
        }

        // 2. 5th wrong PIN -> 1 minute lockout (60,000 ms)
        val res5 = pinManager.verifyPin(studyId, "999888", now)
        assertThat(res5).isInstanceOf(PinCheckResult.LockedOut::class.java)
        val locked5 = res5 as PinCheckResult.LockedOut
        assertThat(locked5.remainingMinutes).isEqualTo(1)

        // 3. Simulate FORCE-STOP: close database connection and reopen
        database.close()
        database = Room.databaseBuilder(context, NeoGoldDatabase::class.java, "test_research_pin.db")
            .allowMainThreadQueries()
            .build()
        pinManager = ResearchPinManager(database.researchStudyDao(), database.auditEventDao(), pinHasher)

        // Verify failure counter kept across restart
        val studyAfterRestart = database.researchStudyDao().getStudyById(studyId)
        assertThat(studyAfterRestart).isNotNull()
        assertThat(studyAfterRestart!!.failedPinAttempts).isEqualTo(5)
        assertThat(studyAfterRestart.lockedUntil).isEqualTo(now + 60_000L)

        // While locked, check returns LockedOut without executing verification
        val lockedCheck = pinManager.verifyPin(studyId, correctPin, now + 10_000L)
        assertThat(lockedCheck).isInstanceOf(PinCheckResult.LockedOut::class.java)

        // Fast-forward past 1 minute lockout
        now += 61_000L

        // Continue to 10 wrong attempts (5 more)
        repeat(4) {
            val res = pinManager.verifyPin(studyId, "999888", now)
            assertThat(res).isInstanceOf(PinCheckResult.WrongPin::class.java)
            now += 1000L
        }
        val res10 = pinManager.verifyPin(studyId, "999888", now)
        assertThat(res10).isInstanceOf(PinCheckResult.LockedOut::class.java)
        val locked10 = res10 as PinCheckResult.LockedOut
        assertThat(locked10.remainingMinutes).isEqualTo(15)

        // Fast-forward past 15 minute lockout
        now += (15 * 60_000L) + 1000L

        // Continue to 15 wrong attempts (5 more)
        repeat(4) {
            pinManager.verifyPin(studyId, "999888", now)
            now += 1000L
        }
        val res15 = pinManager.verifyPin(studyId, "999888", now)
        assertThat(res15).isInstanceOf(PinCheckResult.LockedOut::class.java)
        val locked15 = res15 as PinCheckResult.LockedOut
        assertThat(locked15.remainingMinutes).isEqualTo(60)

        // Verify no data was wiped
        val studyFinal = database.researchStudyDao().getStudyById(studyId)
        assertThat(studyFinal).isNotNull()
        assertThat(studyFinal!!.failedPinAttempts).isEqualTo(15)
    }

    /**
     * TC-RSH-06: PIN hash useless off the phone (T1).
     * Argon2id hash is device-bound via non-exportable Keystore HMAC-SHA256.
     * Copying database + salt to an external computer without the phone's Keystore HMAC key
     * fails verification for all candidate PINs.
     */
    @Test
    fun tcRsh06_pinHashUselessOffThePhone() {
        val correctPin = "839174"
        val salt = pinHasher.generateSalt()

        // 1. Legitimate hash computed on the phone (with phone's HMAC key K9)
        val legitimateHash = pinHasher.hashPin(correctPin, salt)

        // 2. Off-phone attacker extracts database: knows salt and legitimateHash
        // But attacker has a different HMAC key or no HMAC key
        val attackerHmacKey = FakePinHmacKeyProvider(
            keyBytes = "DIFFERENT_OFFLINE_ATTACKER_KEY_000".toByteArray(Charsets.UTF_8)
        )
        val attackerHasher = Argon2PinHasher(attackerHmacKey)

        // Attacker attempts brute force: trying correct PIN and other PINs
        val candidatePins = listOf("839174", "000000", "123456", "999999", "839175")
        for (candidate in candidatePins) {
            val verified = attackerHasher.verifyPin(candidate, salt, legitimateHash)
            assertThat(verified).isFalse()
        }

        // On legitimate phone with correct key, correct PIN verifies
        assertThat(pinHasher.verifyPin(correctPin, salt, legitimateHash)).isTrue()
    }

    /**
     * TC-RSH-06 (key loss): a PIN hash whose Keystore key K9 is gone cannot be checked. The result says so, the failure
     * counter does not move and no lockout starts, however often the correct PIN is entered (BUG-2026-011).
     */
    @Test
    fun tcRsh06_lostVerifierKeyIsNotAWrongPin() = runBlocking {
        val studyId = UUID.randomUUID().toString()
        val correctPin = "839174"
        val salt = pinHasher.generateSalt()
        val hash = pinHasher.hashPin(correctPin, salt)
        database.researchStudyDao().insert(
            ResearchStudyEntity(
                id = studyId,
                studyCode = "SKP01",
                siteCode = "SKP01",
                displayName = "Sakhipur Study",
                serverUrl = "https://intake.neogold.example.bd/v1",
                deviceKeyAlias = null,
                serverDeviceId = null,
                pinHash = hash,
                pinSalt = salt,
                failedPinAttempts = 2,
                lockedUntil = null,
                timestampPrecision = "DAY_PLUS_OFFSETS",
                timeZone = "Asia/Dhaka",
                dataGeneration = null,
                localRetentionAfterAckDays = 14,
                status = "ACTIVE",
                enrolledAt = 1_000_000L
            )
        )

        hmacKeyProvider.deleteKey()

        repeat(8) {
            assertThat(pinManager.verifyPin(studyId, correctPin, 2_000_000L)).isEqualTo(PinCheckResult.VerifierKeyLost)
        }
        val after = database.researchStudyDao().getStudyById(studyId)!!
        assertThat(after.failedPinAttempts).isEqualTo(2)
        assertThat(after.lockedUntil).isNull()
    }

    /**
     * TC-RSH-07: Re-lock (FR-25).
     * Background research mode for 5 minutes; separately, turn screen off.
     * PIN required again in both scenarios.
     */
    @Test
    fun tcRsh07_relockOnBackgroundAndScreenOff() {
        val sessionManager = ResearchSessionManager()

        // Session starts locked
        assertThat(sessionManager.isUnlocked.value).isFalse()

        // User unlocks session
        val studyId = "STUDY-01"
        sessionManager.unlock(studyId, 1_000_000L)
        assertThat(sessionManager.isUnlocked.value).isTrue()

        // 1. Simulate 5 minutes of inactivity in background
        sessionManager.onAppBackgrounded(1_000_000L)
        sessionManager.onAppForegrounded(1_000_000L + 5 * 60 * 1000L + 1000L)
        assertThat(sessionManager.isUnlocked.value).isFalse()

        // 2. Unlock again and simulate SCREEN_OFF broadcast
        sessionManager.unlock(studyId, 2_000_000L)
        assertThat(sessionManager.isUnlocked.value).isTrue()

        sessionManager.registerScreenOffReceiver(context)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Immediately locked
        assertThat(sessionManager.isUnlocked.value).isFalse()
        sessionManager.unregisterScreenOffReceiver(context)
    }
}

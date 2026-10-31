package com.kitalonlabs.neogold.feature.research

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.pin.ResearchPinManager
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.FakePinHmacKeyProvider
import com.kitalonlabs.neogold.core.security.pin.ResearchSessionManager
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.TrustListParser
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus
import com.kitalonlabs.neogold.feature.research.data.ResearchCaptureInput
import com.kitalonlabs.neogold.feature.research.data.ResearchMessage
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import com.kitalonlabs.neogold.feature.research.ui.ResearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The research view model over a real database and the real PIN hashing (TC-RSH-01, TC-RSH-04, TC-RSH-05, TC-RSH-07,
 * TC-RSH-08, TC-RSH-09): scan, confirm, enroll, wrong PIN, lockout, session lock, and the save path. It judges state only,
 * never message text: the screens write the text in the language they show.
 */
@RunWith(RobolectricTestRunner::class)
class ResearchViewModelTest {

    private lateinit var database: NeoGoldDatabase
    private lateinit var repository: ResearchRepository
    private lateinit var session: ResearchSessionManager
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: ResearchViewModel
    private lateinit var qr: ByteArray

    private val pin = "839174"

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java).allowMainThreadQueries().build()
        val files = DefaultEncryptedFileStore(
            context,
            DefaultFileKeysetProvider(context, FakeMasterKeyProvider(), File(context.filesDir, "vm_file_keys")),
            File(context.filesDir, "vm_images")
        )
        val trustList = TrustListParser.parse(findRepoFile("card/spec/testvectors/test_trust_list.json").readText())
        val hasher = Argon2PinHasher(FakePinHmacKeyProvider())
        repository = ResearchRepository(
            database = database,
            encryptedFileStore = files,
            trustList = trustList,
            pinManager = ResearchPinManager(database.researchStudyDao(), database.auditEventDao(), hasher),
            cardTrustVerifier = CardTrustVerifier(trustList),
            pinHasher = hasher,
            provenance = { TEST_PROVENANCE },
            isAllowedImage = { true }
        )
        session = ResearchSessionManager()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        viewModel = ResearchViewModel(repository, session, scope = scope, cpu = Dispatchers.Default)
        qr = hexToBytes(
            JSONObject(findRepoFile("card/spec/testvectors/study_profile_valid.json").readText()).getString("payload_hex")
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for: $what")
            Thread.sleep(POLL_MS)
        }
    }

    private fun enroll() {
        viewModel.onStudyQrScanned(qr)
        val done = AtomicBoolean(false)
        viewModel.enrollWithScannedProfile(pin, pin) { done.set(true) }
        awaitUntil("enrollment") { done.get() }
    }

    private fun lockThenWaitForTheViewModelToSeeIt() {
        session.lock()
        awaitUntil("the lock to reach the view model") { !viewModel.isUnlocked.value }
    }

    private fun unlockWith(candidate: String): Boolean {
        val opened = AtomicBoolean(false)
        viewModel.unlockWithPin(candidate) { opened.set(true) }
        awaitUntil("the PIN check to finish") { opened.get() || viewModel.error.value != null }
        return opened.get()
    }

    @Test
    fun tcRsh01_aScannedQrShowsWhichStudyItNamesBeforeAnyPinIsAsked() {
        viewModel.onStudyQrScanned(qr)

        assertThat(viewModel.scannedProfile.value?.studyCode).isEqualTo("SKP01")
        assertThat(viewModel.error.value).isNull()
        assertThat(viewModel.isUnlocked.value).isFalse()
    }

    @Test
    fun tcRsh02_aQrThatDoesNotVerifyIsRefusedAndShowsNoStudy() {
        val tampered = hexToBytes(
            JSONObject(findRepoFile("card/spec/testvectors/study_profile_invalid_tampered.json").readText())
                .getString("payload_hex")
        )

        viewModel.onStudyQrScanned(tampered)

        assertThat(viewModel.scannedProfile.value).isNull()
        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.QR_NOT_VALID)
    }

    @Test
    fun tcRsh01_scanThenConfirmThenEnrollOpensResearchModeAndClearsTheScan() {
        enroll()

        assertThat(viewModel.isUnlocked.value).isTrue()
        assertThat(viewModel.activeStudy.value?.studyCode).isEqualTo("SKP01")
        assertThat(viewModel.scannedProfile.value).isNull()
        assertThat(study("SKP01")).isNotNull()
    }

    @Test
    fun tcRsh01_aScannedQrSurvivesTheFirstLockedStateEveryNewViewModelReceives() = runTest {
        // A paused dispatcher: the view model's collector of the session has not run yet when the QR is scanned.
        val paused = StandardTestDispatcher(testScheduler)
        val fresh = ResearchViewModel(
            repository = repository,
            sessionManager = session,
            scope = CoroutineScope(paused + SupervisorJob()),
            cpu = paused
        )

        fresh.onStudyQrScanned(qr)
        advanceUntilIdle() // now the collector receives the session's first state, "locked"

        assertThat(fresh.scannedProfile.value).isNotNull()
    }

    @Test
    fun tcRsh01_twoPinsThatDifferEnrollNothing() {
        viewModel.onStudyQrScanned(qr)

        viewModel.enrollWithScannedProfile(pin, "839175") { fail("must not enroll") }

        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.PINS_DO_NOT_MATCH)
        assertThat(study("SKP01")).isNull()
        assertThat(viewModel.scannedProfile.value).isNotNull() // the worker can type the PINs again
    }

    @Test
    fun tcRsh04_aWeakPinIsRefusedAtEnrollment() {
        viewModel.onStudyQrScanned(qr)

        viewModel.enrollWithScannedProfile("123456", "123456") { fail("must not enroll") }
        awaitUntil("the refusal") { viewModel.error.value != null }

        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.WEAK_PIN)
        assertThat(study("SKP01")).isNull()
    }

    @Test
    fun tcRsh05_aWrongPinKeepsResearchModeLockedAndSaysHowManyTriesAreLeft() {
        enroll()
        lockThenWaitForTheViewModelToSeeIt()

        val opened = unlockWith("999888")

        assertThat(opened).isFalse()
        assertThat(viewModel.isUnlocked.value).isFalse()
        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.WRONG_PIN)
        assertThat(viewModel.error.value?.attempts).isGreaterThan(0)
    }

    @Test
    fun tcRsh05_fiveWrongPinsLockTheStudyAndEvenTheRightPinIsRefusedUntilItEnds() {
        enroll()
        lockThenWaitForTheViewModelToSeeIt()

        repeat(WRONG_BEFORE_LOCKOUT) {
            viewModel.clearError()
            unlockWith("99988$it")
        }

        awaitUntil("the lockout") { viewModel.isLockedOut.value }
        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.LOCKED_OUT)
        assertThat(viewModel.lockoutMinutes.value).isAtLeast(1)

        viewModel.clearError()
        assertThat(unlockWith(pin)).isFalse()
        assertThat(viewModel.isUnlocked.value).isFalse()
    }

    @Test
    fun tcRsh01_theRightPinOpensResearchModeAgainAfterALock() {
        enroll()
        lockThenWaitForTheViewModelToSeeIt()

        assertThat(unlockWith(pin)).isTrue()

        awaitUntil("unlocked") { viewModel.isUnlocked.value }
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun tcRsh07_aPinCheckThatFinishesAfterALockDoesNotOpenResearchMode() {
        enroll()
        lockThenWaitForTheViewModelToSeeIt()
        val opened = AtomicBoolean(false)

        viewModel.unlockWithPin(pin) { opened.set(true) } // the check takes a while: Argon2 on a worker thread
        session.lock() // the screen turns off while it runs
        Thread.sleep(SETTLE_MS * 2)

        assertThat(opened.get()).isFalse()
        assertThat(viewModel.isUnlocked.value).isFalse()
    }

    @Test
    fun tcRsh07_aLockClearsTheParticipantTheInputsTheCapturesAndTheResult() {
        enroll()
        registerParticipant()
        viewModel.onCapturesCompleted(listOf(shot("STERNUM"))) {}
        assertThat(viewModel.currentParticipant.value).isNotNull()
        assertThat(viewModel.currentCaptures.value).hasSize(1)

        lockThenWaitForTheViewModelToSeeIt()
        awaitUntil("the session state to clear") { viewModel.currentParticipant.value == null }

        assertThat(viewModel.clinicalInputs.value).isNull()
        assertThat(viewModel.currentCaptures.value).isEmpty()
        assertThat(viewModel.lastSessionResult.value).isNull()
        assertThat(viewModel.cardTrustStatus.value).isEqualTo(CardTrustStatus.UNVERIFIED)
    }

    @Test
    fun tcRsh07_aLockAlsoDropsAScannedQrThatWasWaitingForItsPin() {
        viewModel.onStudyQrScanned(qr)
        assertThat(viewModel.scannedProfile.value).isNotNull()

        session.unlock("any-study")
        awaitUntil("unlocked") { viewModel.isUnlocked.value }
        lockThenWaitForTheViewModelToSeeIt()
        awaitUntil("the scan to clear") { viewModel.scannedProfile.value == null }
    }

    @Test
    fun tcRsh08_theSessionIsSavedWithTheBlindedRecordAndTheResultIsShown() {
        enroll()
        registerParticipant()
        viewModel.onCapturesCompleted(listOf(shot("STERNUM"), shot("FOREHEAD"))) {}

        val done = AtomicBoolean(false)
        viewModel.completeSessionWithTsb(tsbMgDl = null, drawTimeMillis = null, method = null) { done.set(true) }
        awaitUntil("the save") { done.get() || viewModel.error.value != null }

        assertThat(viewModel.error.value).isNull()
        val result = viewModel.lastSessionResult.value
        assertThat(result?.capturesCount).isEqualTo(2)
        assertThat(result?.participantCode).isEqualTo("SKP-0142")
        val saved = researchSessions().single()
        assertThat(saved.estimateHidden).isEqualTo(1)
    }

    @Test
    fun tcRsh09_aBirthTimeAfterTheSaveTimeSavesNothingAndSaysTheAgeIsWrong() {
        enroll()
        registerParticipant(birthTimeMillis = System.currentTimeMillis() + A_DAY_MS)
        viewModel.onCapturesCompleted(listOf(shot("STERNUM"))) {}

        viewModel.completeSessionWithTsb(tsbMgDl = null, drawTimeMillis = null, method = null) { fail("must not save") }
        awaitUntil("the refusal") { viewModel.error.value != null }

        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.AGE_OUT_OF_RANGE)
        assertThat(researchSessions()).isEmpty()
    }

    @Test
    fun tcRsh07_registeringRightAfterALockPutsNothingInMemoryEvenBeforeTheViewModelHasSeenTheLock() {
        enroll()
        session.lock() // not waited for: the view model's own copy of the state still says unlocked
        val wentOn = AtomicBoolean(false)

        viewModel.registerParticipantAndInputs(
            participantCode = "SKP-0142",
            consentFormId = "CF-2026-0001",
            birthTimeMillis = System.currentTimeMillis() - 48 * A_HOUR_MS,
            ageHours = 48.0,
            gestationalAgeWeeks = 38,
            flagEarlyJaundice = false,
            flagDangerSigns = false,
            flagPriorPhototherapy = false
        ) { wentOn.set(true) }
        Thread.sleep(SETTLE_MS)

        assertThat(wentOn.get()).isFalse()
        assertThat(viewModel.currentParticipant.value).isNull()
        assertThat(viewModel.clinicalInputs.value).isNull()
    }

    @Test
    fun tcRsh07_aSaveRightAfterALockSavesNothingAndShowsNothing() {
        enroll()
        registerParticipant()
        viewModel.onCapturesCompleted(listOf(shot("STERNUM"))) {}
        session.lock() // not waited for
        val saved = AtomicBoolean(false)

        viewModel.completeSessionWithTsb(tsbMgDl = null, drawTimeMillis = null, method = null) { saved.set(true) }
        Thread.sleep(SETTLE_MS)

        assertThat(saved.get()).isFalse()
        assertThat(researchSessions()).isEmpty()
        assertThat(viewModel.lastSessionResult.value).isNull()
    }

    @Test
    fun tcRsh07_anEnrollmentThatFinishesAfterALockDoesNotOpenResearchMode() {
        viewModel.onStudyQrScanned(qr)
        val opened = AtomicBoolean(false)

        viewModel.enrollWithScannedProfile(pin, pin) { opened.set(true) }
        session.lock() // while the PIN is being hashed
        Thread.sleep(SETTLE_MS * 2)

        assertThat(opened.get()).isFalse()
        assertThat(viewModel.isUnlocked.value).isFalse()
    }

    @Test
    fun tcRsh07_aTsbEnteredLaterIsRefusedWhileLocked() {
        enroll()
        registerParticipant()
        lockThenWaitForTheViewModelToSeeIt()
        val recorded = AtomicBoolean(false)

        viewModel.recordDeferredTsb("SKP-0142", 14.2, System.currentTimeMillis() - A_HOUR_MS, "LAB") {
            recorded.set(true)
        }
        Thread.sleep(SETTLE_MS)

        assertThat(recorded.get()).isFalse()
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun tcRsh10_theWholeWiredSessionLogsNoEstimateAndNoPersonalValue() {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs.add((message + " " + (t?.message ?: "")).lowercase())
            }
        }
        Timber.plant(tree)
        try {
            enroll()
            val born = System.currentTimeMillis() - 48 * A_HOUR_MS
            registerParticipant(birthTimeMillis = born)
            viewModel.onCapturesCompleted(listOf(shot("STERNUM"))) {}
            val done = AtomicBoolean(false)
            viewModel.completeSessionWithTsb(
                tsbMgDl = 14.2,
                drawTimeMillis = System.currentTimeMillis() - A_HOUR_MS,
                method = "LAB"
            ) {
                done.set(true)
            }
            awaitUntil("the save") { done.get() || viewModel.error.value != null }
            lockThenWaitForTheViewModelToSeeIt()

            val everything = logs.joinToString("\n")
            for (forbidden in listOf("estimate", "triage", "bilirubin", "phototherapy")) {
                assertThat(everything).doesNotContain(forbidden)
            }
            // personal or clinical values never reach a log (doc 8 Section 6.6)
            for (value in listOf("skp-0142", "cf-2026-0001", pin, born.toString(), "14.2")) {
                assertThat(everything).doesNotContain(value)
            }
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun aSecondTapOnSaveWhileOneRunsIsIgnoredSoOnlyOneSessionIsSaved() {
        enroll()
        registerParticipant()
        viewModel.onCapturesCompleted(listOf(shot("STERNUM"))) {}

        val completions = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(2) {
            viewModel.completeSessionWithTsb(tsbMgDl = null, drawTimeMillis = null, method = null) {
                completions.incrementAndGet()
            }
        }
        awaitUntil("the first save") { completions.get() >= 1 }
        Thread.sleep(SETTLE_MS)

        assertThat(completions.get()).isEqualTo(1)
        assertThat(researchSessions()).hasSize(1)
    }

    private fun registerParticipant(birthTimeMillis: Long = System.currentTimeMillis() - 48 * A_HOUR_MS) {
        val done = AtomicBoolean(false)
        viewModel.registerParticipantAndInputs(
            participantCode = "SKP-0142",
            consentFormId = "CF-2026-0001",
            birthTimeMillis = birthTimeMillis,
            ageHours = 48.0,
            gestationalAgeWeeks = 38,
            flagEarlyJaundice = false,
            flagDangerSigns = false,
            flagPriorPhototherapy = false
        ) { done.set(true) }
        awaitUntil("participant registration") { done.get() || viewModel.error.value != null }
        assertThat(viewModel.error.value).isNull()
    }

    private fun shot(site: String) = ResearchCaptureInput(
        site = site,
        imageBytes = ByteArray(32) { it.toByte() },
        qualityPassed = true
    )

    private fun study(code: String) = runBlocking { database.researchStudyDao().getStudyByCode(code) }

    private fun researchSessions() = runBlocking { database.screeningSessionDao().getSessionsByMode("RESEARCH") }

    private fun findRepoFile(relative: String): File {
        val candidates =
            listOf(File(relative), File("../$relative"), File("../../$relative"), File("../../../$relative"))
        return candidates.firstOrNull { it.exists() } ?: error("Could not find file '$relative'. Checked: $candidates")
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.trim().length / 2) { hex.trim().substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val WAIT_MS = 60_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 1_500L
        const val WRONG_BEFORE_LOCKOUT = 5
        const val A_HOUR_MS = 3_600_000L
        const val A_DAY_MS = 24 * A_HOUR_MS
    }
}

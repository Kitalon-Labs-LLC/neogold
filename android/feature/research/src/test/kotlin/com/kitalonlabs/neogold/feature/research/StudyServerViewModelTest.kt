package com.kitalonlabs.neogold.feature.research

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.pin.ResearchPinManager
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.FakePinHmacKeyProvider
import com.kitalonlabs.neogold.core.security.pin.ResearchSessionManager
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.PayloadParser
import com.kitalonlabs.neogold.engine.cardtrust.TrustListParser
import com.kitalonlabs.neogold.feature.research.data.ResearchError
import com.kitalonlabs.neogold.feature.research.data.ResearchMessage
import com.kitalonlabs.neogold.feature.research.data.ResearchRepository
import com.kitalonlabs.neogold.feature.research.data.StudyServer
import com.kitalonlabs.neogold.feature.research.data.StudyServerFailure
import com.kitalonlabs.neogold.feature.research.ui.ResearchViewModel
import com.kitalonlabs.neogold.feature.research.ui.StudyServerViewModel
import com.kitalonlabs.neogold.feature.research.ui.enrollmentRefusal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Joining and leaving the study server, and "Upload now" (BUG-2026-026, BUG-2026-083 items 8, 14, 23; TC-API-07,
 * TC-SYNC-10, TC-SYNC-16). The server is a fake that records what it was asked; the PIN checks are the real ones (Argon2
 * and the lockout), so a wrong PIN here costs a try exactly as at R01. It judges state only, never message text.
 */
@RunWith(RobolectricTestRunner::class)
class StudyServerViewModelTest {

    /** A study server that records its calls and can be told to fail or to wait. */
    private class FakeServer(private val database: () -> NeoGoldDatabase) : StudyServer {
        val enrolls = mutableListOf<Pair<ByteArray, String>>()
        val leaves = AtomicInteger()
        val uploadNows = AtomicInteger()
        val schedules = AtomicInteger()
        var failure: Throwable? = null

        /** When set, enroll waits for this before answering. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun enroll(study: ResearchStudyEntity, qrPayload: ByteArray, enrollmentCode: String) {
            enrolls += qrPayload to enrollmentCode
            gate?.await()
            failure?.let { throw it }
            database().researchStudyDao().update(study.copy(serverDeviceId = "d-1", deviceKeyAlias = "alias-1"))
        }

        override suspend fun leave(study: ResearchStudyEntity) {
            leaves.incrementAndGet()
            failure?.let { throw it }
            database().researchStudyDao().update(study.copy(serverDeviceId = null, deviceKeyAlias = null))
        }

        override fun uploadNow() {
            uploadNows.incrementAndGet()
        }

        override fun scheduleUploads() {
            schedules.incrementAndGet()
        }
    }

    private lateinit var database: NeoGoldDatabase
    private lateinit var repository: ResearchRepository
    private lateinit var session: ResearchSessionManager
    private lateinit var scope: CoroutineScope
    private lateinit var research: ResearchViewModel
    private lateinit var server: FakeServer
    private lateinit var viewModel: StudyServerViewModel
    private lateinit var qr: ByteArray
    private val studyChanged = AtomicInteger()

    private val pin = "839174"
    private val code = "7K2X-M49P-3QW8"

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java).allowMainThreadQueries().build()
        val files = DefaultEncryptedFileStore(
            context,
            DefaultFileKeysetProvider(context, FakeMasterKeyProvider(), File(context.filesDir, "srv_file_keys")),
            File(context.filesDir, "srv_images")
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
        research = ResearchViewModel(repository, session, scope = scope, cpu = Dispatchers.Default)
        server = FakeServer { database }
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

    /** The study SKP01 is on the phone, research mode is unlocked, and the server view model has read it. */
    private fun phoneWithTheStudy() {
        research.onStudyQrScanned(qr)
        val done = AtomicBoolean(false)
        research.enrollWithScannedProfile(pin, pin) { done.set(true) }
        awaitUntil("the study to be set up") { done.get() }
        viewModel = StudyServerViewModel(repository, server, session, {
            studyChanged.incrementAndGet()
        }, scope, Dispatchers.Default)
        awaitUntil("the study to be read") { viewModel.study.value != null }
    }

    private fun markEnrolledOnTheServer() = runBlocking {
        val study = database.researchStudyDao().getActiveStudy()!!
        database.researchStudyDao().update(study.copy(serverDeviceId = "d-1", deviceKeyAlias = "alias-1"))
        viewModel.refresh()
        awaitUntil("the enrolled study to be read") { viewModel.study.value?.serverDeviceId == "d-1" }
    }

    private fun scanTheStudyQr() {
        viewModel.onQrScanned(qr)
        assertThat(viewModel.scannedProfile.value).isNotNull()
    }

    private fun enrollAndWait(entered: String = code): Boolean {
        val done = AtomicBoolean(false)
        viewModel.enroll(entered) { done.set(true) }
        awaitUntil(
            "the enrollment to finish"
        ) { (done.get() || viewModel.error.value != null) && !viewModel.isBusy.value }
        return done.get()
    }

    @Test
    fun tcApi07_aVerifiedQrOfTheStudyOnThePhoneIsAccepted() {
        phoneWithTheStudy()

        viewModel.onQrScanned(qr)

        assertThat(viewModel.scannedProfile.value?.studyCode).isEqualTo("SKP01")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun tcApi07_aQrThatDoesNotVerifyIsRefused() {
        phoneWithTheStudy()
        val tampered = hexToBytes(
            JSONObject(findRepoFile("card/spec/testvectors/study_profile_invalid_tampered.json").readText())
                .getString("payload_hex")
        )

        viewModel.onQrScanned(tampered)

        assertThat(viewModel.scannedProfile.value).isNull()
        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.QR_NOT_VALID)
    }

    @Test
    fun tcApi07_aValidQrOfAnotherStudyIsRefused_andNothingIsSentOrStored() {
        // The phone holds study OTHER1; the signed QR is SKP01's.
        runBlocking {
            database.researchStudyDao().insert(otherStudy())
        }
        session.unlock("study-other")
        viewModel = StudyServerViewModel(repository, server, session, {}, scope, Dispatchers.Default)
        awaitUntil("the study to be read") { viewModel.study.value != null }

        viewModel.onQrScanned(qr)

        assertThat(viewModel.scannedProfile.value).isNull()
        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.ENROLL_OTHER_STUDY)
        assertThat(server.enrolls).isEmpty()
        runBlocking { assertThat(database.researchStudyDao().getStudyByCode("SKP01")).isNull() }
    }

    @Test
    fun tcApi07_withNoStudyOnThePhoneTheQrIsRefused() {
        session.unlock("none")
        viewModel = StudyServerViewModel(repository, server, session, {}, scope, Dispatchers.Default)

        viewModel.onQrScanned(qr)

        assertThat(viewModel.scannedProfile.value).isNull()
        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.NO_STUDY)
    }

    @Test
    fun tcApi07_aStudyWithNoServerOrAnAlreadyEnrolledPhoneCannotJoin() {
        val profile = requireNotNull(PayloadParser.parseStudyProfile(qr))
        val open = otherStudy().copy(studyCode = profile.studyCode)

        assertThat(enrollmentRefusal(open, profile)).isNull()
        assertThat(enrollmentRefusal(open, profile.copy(host = null))).isEqualTo(ResearchMessage.ENROLL_NO_SERVER)
        assertThat(enrollmentRefusal(open.copy(serverDeviceId = "d-1"), profile)).isEqualTo(ResearchMessage.UNEXPECTED)
        assertThat(
            enrollmentRefusal(open.copy(studyCode = "OTHER1"), profile)
        ).isEqualTo(ResearchMessage.ENROLL_OTHER_STUDY)
        assertThat(enrollmentRefusal(null, profile)).isEqualTo(ResearchMessage.NO_STUDY)
    }

    @Test
    fun tcApi07_aBadlyFormedCodeIsRefusedBeforeTheServerIsAsked() {
        phoneWithTheStudy()
        scanTheStudyQr()

        val joined = enrollAndWait("not-a-code")

        assertThat(joined).isFalse()
        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.ENROLL_CODE_FORMAT)
        assertThat(server.enrolls).isEmpty()
    }

    @Test
    fun tcSync_enrollmentSendsTheScannedQrAndTheCode_thenSchedulesUploads_andClearsTheScan() {
        phoneWithTheStudy()
        scanTheStudyQr()

        val joined = enrollAndWait(" " + code.lowercase() + " ")

        assertThat(joined).isTrue()
        assertThat(server.enrolls).hasSize(1)
        assertThat(server.enrolls.single().first).isEqualTo(qr)
        assertThat(server.enrolls.single().second).isEqualTo(code) // trimmed and upper-cased
        assertThat(server.schedules.get()).isEqualTo(1)
        assertThat(viewModel.scannedProfile.value).isNull()
        assertThat(viewModel.study.value?.serverDeviceId).isEqualTo("d-1")
        assertThat(studyChanged.get()).isEqualTo(1) // the research home reads the study (site code) again
    }

    @Test
    fun tcSync_eachServerFailureShowsItsOwnMessage_keepsTheScan_andSchedulesNothing() {
        val failures = mapOf(
            ResearchMessage.ENROLL_CODE_REJECTED to StudyServerFailure(ResearchError(ResearchMessage.ENROLL_CODE_REJECTED)),
            ResearchMessage.ENROLL_SETTINGS_MISMATCH to StudyServerFailure(ResearchError(ResearchMessage.ENROLL_SETTINGS_MISMATCH)),
            ResearchMessage.SERVER_UNREACHABLE to StudyServerFailure(ResearchError(ResearchMessage.SERVER_UNREACHABLE)),
            ResearchMessage.SERVER_REFUSED to StudyServerFailure(ResearchError(ResearchMessage.SERVER_REFUSED)),
            ResearchMessage.ENROLL_NO_DEVICE_PROFILE to StudyServerFailure(ResearchError(ResearchMessage.ENROLL_NO_DEVICE_PROFILE)),
            // anything else, with a message that must never reach the screen
            ResearchMessage.UNEXPECTED to IllegalStateException("intake.example.bd refused 7K2X-M49P-3QW8")
        )
        phoneWithTheStudy()
        scanTheStudyQr()

        for ((expected, failure) in failures) {
            server.failure = failure
            viewModel.clearError()
            enrollAndWait()
            assertThat(viewModel.error.value?.message).isEqualTo(expected)
            assertThat(viewModel.scannedProfile.value).isNotNull() // the worker can type the code again
            assertThat(viewModel.isBusy.value).isFalse()
        }
        assertThat(server.schedules.get()).isEqualTo(0)
        assertThat(viewModel.study.value?.serverDeviceId).isNull()
    }

    @Test
    fun tcSync_aSecondTapWhileTheServerIsAnsweringIsIgnored() {
        phoneWithTheStudy()
        scanTheStudyQr()
        server.gate = CompletableDeferred()
        val done = AtomicBoolean(false)

        viewModel.enroll(code) { done.set(true) }
        awaitUntil("the first request to reach the server") { server.enrolls.size == 1 }
        viewModel.enroll(code) { fail("a second enrollment must not start") }
        server.gate!!.complete(Unit)
        awaitUntil("the enrollment to finish") { done.get() }

        assertThat(server.enrolls).hasSize(1)
    }

    @Test
    fun tcRsh07_anAnswerThatArrivesAfterALockChangesNothingOnScreen() {
        phoneWithTheStudy()
        scanTheStudyQr()
        server.gate = CompletableDeferred()

        viewModel.enroll(code) { fail("a locked session is not told about the result") }
        awaitUntil("the request to reach the server") { server.enrolls.size == 1 }
        session.lock()
        server.gate!!.complete(Unit)
        awaitUntil("the view model to be free again") { !viewModel.isBusy.value }

        assertThat(viewModel.scannedProfile.value).isNull() // the lock cleared the scan
        assertThat(server.schedules.get()).isEqualTo(0)
    }

    @Test
    fun tcRsh07_nothingStartsWhileTheSessionIsLocked() {
        phoneWithTheStudy()
        scanTheStudyQr()
        session.lock()

        viewModel.enroll(code) { fail("must not run") }
        viewModel.leave(pin) { fail("must not run") }
        viewModel.uploadNow()

        Thread.sleep(SETTLE_MS)
        assertThat(server.enrolls).isEmpty()
        assertThat(server.leaves.get()).isEqualTo(0)
        assertThat(server.uploadNows.get()).isEqualTo(0)
    }

    @Test
    fun tcSync16_leavingWithTheWrongPinCostsATry_andLeavesTheServer_untouched() {
        phoneWithTheStudy()
        markEnrolledOnTheServer()

        viewModel.leave("000000") { fail("a wrong PIN must not leave") }
        awaitUntil("the PIN check") { viewModel.error.value != null }

        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.WRONG_PIN)
        assertThat(server.leaves.get()).isEqualTo(0)
        runBlocking { assertThat(database.researchStudyDao().getActiveStudy()!!.failedPinAttempts).isEqualTo(1) }
    }

    @Test
    fun tcSync16_leavingWithTheRightPinEndsOnlyTheServerConnection() {
        phoneWithTheStudy()
        markEnrolledOnTheServer()
        val left = AtomicBoolean(false)

        viewModel.leave(pin) { left.set(true) }
        awaitUntil("leaving to finish") { left.get() || viewModel.error.value != null }

        assertThat(left.get()).isTrue()
        assertThat(server.leaves.get()).isEqualTo(1)
        val study = runBlocking { database.researchStudyDao().getActiveStudy() }
        assertThat(study).isNotNull() // still ACTIVE: export and retention find the study through this row
        assertThat(study!!.serverDeviceId).isNull()
        assertThat(viewModel.study.value?.serverDeviceId).isNull()
        assertThat(studyChanged.get()).isEqualTo(1)
    }

    @Test
    fun tcSync16_aServerThatCannotBeReached_leavesThePhoneEnrolledAndSaysSo() {
        phoneWithTheStudy()
        markEnrolledOnTheServer()
        server.failure = StudyServerFailure(ResearchError(ResearchMessage.SERVER_UNREACHABLE))

        viewModel.leave(pin) { fail("must not report success") }
        awaitUntil("the failure") { viewModel.error.value != null }

        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.SERVER_UNREACHABLE)
        assertThat(viewModel.study.value?.serverDeviceId).isEqualTo("d-1")
    }

    @Test
    fun tcSync16_aPhoneThatIsNotOnTheServerHasNothingToLeave() {
        phoneWithTheStudy()

        viewModel.leave(pin) { fail("must not run") }

        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.NOT_ENROLLED)
        assertThat(server.leaves.get()).isEqualTo(0)
    }

    @Test
    fun tcSync10_uploadNowAsksTheServerLayerOnlyOnceThePhoneIsSetUp() {
        phoneWithTheStudy()

        viewModel.uploadNow()
        assertThat(viewModel.error.value?.message).isEqualTo(ResearchMessage.NOT_ENROLLED)
        assertThat(server.uploadNows.get()).isEqualTo(0)

        viewModel.clearError()
        markEnrolledOnTheServer()
        viewModel.uploadNow()
        assertThat(server.uploadNows.get()).isEqualTo(1)
    }

    private fun otherStudy() = ResearchStudyEntity(
        id = "study-other",
        studyCode = "OTHER1",
        siteCode = "OTHER1",
        displayName = "Another study",
        serverUrl = null,
        deviceKeyAlias = null,
        serverDeviceId = null,
        pinHash = ByteArray(32),
        pinSalt = ByteArray(16),
        failedPinAttempts = 0,
        lockedUntil = null,
        timestampPrecision = "DAY_PLUS_OFFSETS",
        timeZone = "Asia/Dhaka",
        dataGeneration = null,
        localRetentionAfterAckDays = 14,
        exportPublicKey = ByteArray(32),
        status = "ACTIVE",
        enrolledAt = 1_000L
    )

    private fun hexToBytes(hex: String): ByteArray = ByteArray(
        hex.length / 2
    ) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun findRepoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("Not found: $relative")
    }

    private companion object {
        const val WAIT_MS = 60_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 300L
    }
}

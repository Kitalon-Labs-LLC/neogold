@file:Suppress("MaxLineLength") // JSON fixtures stay on one line so a diff shows the whole payload

package com.kitalonlabs.neogold.sync.research

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.engine.cardtrust.PayloadParser
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.crypto.ClockManager
import com.kitalonlabs.neogold.sync.research.crypto.SoftwareDeviceKeyManager
import com.kitalonlabs.neogold.sync.research.enrollment.EnrollingDevice
import com.kitalonlabs.neogold.sync.research.enrollment.EnrollmentRefusal
import com.kitalonlabs.neogold.sync.research.enrollment.StudyEnrollmentManager
import com.kitalonlabs.neogold.sync.research.enrollment.StudyEnrollmentRefused
import com.kitalonlabs.neogold.sync.research.enrollment.StudyRevocationManager
import com.kitalonlabs.neogold.sync.research.enrollment.StudySettingsMismatchException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * What a phone may be enrolled into, and what it tells the server (BUG-2026-083 items 8 and 14, TC-API-07, TC-SYNC-16).
 * The study QR below is not signed; the verifier is a test double, because these tests are about the guards, not the
 * signature (the real verifier is tested in `SyncTestCases`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyEnrollmentGuardsTest {

    private lateinit var database: NeoGoldDatabase
    private lateinit var keyManager: SoftwareDeviceKeyManager
    private lateinit var server: MockWebServer
    private lateinit var manager: StudyEnrollmentManager

    private val code = "7K2X-M49P-3ABC"
    private val alias = "neogold_device_key_SKP-2026"
    private val device = EnrollingDevice(cameraHardwareLevel = "FULL", qualification = "RESEARCH_ONLY")

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NeoGoldDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        keyManager = SoftwareDeviceKeyManager()
        server = MockWebServer().also { it.start() }
        manager = StudyEnrollmentManager(
            apiClient = ResearchApiClient(OkHttpClient.Builder().build(), ClockManager(), keyManager),
            keyManager = keyManager,
            studyProfileVerifier = { VerificationResult.Verified(requireNotNull(PayloadParser.parseStudyProfile(it))) },
            studyDao = database.researchStudyDao(),
            appVersion = "1.0.0",
            allowCleartext = true // the mock server is plain http on localhost
        )
    }

    @After
    fun teardown() {
        server.shutdown()
        database.close()
    }

    private suspend fun seedStudy(studyCode: String = "SKP-2026", enrolled: Boolean = false) {
        database.researchStudyDao().insert(
            ResearchStudyEntity(
                id = "study-1",
                studyCode = studyCode,
                siteCode = "DHK-01",
                displayName = "Sakhipur Study",
                serverUrl = if (enrolled) server.url("/v1").toString() else null,
                deviceKeyAlias = if (enrolled) "old-alias" else null,
                serverDeviceId = if (enrolled) "d-old" else null,
                pinHash = ByteArray(32) { 7 },
                pinSalt = ByteArray(16) { 9 },
                failedPinAttempts = 2,
                lockedUntil = null,
                timestampPrecision = "DAY_PLUS_OFFSETS",
                timeZone = "Asia/Dhaka",
                dataGeneration = null,
                localRetentionAfterAckDays = 14,
                exportPublicKey = ByteArray(32),
                status = "ACTIVE",
                enrolledAt = 1_000L
            )
        )
    }

    private fun qr(studyCode: String = "SKP-2026", host: String = server.url("/v1").toString()): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x02)
        out.write(0x01)
        out.write(byteArrayOf(0x7F, 0xFF.toByte()))
        out.write(0) // DAY_PLUS_OFFSETS
        for (field in listOf("K-ISSUER", studyCode, "Sakhipur Study", "BRAC", host)) {
            out.write(field.length)
            out.write(field.toByteArray())
        }
        out.write(ByteArray(32))
        out.write(ByteArray(64))
        return out.toByteArray()
    }

    private fun enrollmentBody(maxImageBytes: Long = 5_000_000, precision: String = "DAY_PLUS_OFFSETS") =
        """{"device_id":"d-new","study_code":"SKP-2026","site_code":"DHK-02","enrolled_at":"2026-10-10T08:15:30Z","data_generation":3,"upload_policy":{"wifi_only":true,"max_image_bytes":$maxImageBytes,"max_requests_per_minute":60,"timestamp_precision":"$precision","time_zone":"Asia/Dhaka"}}"""

    private suspend fun assertRefused(reason: EnrollmentRefusal, bytes: ByteArray = qr()) {
        try {
            manager.enroll(bytes, code, device)
            fail("Expected StudyEnrollmentRefused")
        } catch (e: StudyEnrollmentRefused) {
            assertThat(e.reason).isEqualTo(reason)
            assertThat(e.message).doesNotContain(code)
        }
        // refused before anything was made or sent
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(keyManager.hasDeviceKey(alias)).isFalse()
    }

    @Test
    fun noStudyOnThePhone_isRefused_beforeAnyKeyOrRequest() = runBlocking<Unit> {
        assertRefused(EnrollmentRefusal.NO_STUDY)
        assertThat(database.researchStudyDao().getActiveStudy()).isNull()
    }

    @Test
    fun aQrOfAnotherStudy_isRefused_andNothingIsStored() = runBlocking<Unit> {
        seedStudy("SKP-2026")
        assertRefused(EnrollmentRefusal.OTHER_STUDY, qr(studyCode = "OTHER-77"))
        assertThat(database.researchStudyDao().getStudyByCode("OTHER-77")).isNull()
        assertThat(database.researchStudyDao().getActiveStudy()!!.serverDeviceId).isNull()
    }

    @Test
    fun aPhoneThatIsAlreadyEnrolled_isRefused_andKeepsItsKeyAndDeviceId() = runBlocking<Unit> {
        seedStudy(enrolled = true)
        assertRefused(EnrollmentRefusal.ALREADY_ENROLLED)
        assertThat(database.researchStudyDao().getActiveStudy()!!.serverDeviceId).isEqualTo("d-old")
    }

    @Test
    fun aStudyWithNoServer_isRefused() = runBlocking<Unit> {
        seedStudy()
        assertRefused(EnrollmentRefusal.NO_SERVER, qr(host = ""))
    }

    @Test
    fun enrollment_updatesTheExistingStudyRow_andKeepsThePinAndItsCounters() = runBlocking<Unit> {
        seedStudy()
        server.enqueue(MockResponse().setResponseCode(201).setBody(enrollmentBody()))

        val response = manager.enroll(qr(), code, device)

        assertThat(response.device_id).isEqualTo("d-new")
        val rows = database.researchStudyDao()
        val study = rows.getActiveStudy()!!
        assertThat(study.id).isEqualTo("study-1") // the same row: no second study appears
        assertThat(study.serverDeviceId).isEqualTo("d-new")
        assertThat(study.deviceKeyAlias).isEqualTo(alias)
        assertThat(study.siteCode).isEqualTo("DHK-02")
        assertThat(study.dataGeneration).isEqualTo(3L)
        assertThat(study.pinHash).isEqualTo(ByteArray(32) { 7 })
        assertThat(study.pinSalt).isEqualTo(ByteArray(16) { 9 })
        assertThat(study.failedPinAttempts).isEqualTo(2)
        assertThat(keyManager.hasDeviceKey(alias)).isTrue()
    }

    @Test
    fun theServerIsToldTheDeviceFactsItWasGiven_notMadeUpOnes() = runBlocking<Unit> {
        seedStudy()
        server.enqueue(MockResponse().setResponseCode(201).setBody(enrollmentBody()))

        manager.enroll(qr(), code, device)

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"camera_hardware_level\":\"FULL\"")
        assertThat(body).contains("\"qualification\":\"RESEARCH_ONLY\"")
    }

    @Test
    fun aServerPhotoLimitBelowTheAppsOwn_isRefused_andTheKeyIsDeleted() = runBlocking<Unit> {
        seedStudy()
        server.enqueue(MockResponse().setResponseCode(201).setBody(enrollmentBody(maxImageBytes = 2_000_000)))

        try {
            manager.enroll(qr(), code, device)
            fail("Expected StudySettingsMismatchException")
        } catch (_: StudySettingsMismatchException) {
            // expected
        }

        assertThat(keyManager.hasDeviceKey(alias)).isFalse()
        assertThat(database.researchStudyDao().getActiveStudy()!!.serverDeviceId).isNull()
    }

    @Test
    fun aServerThatRefusesTheCode_leavesNoKeyAndNoDeviceId() = runBlocking<Unit> {
        seedStudy()
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"status":403,"code":"ENROLLMENT_CODE_USED","title":"Used","retryable":false}""")
        )

        try {
            manager.enroll(qr(), code, device)
            fail("Expected ApiException")
        } catch (_: com.kitalonlabs.neogold.sync.research.api.ApiException) {
            // expected
        }

        assertThat(keyManager.hasDeviceKey(alias)).isFalse()
        assertThat(database.researchStudyDao().getActiveStudy()!!.serverDeviceId).isNull()
    }

    @Test
    fun disconnecting_keepsTheStudyActive_clearsKeyAndDeviceId_andIsAudited() = runBlocking<Unit> {
        // TC-SYNC-16: nothing is lost by leaving, revoking or a closed study; export and retention still find the study.
        for (reason in listOf("LEFT", "REVOKED", "CLOSED")) {
            database.researchStudyDao().deleteAll()
            seedStudy(enrolled = true)
            keyManager.generateDeviceKey("old-alias", code)
            val revocation = StudyRevocationManager(
                apiClient = ResearchApiClient(OkHttpClient.Builder().build(), ClockManager(), keyManager),
                keyManager = keyManager,
                studyDao = database.researchStudyDao(),
                auditDao = database.auditEventDao(),
                appVersion = "1.0.0"
            )
            val study = database.researchStudyDao().getActiveStudy()!!
            when (reason) {
                "LEFT" -> {
                    server.enqueue(MockResponse().setResponseCode(204))
                    revocation.leaveStudy(study)
                }
                "REVOKED" -> revocation.handleDeviceRevoked(study)
                else -> revocation.handleStudyClosed(study)
            }

            val after = database.researchStudyDao().getActiveStudy()
            assertThat(after).isNotNull() // still ACTIVE
            assertThat(after!!.serverDeviceId).isNull()
            assertThat(after.deviceKeyAlias).isNull()
            assertThat(keyManager.hasDeviceKey("old-alias")).isFalse()
            val audited = database.auditEventDao().getEventsByType("DEVICE_DISCONNECTED")
            assertThat(audited.first().details).isEqualTo("""{"reason":"$reason"}""")
        }
    }

    @Test
    fun disconnecting_doesNotGiveBackPinGuesses_whenThePinCountersMovedAfterTheRowWasRead() = runBlocking<Unit> {
        // BUG-2026-088 review: the copy of the row read before the network call must not overwrite a later PIN check.
        seedStudy(enrolled = true)
        keyManager.generateDeviceKey("old-alias", code)
        val stale = database.researchStudyDao().getActiveStudy()!!
        database.researchStudyDao().update(stale.copy(failedPinAttempts = 4, lockedUntil = 99_000L))
        val revocation = StudyRevocationManager(
            apiClient = ResearchApiClient(OkHttpClient.Builder().build(), ClockManager(), keyManager),
            keyManager = keyManager,
            studyDao = database.researchStudyDao(),
            auditDao = database.auditEventDao(),
            appVersion = "1.0.0"
        )

        revocation.handleDeviceRevoked(stale)

        val after = database.researchStudyDao().getActiveStudy()!!
        assertThat(after.serverDeviceId).isNull()
        assertThat(after.failedPinAttempts).isEqualTo(4)
        assertThat(after.lockedUntil).isEqualTo(99_000L)
    }

    @Test
    fun aPhoneOnAClosedStudy_canStillLeave() = runBlocking<Unit> {
        seedStudy(enrolled = true)
        keyManager.generateDeviceKey("old-alias", code)
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"status":403,"code":"STUDY_CLOSED","title":"Closed","retryable":false}""")
        )
        val revocation = StudyRevocationManager(
            apiClient = ResearchApiClient(OkHttpClient.Builder().build(), ClockManager(), keyManager),
            keyManager = keyManager,
            studyDao = database.researchStudyDao(),
            auditDao = database.auditEventDao(),
            appVersion = "1.0.0"
        )

        revocation.leaveStudy(database.researchStudyDao().getActiveStudy()!!)

        assertThat(database.researchStudyDao().getActiveStudy()!!.serverDeviceId).isNull()
        assertThat(keyManager.hasDeviceKey("old-alias")).isFalse()
    }
}

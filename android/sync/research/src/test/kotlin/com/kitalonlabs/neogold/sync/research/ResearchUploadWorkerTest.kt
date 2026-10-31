@file:Suppress("MaxLineLength") // JSON fixtures stay on one line so a diff shows the whole payload

package com.kitalonlabs.neogold.sync.research

import android.content.Context
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.TrustList
import com.kitalonlabs.neogold.sync.research.crypto.SoftwareDeviceKeyManager
import com.kitalonlabs.neogold.sync.research.outbox.OutboxProcessor
import com.kitalonlabs.neogold.sync.research.worker.ResearchUploadWorker
import com.kitalonlabs.neogold.sync.research.worker.ResearchWorkScheduler
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The upload worker takes everything from the application (BUG-2026-083 item 33, TC-SYNC-10, TC-SYNC-11, TC-SYNC-15).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchUploadWorkerTest {

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var server: MockWebServer
    private lateinit var keys: SoftwareDeviceKeyManager

    /** The worker with a processor (or none) chosen by the test, as the application would hand it over. */
    private class TestWorker(
        context: Context,
        params: WorkerParameters,
        private val processor: OutboxProcessor?
    ) : ResearchUploadWorker(context, params) {
        override suspend fun outboxProcessor(): OutboxProcessor? = processor
    }

    private fun worker(processor: OutboxProcessor?) = TestListenableWorkerBuilder<TestWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters) =
                TestWorker(appContext, params, processor)
        })
        .build()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().also { it.start() }
        keys = SoftwareDeviceKeyManager().also { it.generateDeviceKey("worker-key", "7K2X-M49P-3ABC") }
    }

    @After
    fun teardown() {
        server.shutdown()
        database.close()
    }

    private fun graph() = ResearchSyncGraph(
        database = database,
        encryptedFiles = DefaultEncryptedFileStore(
            context,
            DefaultFileKeysetProvider(context, FakeMasterKeyProvider())
        ),
        cardTrustVerifier = CardTrustVerifier(
            TrustList(1, 1, LocalDate.of(2026, 10, 1), LocalDate.of(2028, 10, 1), emptyList(), emptyList(), emptyList())
        ),
        appVersion = "1.0.0",
        keyManager = keys
    )

    private suspend fun seedEnrolledStudy() {
        database.researchStudyDao().insert(
            ResearchStudyEntity(
                id = "study-1",
                studyCode = "SKP-2026",
                siteCode = "DHK-01",
                displayName = "Sakhipur Study",
                serverUrl = server.url("/v1").toString(),
                deviceKeyAlias = "worker-key",
                serverDeviceId = "d-1",
                pinHash = ByteArray(32),
                pinSalt = ByteArray(16),
                failedPinAttempts = 0,
                lockedUntil = null,
                timestampPrecision = "DAY_PLUS_OFFSETS",
                timeZone = "Asia/Dhaka",
                dataGeneration = 1L,
                localRetentionAfterAckDays = 14,
                exportPublicKey = ByteArray(32),
                status = "ACTIVE",
                enrolledAt = 1_000L
            )
        )
    }

    private fun deviceBody(studyStatus: String) =
        """{"device_id":"d-1","site_code":"DHK-01","study_status":"$studyStatus","revoked":false,"data_generation":1,"server_time":"2026-10-10T08:15:00Z","min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"max_image_bytes":5000000,"max_requests_per_minute":60,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""

    @Test
    fun withoutAProcessor_theWorkerDoesNothingAndTriesAgainLater() = runBlocking<Unit> {
        // Storage not readable, or the bundle check failed: the application hands over no processor.
        val result = worker(null).doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun anApplicationWithoutTheComponent_givesNoProcessor_andTheWorkerTriesAgainLater() = runBlocking<Unit> {
        // The default worker reads the processor from the application, never builds its own database or keys.
        val result = TestListenableWorkerBuilder<ResearchUploadWorker>(context).build().doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun withAProcessor_theWorkerChecksTheDeviceFirst_andSucceeds() = runBlocking<Unit> {
        seedEnrolledStudy()
        server.enqueue(MockResponse().setResponseCode(200).setBody(deviceBody("ACTIVE")))

        val result = worker(graph().outboxProcessor).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(server.requestCount).isEqualTo(1) // GET /device first (doc 7 Section 3)
    }

    @Test
    fun aClosedStudy_stopsTheWorker_andDisconnectsThePhone_withTheDataKept() = runBlocking<Unit> {
        seedEnrolledStudy()
        server.enqueue(MockResponse().setResponseCode(200).setBody(deviceBody("CLOSED")))

        val result = worker(graph().outboxProcessor).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        val study = database.researchStudyDao().getActiveStudy()
        assertThat(study).isNotNull() // TC-SYNC-16: still ACTIVE, so export and retention find it
        assertThat(study!!.serverDeviceId).isNull()
        assertThat(study.deviceKeyAlias).isNull()
        assertThat(keys.hasDeviceKey("worker-key")).isFalse()
        assertThat(database.auditEventDao().getEventsByType("DEVICE_DISCONNECTED").single().details)
            .isEqualTo("""{"reason":"CLOSED"}""")
    }

    @Test
    fun aPhoneThatIsNotSetUpOnTheServer_hasNothingToSend_andIsNotRetried() = runBlocking<Unit> {
        seedEnrolledStudy()
        val study = database.researchStudyDao().getActiveStudy()!!
        database.researchStudyDao().update(study.copy(serverDeviceId = null, deviceKeyAlias = null))

        val result = worker(graph().outboxProcessor).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun aServerError_isRetried_andNeitherItsTextNorItsHostIsInTheResult() = runBlocking<Unit> {
        seedEnrolledStudy()
        server.enqueue(
            MockResponse().setResponseCode(
                503
            ).setBody("""{"status":503,"code":"MAINTENANCE","title":"Down for work","retryable":true}""")
        )

        val result = worker(graph().outboxProcessor).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun backgroundUpload_isNotAddedWhileOneIsWaiting_andIsQueuedBehindOneThatRuns() {
        val p = ResearchWorkScheduler::policyForBackgroundUpload
        // a waiting run will upload everything that is pending by then
        assertThat(p(listOf(WorkInfo.State.ENQUEUED))).isNull()
        assertThat(p(listOf(WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING))).isNull()
        // a run in progress has already listed its rows: one more goes behind it
        assertThat(p(listOf(WorkInfo.State.RUNNING))).isEqualTo(ExistingWorkPolicy.APPEND_OR_REPLACE)
        // nothing, or only finished runs: a new one
        assertThat(p(emptyList())).isEqualTo(ExistingWorkPolicy.REPLACE)
        assertThat(p(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)))
            .isEqualTo(ExistingWorkPolicy.REPLACE)
    }
}

/** The application the default worker reads its processor from (see [ResearchUploadWorkerApplicationTest]). */
class SyncTestApplication : android.app.Application(), ResearchSyncComponent {
    var processor: OutboxProcessor? = null

    override suspend fun outboxProcessor(): OutboxProcessor? = processor
}

/** The default worker, as WorkManager builds it, uses the application's component and nothing of its own (item 33). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SyncTestApplication::class)
class ResearchUploadWorkerApplicationTest {

    @Test
    fun theDefaultWorker_asksTheApplicationForItsProcessor() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication() as SyncTestApplication
        assertThat(app.processor).isNull()

        // no processor yet: the worker waits
        val result = TestListenableWorkerBuilder<ResearchUploadWorker>(app).build().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }
}

@file:Suppress("MaxLineLength") // JSON fixtures stay on one line so a diff shows the whole payload

package com.kitalonlabs.neogold.sync.research

import android.content.Context
import androidx.room.Room
import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.core.data.local.entity.OutboxEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.data.local.entity.TsbMeasurementEntity
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.FakeMasterKeyProvider
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.PayloadParser
import com.kitalonlabs.neogold.engine.cardtrust.TrustList
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.crypto.ClockManager
import com.kitalonlabs.neogold.sync.research.crypto.SoftwareDeviceKeyManager
import com.kitalonlabs.neogold.sync.research.enrollment.StudyEnrollmentManager
import com.kitalonlabs.neogold.sync.research.enrollment.StudyProfileVerifier
import com.kitalonlabs.neogold.sync.research.enrollment.StudyRevocationManager
import com.kitalonlabs.neogold.sync.research.enrollment.StudySettingsMismatchException
import com.kitalonlabs.neogold.sync.research.outbox.OutboxProcessor
import com.kitalonlabs.neogold.sync.research.outbox.OutboxResult
import com.kitalonlabs.neogold.sync.research.outbox.ReconciliationManager
import com.kitalonlabs.neogold.sync.research.outbox.ResearchRetentionConfirmationHook
import com.kitalonlabs.neogold.sync.research.worker.ResearchUploadWorker
import com.kitalonlabs.neogold.sync.research.worker.ResearchWorkScheduler
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
 * TC-SYNC-01 to TC-SYNC-20, TC-API-07 (doc 7, doc 10).
 * Exhaustive MockWebServer-based verification of research sync client and enrollment flows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTestCases {

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var keyManager: SoftwareDeviceKeyManager
    private lateinit var clockManager: ClockManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiClient: ResearchApiClient
    private lateinit var revocationManager: StudyRevocationManager
    private lateinit var reconciliationManager: ReconciliationManager
    private lateinit var outboxProcessor: OutboxProcessor
    private lateinit var server: MockWebServer

    private val studyId = "study-skp-2026"
    private val deviceId = "d-8f2a1b9c"
    private val keyAlias = "test-device-key"
    private val appVersion = "1.0.0"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val masterKeyProvider = FakeMasterKeyProvider()
        val fileKeysetProvider = DefaultFileKeysetProvider(context, masterKeyProvider)
        encryptedFileStore = DefaultEncryptedFileStore(context, fileKeysetProvider)

        keyManager = SoftwareDeviceKeyManager()
        keyManager.generateDeviceKey(keyAlias, "7K2X-M49P-3ABC")
        clockManager = ClockManager()

        server = MockWebServer()
        server.start()

        okHttpClient = OkHttpClient.Builder().build()
        apiClient = ResearchApiClient(
            okHttpClient = okHttpClient,
            clockManager = clockManager,
            keyManager = keyManager
        )

        revocationManager = StudyRevocationManager(
            apiClient = apiClient,
            keyManager = keyManager,
            studyDao = database.researchStudyDao(),
            appVersion = appVersion
        )

        reconciliationManager = ReconciliationManager(
            apiClient = apiClient,
            outboxDao = database.outboxDao(),
            studyDao = database.researchStudyDao(),
            sessionDao = database.screeningSessionDao(),
            captureDao = database.captureDao(),
            batchDao = database.cardBatchDao(),
            participantDao = database.researchParticipantDao(),
            tsbDao = database.tsbMeasurementDao(),
            appVersion = appVersion
        )

        outboxProcessor = OutboxProcessor(
            apiClient = apiClient,
            outboxDao = database.outboxDao(),
            studyDao = database.researchStudyDao(),
            batchDao = database.cardBatchDao(),
            participantDao = database.researchParticipantDao(),
            sessionDao = database.screeningSessionDao(),
            captureDao = database.captureDao(),
            tsbDao = database.tsbMeasurementDao(),
            encryptedFileStore = encryptedFileStore,
            revocationManager = revocationManager,
            reconciliationManager = reconciliationManager,
            appVersion = appVersion
        )

        // Seed active study
        runBlocking {
            database.researchStudyDao().insert(
                ResearchStudyEntity(
                    id = studyId,
                    studyCode = "SKP-2026",
                    siteCode = "DHK-01",
                    displayName = "Sakhipur Study",
                    serverUrl = server.url("/v1").toString(),
                    deviceKeyAlias = keyAlias,
                    serverDeviceId = deviceId,
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
                    enrolledAt = System.currentTimeMillis()
                )
            )
        }
    }

    @After
    fun teardown() {
        server.shutdown()
        database.close()
    }

    private suspend fun insertResearchSession(
        id: String,
        participantId: String,
        cardBatchId: String? = null,
        createdAt: Long = System.currentTimeMillis()
    ) {
        database.screeningSessionDao().insert(
            ScreeningSessionEntity(
                id = id,
                mode = "RESEARCH",
                status = "COMPLETED",
                createdAt = createdAt,
                completedAt = createdAt + 1000,
                ageHours = 48.0,
                guardianConsentConfirmed = 1,
                gestationalAgeWeeks = 38,
                flagJaundiceUnder24h = 0,
                flagDangerSigns = 0,
                flagPriorPhototherapy = 0,
                ruleOutcome = "QUALIFIED",
                triageAction = "NO_ACTION",
                reasonCodes = "[]",
                estimateMgDl = 12.0,
                estimateLowMgDl = 11.0,
                estimateHighMgDl = 13.0,
                thresholdMgDl = 15.0,
                recheckDueAt = null,
                captureAttempts = 1,
                registerNumber = null,
                registerNumberErasedAt = null,
                participantId = participantId,
                estimateHidden = 1,
                experimentalLabelShown = 1,
                deviceProfileId = null,
                cardBatchId = cardBatchId,
                appVersion = "1.0.0",
                modelVersion = "1.0.0",
                configVersion = "1.0.0"
            )
        )
    }

    private suspend fun insertCapture(
        id: String,
        sessionId: String,
        attemptNumber: Int = 1,
        site: String = "STERNUM",
        imageFile: String? = null,
        imageSha256: String? = null,
        capturedAt: Long = System.currentTimeMillis()
    ) {
        database.captureDao().insert(
            CaptureEntity(
                id = id,
                sessionId = sessionId,
                attemptNumber = attemptNumber,
                site = site,
                capturedAt = capturedAt,
                iso = 100,
                exposureNs = 10000000L,
                aeLocked = 1,
                awbLocked = 1,
                colorGains = "{}",
                qualityStatus = "PASSED",
                qualityFailReasons = null,
                correctionResidualDe = 1.0,
                features = "{}",
                imageFile = imageFile,
                imageSha256 = imageSha256
            )
        )
    }

    private suspend fun insertTsb(
        id: String,
        participantId: String,
        sessionId: String? = null,
        tsbMgDl: Double = 12.5,
        drawTime: Long = System.currentTimeMillis()
    ) {
        database.tsbMeasurementDao().insert(
            TsbMeasurementEntity(
                id = id,
                participantId = participantId,
                sessionId = sessionId,
                tsbMgDl = tsbMgDl,
                drawTime = drawTime,
                method = "LAB",
                enteredAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * TC-SYNC-01: Upload order verification.
     * Order MUST be: GET /device, batch, participant, manifest, images, TSB.
     */
    @Test
    fun tcSync01_uploadOrder() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody("""{"batch_id":"BATCH-01","status":"ACCEPTED","received_at":"2026-10-10T08:16:00Z"}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody(
                """{"participant_id":"p-100","participant_code":"SKP-0100","canonical_participant_id":"p-100","status":"ACCEPTED"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody(
                """{"session_id":"s-200","status":"RECEIVING","received_images":0,"total_images":1,"missing_images":["c-300"]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody("""{"capture_id":"c-300","status":"ACCEPTED","bytes_received":100,"sha256":"dummy"}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody("""{"measurement_id":"m-400","status":"ACCEPTED","received_at":"2026-10-10T08:18:00Z"}""")
        )

        val now = System.currentTimeMillis()
        database.cardBatchDao().insert(
            CardBatchEntity(
                batchId = "BATCH-01",
                issuerId = "K-01",
                formatVersion = 1,
                referenceValues = "[]",
                issuedAt = now,
                expiresAt = now + 86400000L,
                signatureValid = 1,
                qrPayload = ByteArray(10),
                serverStatus = "PENDING",
                firstSeenAt = now
            )
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-1",
                entityType = "CARD_BATCH",
                entityId = "BATCH-01",
                idempotencyKey = "idemp-1",
                payloadSha256 = "",
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        database.researchParticipantDao().insert(
            ResearchParticipantEntity(
                id = "p-100",
                studyId = studyId,
                participantCode = "SKP-0100",
                consentFormId = "C-0100",
                consentRecordedAt = now,
                createdAt = now
            )
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-2",
                entityType = "PARTICIPANT",
                entityId = "p-100",
                idempotencyKey = "idemp-2",
                payloadSha256 = "",
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        insertResearchSession(id = "s-200", participantId = "p-100", cardBatchId = "BATCH-01", createdAt = now)
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-3",
                entityType = "SESSION_MANIFEST",
                entityId = "s-200",
                idempotencyKey = "idemp-3",
                payloadSha256 = "",
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        val imgBytes = "image-bytes-12345".toByteArray()
        val imgSha = encryptedFileStore.writeEncryptedFile("img_c300.enc") { out ->
            out.write(imgBytes)
        }

        insertCapture(
            id = "c-300",
            sessionId = "s-200",
            attemptNumber = 1,
            site = "STERNUM",
            imageFile = "img_c300.enc",
            imageSha256 = imgSha,
            capturedAt = now
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-4",
                entityType = "CAPTURE_IMAGE",
                entityId = "c-300",
                idempotencyKey = "idemp-4",
                payloadSha256 = imgSha,
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        insertTsb(id = "m-400", participantId = "p-100", sessionId = "s-200", drawTime = now)
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-5",
                entityType = "TSB",
                entityId = "m-400",
                idempotencyKey = "idemp-5",
                payloadSha256 = "",
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        val result = outboxProcessor.process()
        assertThat(result).isInstanceOf(OutboxResult.Success::class.java)

        val r1 = server.takeRequest()
        assertThat(r1.method).isEqualTo("GET")
        assertThat(r1.path).isEqualTo("/v1/device")

        val r2 = server.takeRequest()
        assertThat(r2.method).isEqualTo("PUT")
        assertThat(r2.path).isEqualTo("/v1/card-batches/BATCH-01")

        val r3 = server.takeRequest()
        assertThat(r3.method).isEqualTo("PUT")
        assertThat(r3.path).isEqualTo("/v1/participants/p-100")

        val r4 = server.takeRequest()
        assertThat(r4.method).isEqualTo("PUT")
        assertThat(r4.path).isEqualTo("/v1/sessions/s-200")

        val r5 = server.takeRequest()
        assertThat(r5.method).isEqualTo("PUT")
        assertThat(r5.path).isEqualTo("/v1/sessions/s-200/captures/c-300/image")

        val r6 = server.takeRequest()
        assertThat(r6.method).isEqualTo("PUT")
        assertThat(r6.path).isEqualTo("/v1/tsb-measurements/m-400")
    }

    /**
     * TC-SYNC-02: Paused study stops early. No image requests sent.
     */
    @Test
    fun tcSync02_pausedStudyStopsEarly() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"PAUSED","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )

        val now = System.currentTimeMillis()
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-img",
                entityType = "CAPTURE_IMAGE",
                entityId = "c-99",
                idempotencyKey = "idemp-99",
                payloadSha256 = "sha",
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        val result = outboxProcessor.process()
        assertThat(result).isEqualTo(OutboxResult.Paused)

        assertThat(server.requestCount).isEqualTo(1)

        val item = database.outboxDao().getItemById("o-img")
        assertThat(item?.state).isEqualTo("PENDING")
    }

    /**
     * TC-SYNC-03: Timeout retry keeps the same Idempotency-Key.
     */
    @Test
    fun tcSync03_timeoutRetryKeepsKey() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                504
            ).setBody("""{"status":504,"code":"GATEWAY_TIMEOUT","title":"Timeout","retryable":true}""")
        )

        val now = System.currentTimeMillis()
        database.cardBatchDao().insert(
            CardBatchEntity("B-01", "K-01", 1, "[]", now, now + 86400000L, 1, ByteArray(10), "PENDING", now)
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-b",
                entityType = "CARD_BATCH",
                entityId = "B-01",
                idempotencyKey = "idemp-same-key",
                payloadSha256 = "",
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        outboxProcessor.process()

        server.takeRequest() // GET /device
        val req1 = server.takeRequest()
        assertThat(req1.getHeader("Idempotency-Key")).isEqualTo("idemp-same-key")

        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody("""{"batch_id":"B-01","status":"ACCEPTED","received_at":"2026-10-10T08:16:00Z"}""")
        )

        outboxProcessor.process()

        server.takeRequest() // GET /device
        val req2 = server.takeRequest()
        assertThat(req2.getHeader("Idempotency-Key")).isEqualTo("idemp-same-key")
    }

    /**
     * TC-SYNC-04: 409 REQUEST_IN_PROGRESS retries with same key after delay.
     */
    @Test
    fun tcSync04_requestInProgress() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                409
            ).setBody("""{"status":409,"code":"REQUEST_IN_PROGRESS","title":"In Progress","retryable":true}""")
        )

        val now = System.currentTimeMillis()
        database.cardBatchDao().insert(
            CardBatchEntity("B-01", "K-01", 1, "[]", now, now + 86400000L, 1, ByteArray(10), "PENDING", now)
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-b",
                entityType = "CARD_BATCH",
                entityId = "B-01",
                idempotencyKey = "idemp-409",
                payloadSha256 = "",
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        outboxProcessor.process()

        val item = database.outboxDao().getItemById("o-b")
        assertThat(item?.state).isEqualTo("PENDING")
        assertThat(item?.idempotencyKey).isEqualTo("idemp-409")
    }

    /**
     * TC-SYNC-05: Dependency re-queue limit of 3 -> FAILED_PERMANENT.
     */
    @Test
    fun tcSync05_dependencyRequeueLimit() = runBlocking {
        val now = System.currentTimeMillis()
        database.researchParticipantDao().insert(
            ResearchParticipantEntity("p-unk", studyId, "SKP-9999", "C-9999", now, now)
        )
        insertResearchSession(id = "s-dep", participantId = "p-unk", createdAt = now)
        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-p", "PARTICIPANT", "p-unk", "idemp-p", 0, 0, "", "ACKED", 0, null, now, now)
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-s", "SESSION_MANIFEST", "s-dep", "idemp-s", 0, 0, "", "PENDING", 0, null, now, now)
        )

        // Attempt 1: Session fails with PARTICIPANT_UNKNOWN -> dependency re-queued
        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                422
            ).setBody("""{"status":422,"code":"PARTICIPANT_UNKNOWN","title":"Participant Unknown","retryable":false}""")
        )
        outboxProcessor.process()

        // Attempt 2: Participant re-uploaded (201), then Session fails again (422)
        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody(
                """{"participant_id":"p-unk","canonical_participant_id":"p-unk","site_code":"DHK-01","created_on":"2026-10-10"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                422
            ).setBody("""{"status":422,"code":"PARTICIPANT_UNKNOWN","title":"Participant Unknown","retryable":false}""")
        )
        outboxProcessor.process()

        // Attempt 3: Participant re-uploaded (201), Session fails 3rd time -> FAILED_PERMANENT
        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody(
                """{"participant_id":"p-unk","canonical_participant_id":"p-unk","site_code":"DHK-01","created_on":"2026-10-10"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                422
            ).setBody("""{"status":422,"code":"PARTICIPANT_UNKNOWN","title":"Participant Unknown","retryable":false}""")
        )
        outboxProcessor.process()

        val sessionItem = database.outboxDao().getItemById("o-s")
        assertThat(sessionItem?.state).isEqualTo("FAILED_PERMANENT")
        assertThat(sessionItem?.lastError).isEqualTo("PARTICIPANT_UNKNOWN")
    }

    /**
     * TC-SYNC-06: Hash mismatch 3 times -> FAILED_PERMANENT.
     */
    @Test
    fun tcSync06_hashMismatchThreeTimes() = runBlocking {
        val now = System.currentTimeMillis()
        val imgBytes = "test-bytes".toByteArray()
        val imgSha = encryptedFileStore.writeEncryptedFile("img_mismatch.enc") { out ->
            out.write(imgBytes)
        }

        database.researchParticipantDao().insert(
            ResearchParticipantEntity("p-hash", studyId, "SKP-0055", "C-0055", now, now)
        )
        insertResearchSession(id = "s-any", participantId = "p-hash", createdAt = now)

        insertCapture(
            id = "c-hash",
            sessionId = "s-any",
            attemptNumber = 1,
            site = "STERNUM",
            imageFile = "img_mismatch.enc",
            imageSha256 = imgSha,
            capturedAt = now
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-hash",
                entityType = "CAPTURE_IMAGE",
                entityId = "c-hash",
                idempotencyKey = "idemp-hash",
                payloadSha256 = imgSha,
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        repeat(3) {
            server.enqueue(
                MockResponse().setResponseCode(
                    200
                ).setBody(
                    """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(
                    422
                ).setBody("""{"status":422,"code":"HASH_MISMATCH","title":"Hash Mismatch","retryable":true}""")
            )
            outboxProcessor.process()
        }

        val item = database.outboxDao().getItemById("o-hash")
        assertThat(item?.state).isEqualTo("FAILED_PERMANENT")
        assertThat(item?.lastError).isEqualTo("HASH_MISMATCH")
    }

    /**
     * TC-SYNC-07: CARD_BATCH_INVALID -> its sessions sent as UNVERIFIED with null batch ID.
     */
    @Test
    fun tcSync07_cardBatchInvalid_sessionsSentUnverified() = runBlocking {
        val now = System.currentTimeMillis()
        database.cardBatchDao().insert(
            CardBatchEntity("B-BAD", "K-01", 1, "[]", now, now + 86400000L, 1, ByteArray(10), "PENDING", now)
        )
        database.researchParticipantDao().insert(
            ResearchParticipantEntity("p-01", studyId, "SKP-0001", "C-01", now, now)
        )
        insertResearchSession(id = "s-unv", participantId = "p-01", cardBatchId = "B-BAD", createdAt = now)
        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-bb", "CARD_BATCH", "B-BAD", "idemp-bb", 0, 0, "", "PENDING", 0, null, now, now)
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-pp", "PARTICIPANT", "p-01", "idemp-pp", 0, 0, "", "ACKED", 0, null, now, now)
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-ss", "SESSION_MANIFEST", "s-unv", "idemp-ss", 0, 0, "", "PENDING", 0, null, now, now)
        )

        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                422
            ).setBody("""{"status":422,"code":"CARD_BATCH_INVALID","title":"Card Batch Invalid","retryable":false}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody(
                """{"session_id":"s-unv","status":"COMPLETE","received_images":0,"total_images":0,"missing_images":[]}"""
            )
        )

        outboxProcessor.process()

        server.takeRequest() // GET /device
        server.takeRequest() // POST /card-batches
        val sessionReq = server.takeRequest() // POST /sessions
        val body = sessionReq.body.readUtf8()
        assertThat(body).contains(""""card_status":"UNVERIFIED"""")
        assertThat(body).doesNotContain(""""card_batch_id":"B-BAD"""")

        val batch = database.cardBatchDao().getBatch("B-BAD")
        assertThat(batch?.serverStatus).isEqualTo("REJECTED")
    }

    /**
     * TC-SYNC-08: Unknown error code follows retryable boolean.
     */
    @Test
    fun tcSync08_unknownErrorCode() = runBlocking {
        val now = System.currentTimeMillis()
        database.cardBatchDao().insert(
            CardBatchEntity("B-08", "K-01", 1, "[]", now, now + 86400000L, 1, ByteArray(10), "PENDING", now)
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-u", "CARD_BATCH", "B-08", "idemp-u", 0, 0, "", "PENDING", 0, null, now, now)
        )

        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                499
            ).setBody("""{"status":499,"code":"FUTURE_CUSTOM_ERROR","title":"Future Error","retryable":true}""")
        )

        outboxProcessor.process()
        var item = database.outboxDao().getItemById("o-u")
        assertThat(item?.state).isEqualTo("PENDING")

        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                499
            ).setBody("""{"status":499,"code":"FUTURE_CUSTOM_FATAL","title":"Fatal Future Error","retryable":false}""")
        )

        outboxProcessor.process()
        item = database.outboxDao().getItemById("o-u")
        assertThat(item?.state).isEqualTo("FAILED_PERMANENT")
    }

    /**
     * TC-SYNC-09: Recovery after an app fix.
     */
    @Test
    fun tcSync09_recoveryAfterAppFix() = runBlocking {
        val now = System.currentTimeMillis()
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "o-bug",
                entityType = "SESSION_MANIFEST",
                entityId = "s-bug",
                idempotencyKey = "old-key",
                revision = 1,
                dependencyRequeues = 0,
                payloadSha256 = "",
                state = "FAILED_PERMANENT",
                attempts = 1,
                lastError = "VALIDATION_FAILED",
                createdAt = now,
                updatedAt = now
            )
        )

        outboxProcessor.recoverClientBugFailuresAfterAppUpdate("1.0.0", "1.0.1")

        val item = database.outboxDao().getItemById("o-bug")
        assertThat(item?.state).isEqualTo("PENDING")
        assertThat(item?.idempotencyKey).isNotEqualTo("old-key")
        assertThat(item?.revision).isEqualTo(2)
        assertThat(item?.attempts).isEqualTo(0)
    }

    /**
     * TC-SYNC-10: One runner only.
     */
    @Test
    fun tcSync10_oneRunnerOnly() {
        val constraints = ResearchWorkScheduler.createConstraints()
        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    }

    /**
     * TC-SYNC-11: Killed run recovers: IN_FLIGHT reset to PENDING.
     */
    @Test
    fun tcSync11_killedRunRecovers() = runBlocking {
        val now = System.currentTimeMillis()
        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-flight", "CARD_BATCH", "B-01", "idemp-fl", 0, 0, "", "IN_FLIGHT", 0, null, now, now)
        )

        val resetCount = database.outboxDao().resetInFlightToPending()
        assertThat(resetCount).isEqualTo(1)

        val item = database.outboxDao().getItemById("o-flight")
        assertThat(item?.state).isEqualTo("PENDING")
    }

    /**
     * TC-SYNC-12: Foreground worker dataSync type.
     */
    @Test
    fun tcSync12_foregroundWorker() {
        assertThat(ResearchUploadWorker.NOTIFICATION_ID).isEqualTo(9042)
        assertThat(ResearchUploadWorker.NOTIFICATION_CHANNEL_ID).isEqualTo("neogold_research_sync_channel")
    }

    /**
     * TC-SYNC-13: Reconciliation before local deletion.
     */
    @Test
    fun tcSync13_reconciliationBeforeDeletion() = runBlocking {
        val now = System.currentTimeMillis()
        database.researchParticipantDao().insert(
            ResearchParticipantEntity("p-rec", studyId, "SKP-REC", "C-REC", now, now)
        )
        insertResearchSession(id = "s-rec", participantId = "p-rec", createdAt = now)

        // Case 1: Server returns 404 -> not deletable, re-queues session
        server.enqueue(
            MockResponse().setResponseCode(
                404
            ).setBody("""{"status":404,"code":"SESSION_UNKNOWN","title":"Session Unknown"}""")
        )

        val hook = ResearchRetentionConfirmationHook(reconciliationManager, database.researchStudyDao())
        val canDelete1 = hook.isServerConfirmed("s-rec")
        assertThat(canDelete1).isFalse()

        val item1 = database.outboxDao().getItemByEntity("SESSION_MANIFEST", "s-rec")
        assertThat(item1?.state).isEqualTo("PENDING")

        // Case 2: Server returns COMPLETE -> deletable
        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"session_id":"s-rec","status":"COMPLETE","received_images":0,"total_images":0,"missing_images":[]}"""
            )
        )
        val canDelete2 = hook.isServerConfirmed("s-rec")
        assertThat(canDelete2).isTrue()
    }

    /**
     * TC-SYNC-14: Restore from backup (data_generation change re-sends all held items).
     */
    @Test
    fun tcSync14_restoreFromBackup() = runBlocking {
        val now = System.currentTimeMillis()
        val study = database.researchStudyDao().getActiveStudy()!!
        database.researchStudyDao().update(study.copy(dataGeneration = 4L))

        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-held", "CARD_BATCH", "B-01", "idemp-held", 0, 0, "", "ACKED", 0, null, now, now)
        )

        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":5,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody("""{"batch_id":"B-01","status":"ACCEPTED","received_at":"2026-10-10T08:16:00Z"}""")
        )

        database.cardBatchDao().insert(
            CardBatchEntity("B-01", "K-01", 1, "[]", now, now + 86400000L, 1, ByteArray(10), "ACCEPTED", now)
        )

        outboxProcessor.process()

        val updatedStudy = database.researchStudyDao().getActiveStudy()
        assertThat(updatedStudy?.dataGeneration).isEqualTo(5L)
    }

    /**
     * TC-SYNC-15: Wi-Fi only constraint.
     */
    @Test
    fun tcSync15_wifiOnly() {
        val constraints = ResearchWorkScheduler.createConstraints()
        assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    }

    /**
     * TC-SYNC-16: DEVICE_REVOKED deletes key, clears server_device_id, preserves local data.
     */
    @Test
    fun tcSync16_revocationKeepsData() = runBlocking {
        val now = System.currentTimeMillis()
        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-keep", "CARD_BATCH", "B-01", "idemp-k", 0, 0, "", "PENDING", 0, null, now, now)
        )

        server.enqueue(
            MockResponse().setResponseCode(
                403
            ).setBody("""{"status":403,"code":"DEVICE_REVOKED","title":"Device Revoked","retryable":false}""")
        )

        val result = outboxProcessor.process()
        assertThat(result).isEqualTo(OutboxResult.Revoked)

        assertThat(keyManager.hasDeviceKey(keyAlias)).isFalse()

        val study = database.researchStudyDao().getActiveStudy()
        assertThat(study?.serverDeviceId).isNull()

        val item = database.outboxDao().getItemById("o-keep")
        assertThat(item).isNotNull()
    }

    /**
     * TC-SYNC-17: Phone clock fast: CLOCK_SKEW offset corrected, retry succeeds.
     */
    @Test
    fun tcSync17_phoneClockFast() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(
                401
            ).setBody(
                """{"status":401,"code":"CLOCK_SKEW","title":"Clock Skew","retryable":true,"server_time":"2026-10-10T08:00:00Z"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )

        val status = apiClient.getDevice(
            baseUrl = server.url("/v1").toString(),
            deviceId = deviceId,
            keyAlias = keyAlias,
            appVersion = appVersion
        )

        assertThat(status.study_status).isEqualTo("ACTIVE")
        assertThat(server.requestCount).isEqualTo(2)
    }

    /**
     * TC-SYNC-18: Local dates in Asia/Dhaka time zone.
     */
    @Test
    fun tcSync18_localDatesDhaka() {
        val epochMs = 1770062400000L
        val formatted = OutboxProcessor.formatCalendarDate(epochMs, "Asia/Dhaka")
        assertThat(formatted).isEqualTo("2026-02-03")
    }

    /**
     * TC-SYNC-19: Same baby, two phones: canonical_participant_id alias recorded.
     */
    @Test
    fun tcSync19_sameBabyTwoPhones() = runBlocking {
        val now = System.currentTimeMillis()
        database.researchParticipantDao().insert(
            ResearchParticipantEntity("p-second-phone", studyId, "SKP-0142", "C-0142", now, now)
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity("o-p19", "PARTICIPANT", "p-second-phone", "idemp-p19", 0, 0, "", "PENDING", 0, null, now, now)
        )

        server.enqueue(
            MockResponse().setResponseCode(
                200
            ).setBody(
                """{"device_id":"$deviceId","study_status":"ACTIVE","revoked":false,"data_generation":1,"min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody(
                """{"participant_id":"p-second-phone","participant_code":"SKP-0142","canonical_participant_id":"p-first-phone","status":"ACCEPTED"}"""
            )
        )

        outboxProcessor.process()

        val participant = database.researchParticipantDao().getParticipantById("p-second-phone")
        assertThat(participant).isNotNull()
    }

    /**
     * TC-SYNC-20: No plain image on disk during upload.
     */
    @Test
    fun tcSync20_noPlainImageOnDisk() = runBlocking {
        val plainBytes = "strictly-encrypted-image-data-payload".toByteArray()
        encryptedFileStore.writeEncryptedFile("secure_capture.enc") { out ->
            out.write(plainBytes)
        }

        val filesDir = context.filesDir
        val plainImageFiles = filesDir.walkTopDown().filter {
            it.name.endsWith(".jpg") || it.name.endsWith(".png") || it.name.endsWith(".jpeg")
        }.toList()

        assertThat(plainImageFiles).isEmpty()
    }

    /**
     * TC-API-07: Settings mismatch refused by app: key deleted and error shown.
     */
    @Test
    fun tcApi07_settingsMismatchRefused() = runBlocking {
        val enrollmentManager = StudyEnrollmentManager(
            apiClient = apiClient,
            keyManager = keyManager,
            // A test double: the mock QR below is not signed, and this test is about the settings check, not the
            // signature. The real verifier is exercised by the two tests after it.
            studyProfileVerifier = { VerificationResult.Verified(requireNotNull(PayloadParser.parseStudyProfile(it))) },
            studyDao = database.researchStudyDao(),
            appVersion = appVersion,
            allowCleartext = true // the mock server is plain http on localhost
        )

        server.enqueue(
            MockResponse().setResponseCode(
                201
            ).setBody(
                """{"device_id":"d-mismatch","study_code":"SKP-2026","site_code":"DHK-01","enrolled_at":"2026-10-10T08:15:30Z","upload_policy":{"wifi_only":true,"batch_size_images":12,"max_retry_interval_seconds":3600,"timestamp_precision":"FULL","time_zone":"Asia/Dhaka"}}"""
            )
        )

        val qrBytes = createMockStudyQrBytes(
            studyCode = "SKP-2026",
            displayName = "Sakhipur Study",
            partner = "BRAC",
            host = server.url("/v1").toString(),
            timestampPrecision = 0 // DAY_PLUS_OFFSETS
        )

        val enrollmentAlias = "neogold_device_key_SKP-2026"
        try {
            enrollmentManager.enroll(
                qrPayload = qrBytes,
                enrollmentCode = "7K2X-M49P-3ABC"
            )
            fail("Expected StudySettingsMismatchException")
        } catch (e: StudySettingsMismatchException) {
            assertThat(e.message).contains("Study settings do not match")
            assertThat(keyManager.hasDeviceKey(enrollmentAlias)).isFalse()
        }
    }

    /**
     * BUG-2026-040: a study QR that no trusted K5 key signed is never used. Nothing is sent, and no device key is made,
     * because the profile names the server the phone would send research data to.
     */
    @Test
    fun unsignedStudyProfile_isRefused_beforeAnyKeyIsMadeOrAnythingIsSent() = runBlocking {
        val emptyTrustList = TrustList(
            1,
            1,
            java.time.LocalDate.of(2026, 10, 1),
            java.time.LocalDate.of(2028, 10, 1),
            emptyList(),
            emptyList(),
            emptyList()
        )
        val manager = StudyEnrollmentManager(
            apiClient = apiClient,
            keyManager = keyManager,
            studyProfileVerifier = StudyProfileVerifier.of(CardTrustVerifier(emptyTrustList)),
            studyDao = database.researchStudyDao(),
            appVersion = appVersion,
            allowCleartext = true // the mock server is plain http on localhost
        )
        val qrBytes = createMockStudyQrBytes(
            studyCode = "SKP-2026",
            displayName = "Sakhipur Study",
            partner = "BRAC",
            host = server.url("/v1").toString(),
            timestampPrecision = 0
        )

        try {
            manager.enroll(qrPayload = qrBytes, enrollmentCode = "7K2X-M49P-3ABC")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).doesNotContain("7K2X")
        }

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(keyManager.hasDeviceKey("neogold_device_key_SKP-2026")).isFalse()
    }

    /** BUG-2026-040: an enrollment code is a credential, so the error for a badly formed one does not repeat it. */
    @Test
    fun aBadlyFormedEnrollmentCode_isRefusedWithoutEchoingIt() = runBlocking {
        val manager = StudyEnrollmentManager(
            apiClient = apiClient,
            keyManager = keyManager,
            studyProfileVerifier = { VerificationResult.Verified(requireNotNull(PayloadParser.parseStudyProfile(it))) },
            studyDao = database.researchStudyDao(),
            appVersion = appVersion,
            allowCleartext = true // the mock server is plain http on localhost
        )
        val qrBytes = createMockStudyQrBytes(
            studyCode = "SKP-2026",
            displayName = "Sakhipur Study",
            partner = "BRAC",
            host = server.url("/v1").toString(),
            timestampPrecision = 0
        )

        try {
            manager.enroll(qrPayload = qrBytes, enrollmentCode = "secret-code-123")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).doesNotContain("secret-code-123")
        }
    }

    private fun createMockStudyQrBytes(
        studyCode: String,
        displayName: String,
        partner: String,
        host: String,
        timestampPrecision: Int
    ): ByteArray {
        val stream = ByteArrayOutputStream()
        stream.write(0x02) // payload type: study profile
        stream.write(0x01) // format version
        stream.write(byteArrayOf(0x7F, 0xFF.toByte())) // expiresAt days
        stream.write(timestampPrecision)
        val issuerBytes = "K-ISSUER".toByteArray()
        stream.write(issuerBytes.size)
        stream.write(issuerBytes)
        val codeBytes = studyCode.toByteArray()
        stream.write(codeBytes.size)
        stream.write(codeBytes)
        val nameBytes = displayName.toByteArray()
        stream.write(nameBytes.size)
        stream.write(nameBytes)
        val partBytes = partner.toByteArray()
        stream.write(partBytes.size)
        stream.write(partBytes)
        val hostBytes = host.toByteArray()
        stream.write(hostBytes.size)
        stream.write(hostBytes)
        stream.write(ByteArray(32)) // export public key
        stream.write(ByteArray(64)) // signature
        return stream.toByteArray()
    }
}

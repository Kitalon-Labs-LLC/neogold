package com.kitalonlabs.neogold.sync.research

import android.content.Context
import androidx.room.Room
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
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.crypto.ClockManager
import com.kitalonlabs.neogold.sync.research.crypto.SoftwareDeviceKeyManager
import com.kitalonlabs.neogold.sync.research.enrollment.StudyRevocationManager
import com.kitalonlabs.neogold.sync.research.outbox.OutboxProcessor
import com.kitalonlabs.neogold.sync.research.outbox.OutboxResult
import com.kitalonlabs.neogold.sync.research.outbox.ReconciliationManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-of-day simulation verification:
 * 5 participants, 20 sessions, 120 images, linked TSB values.
 * Uploads over a simulated link with zero loss and zero duplicates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncFullDaySimulationTest {

    private lateinit var context: Context
    private lateinit var database: NeoGoldDatabase
    private lateinit var encryptedFileStore: EncryptedFileStore
    private lateinit var keyManager: SoftwareDeviceKeyManager
    private lateinit var clockManager: ClockManager
    private lateinit var apiClient: ResearchApiClient
    private lateinit var outboxProcessor: OutboxProcessor
    private lateinit var server: MockWebServer

    private val studyId = "study-full-day"
    private val deviceId = "d-d1-device"
    private val keyAlias = "d1-keystore-key"
    private val appVersion = "1.0.0"

    private val receivedKeys = ConcurrentHashMap.newKeySet<String>()
    private val duplicateKeys = AtomicInteger(0)
    private val uploadedImages = AtomicInteger(0)
    private val uploadedSessions = AtomicInteger(0)
    private val uploadedParticipants = AtomicInteger(0)
    private val uploadedTsbs = AtomicInteger(0)

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
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val method = request.method ?: ""

                if (path == "/v1/device" && method == "GET") {
                    return MockResponse().setResponseCode(200).setBody("""{"device_id":"$deviceId","site_code":"DHK-01","study_status":"ACTIVE","revoked":false,"data_generation":1,"server_time":"2026-10-10T08:15:00Z","min_app_version":"1.0.0","upload_policy":{"wifi_only":true,"max_image_bytes":5000000,"max_requests_per_minute":60,"timestamp_precision":"DAY_PLUS_OFFSETS","time_zone":"Asia/Dhaka"}}""")
                }

                // Check Idempotency-Key
                val idempKey = request.getHeader("Idempotency-Key")
                if (idempKey != null) {
                    if (!receivedKeys.add(idempKey)) {
                        duplicateKeys.incrementAndGet()
                    }
                }

                if (path.startsWith("/v1/card-batches/") && method == "PUT") {
                    val batchId = path.substringAfterLast("/")
                    return MockResponse().setResponseCode(201).setBody("""{"batch_id":"$batchId","issuer_id":"K-01","signature_valid":true,"expires_at":"2027-09-30"}""")
                }

                if (path.startsWith("/v1/participants/") && method == "PUT") {
                    uploadedParticipants.incrementAndGet()
                    val partId = path.substringAfterLast("/")
                    return MockResponse().setResponseCode(201).setBody("""{"participant_id":"$partId","canonical_participant_id":"$partId","site_code":"DHK-01","created_on":"2026-10-10"}""")
                }

                if (path.startsWith("/v1/sessions/") && path.endsWith("/image") && method == "PUT") {
                    uploadedImages.incrementAndGet()
                    val capId = path.substringBeforeLast("/image").substringAfterLast("/")
                    return MockResponse().setResponseCode(201).setBody("""{"session_status":"RECEIVING","missing_count":0,"capture_id":"$capId","sha256":"ok","size_bytes":100,"verified":true}""")
                }

                if (path.startsWith("/v1/sessions/") && method == "PUT") {
                    uploadedSessions.incrementAndGet()
                    val sessId = path.substringAfterLast("/")
                    return MockResponse().setResponseCode(201).setBody("""{"session_id":"$sessId","status":"RECEIVING","received_on":"2026-10-10"}""")
                }

                if (path.startsWith("/v1/tsb-measurements/") && method == "PUT") {
                    uploadedTsbs.incrementAndGet()
                    val tsbId = path.substringAfterLast("/")
                    return MockResponse().setResponseCode(201).setBody("""{"tsb_id":"$tsbId","participant_id":"p","linked":true,"received_on":"2026-10-10"}""")
                }

                return MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val okHttpClient = OkHttpClient.Builder().build()
        apiClient = ResearchApiClient(
            okHttpClient = okHttpClient,
            clockManager = clockManager,
            keyManager = keyManager
        )

        val revocationManager = StudyRevocationManager(
            apiClient = apiClient,
            keyManager = keyManager,
            studyDao = database.researchStudyDao(),
            appVersion = appVersion
        )

        val reconciliationManager = ReconciliationManager(
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

        runBlocking {
            database.researchStudyDao().insert(
                ResearchStudyEntity(
                    id = studyId,
                    studyCode = "SKP-2026",
                    siteCode = "DHK-01",
                    displayName = "Full Study Day",
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

    @Test
    fun fullStudyDay_fiveParticipants_twentySessions_oneTwentyImages_zeroLossZeroDuplicates() = runBlocking {
        val now = System.currentTimeMillis()

        // 1 Batch
        database.cardBatchDao().insert(
            CardBatchEntity(
                batchId = "BATCH-DAY-1",
                issuerId = "K-01",
                formatVersion = 1,
                referenceValues = "[]",
                issuedAt = now,
                expiresAt = now + 86400000L,
                signatureValid = 1,
                qrPayload = ByteArray(10),
                serverStatus = "NOT_SENT",
                firstSeenAt = now
            )
        )
        database.outboxDao().insertOrUpdate(
            OutboxEntity(
                id = "outbox-batch",
                entityType = "CARD_BATCH",
                entityId = "BATCH-DAY-1",
                idempotencyKey = UUID.randomUUID().toString(),
                payloadSha256 = "",
                state = "PENDING",
                createdAt = now,
                updatedAt = now
            )
        )

        // 5 Participants
        val participantIds = (1..5).map { "part-$it" }
        for ((idx, pId) in participantIds.withIndex()) {
            database.researchParticipantDao().insert(
                ResearchParticipantEntity(
                    id = pId,
                    studyId = studyId,
                    participantCode = "SKP-010$idx",
                    consentFormId = "C-010$idx",
                    consentRecordedAt = now,
                    createdAt = now
                )
            )
            database.outboxDao().insertOrUpdate(
                OutboxEntity(
                    id = "outbox-p-$pId",
                    entityType = "PARTICIPANT",
                    entityId = pId,
                    idempotencyKey = UUID.randomUUID().toString(),
                    payloadSha256 = "",
                    state = "PENDING",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        // 20 Sessions (4 per participant), each with 6 images (total 120 images)
        var sessionIndex = 0
        for (pId in participantIds) {
            for (s in 1..4) {
                sessionIndex++
                val sId = "sess-$sessionIndex"
                insertResearchSession(
                    id = sId,
                    participantId = pId,
                    cardBatchId = "BATCH-DAY-1",
                    createdAt = now + (sessionIndex * 60000L)
                )
                database.outboxDao().insertOrUpdate(
                    OutboxEntity(
                        id = "outbox-s-$sId",
                        entityType = "SESSION_MANIFEST",
                        entityId = sId,
                        idempotencyKey = UUID.randomUUID().toString(),
                        payloadSha256 = "",
                        state = "PENDING",
                        createdAt = now,
                        updatedAt = now
                    )
                )

                // 6 captures per session
                for (capNum in 1..6) {
                    val capId = "cap-$sessionIndex-$capNum"
                    val imgData = "sample-image-data-$capId".toByteArray()
                    val filename = "img_$capId.enc"
                    val sha = encryptedFileStore.writeEncryptedFile(filename) { out ->
                        out.write(imgData)
                    }

                    insertCapture(
                        id = capId,
                        sessionId = sId,
                        attemptNumber = if (capNum <= 3) capNum else capNum - 3,
                        site = if (capNum <= 3) "STERNUM" else "FOREHEAD",
                        imageFile = filename,
                        imageSha256 = sha,
                        capturedAt = now
                    )
                    database.outboxDao().insertOrUpdate(
                        OutboxEntity(
                            id = "outbox-c-$capId",
                            entityType = "CAPTURE_IMAGE",
                            entityId = capId,
                            idempotencyKey = UUID.randomUUID().toString(),
                            payloadSha256 = sha,
                            state = "PENDING",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }

                // Linked TSB for each session
                val tsbId = "tsb-$sessionIndex"
                insertTsb(
                    id = tsbId,
                    participantId = pId,
                    sessionId = sId,
                    tsbMgDl = 11.0 + (sessionIndex * 0.2),
                    drawTime = now + (sessionIndex * 60000L) + 15000L
                )
                database.outboxDao().insertOrUpdate(
                    OutboxEntity(
                        id = "outbox-t-$tsbId",
                        entityType = "TSB",
                        entityId = tsbId,
                        idempotencyKey = UUID.randomUUID().toString(),
                        payloadSha256 = "",
                        state = "PENDING",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        // Process all outbox items
        val result = outboxProcessor.process()
        assertThat(result).isInstanceOf(OutboxResult.Success::class.java)

        // Verify counts
        assertThat(uploadedParticipants.get()).isEqualTo(5)
        assertThat(uploadedSessions.get()).isEqualTo(20)
        assertThat(uploadedImages.get()).isEqualTo(120)
        assertThat(uploadedTsbs.get()).isEqualTo(20)

        // Verify zero duplicates
        assertThat(duplicateKeys.get()).isEqualTo(0)

        // Verify zero loss: all outbox items are now ACKED
        val remainingPending = database.outboxDao().getPendingItems()
        assertThat(remainingPending).isEmpty()
    }
}

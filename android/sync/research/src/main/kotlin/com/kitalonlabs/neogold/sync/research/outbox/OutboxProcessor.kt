package com.kitalonlabs.neogold.sync.research.outbox

import com.kitalonlabs.neogold.core.data.local.dao.CaptureDao
import com.kitalonlabs.neogold.core.data.local.dao.CardBatchDao
import com.kitalonlabs.neogold.core.data.local.dao.OutboxDao
import com.kitalonlabs.neogold.core.data.local.dao.ResearchParticipantDao
import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
import com.kitalonlabs.neogold.core.data.local.dao.ScreeningSessionDao
import com.kitalonlabs.neogold.core.data.local.dao.TsbMeasurementDao
import com.kitalonlabs.neogold.core.data.local.entity.OutboxEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.sync.research.api.ApiException
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.api.models.CaptureManifest
import com.kitalonlabs.neogold.sync.research.api.models.CaptureMetadata
import com.kitalonlabs.neogold.sync.research.api.models.CardBatchUpload
import com.kitalonlabs.neogold.sync.research.api.models.ErrorCode
import com.kitalonlabs.neogold.sync.research.api.models.ParticipantUpload
import com.kitalonlabs.neogold.sync.research.api.models.SessionManifest
import com.kitalonlabs.neogold.sync.research.api.models.TsbUpload
import com.kitalonlabs.neogold.sync.research.crypto.Base64Url
import com.kitalonlabs.neogold.sync.research.enrollment.StudyRevocationManager
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed class OutboxResult {
    data class Success(val itemsProcessed: Int) : OutboxResult()
    object Paused : OutboxResult()
    object Closed : OutboxResult()
    object Revoked : OutboxResult()
    data class AppVersionUnsupported(val minVersion: String) : OutboxResult()
    data class Error(val message: String, val cause: Throwable? = null) : OutboxResult()
}

class OutboxProcessor(
    private val apiClient: ResearchApiClient,
    private val outboxDao: OutboxDao,
    private val studyDao: ResearchStudyDao,
    private val batchDao: CardBatchDao,
    private val participantDao: ResearchParticipantDao,
    private val sessionDao: ScreeningSessionDao,
    private val captureDao: CaptureDao,
    private val tsbDao: TsbMeasurementDao,
    private val encryptedFileStore: EncryptedFileStore,
    private val revocationManager: StudyRevocationManager,
    private val reconciliationManager: ReconciliationManager,
    private val appVersion: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
) {

    suspend fun process(): OutboxResult {
        val study = studyDao.getActiveStudy() ?: return OutboxResult.Error("No active research study found")
        val baseUrl = study.serverUrl ?: return OutboxResult.Error("No server URL configured for study")
        val deviceId = study.serverDeviceId ?: return OutboxResult.Error("Device not enrolled")
        val keyAlias = study.deviceKeyAlias ?: return OutboxResult.Error("Device key alias missing")

        // 1. GET /device first (doc 7 Section 3)
        val deviceStatus = try {
            apiClient.getDevice(
                baseUrl = baseUrl,
                deviceId = deviceId,
                keyAlias = keyAlias,
                appVersion = appVersion
            )
        } catch (e: ApiException) {
            if (e.errorCode == ErrorCode.DEVICE_REVOKED) {
                revocationManager.handleDeviceRevoked(study)
                return OutboxResult.Revoked
            }
            return OutboxResult.Error("Failed to check device status: ${e.message}", e)
        } catch (e: Exception) {
            return OutboxResult.Error("Failed to connect to server: ${e.message}", e)
        }

        // Check revocation
        if (deviceStatus.revoked) {
            revocationManager.handleDeviceRevoked(study)
            return OutboxResult.Revoked
        }

        // Check study status
        if (deviceStatus.study_status == "PAUSED") {
            // Keep outbox; stop early before any image is sent (TC-SYNC-02)
            return OutboxResult.Paused
        } else if (deviceStatus.study_status == "CLOSED") {
            return OutboxResult.Closed
        }

        // Check minimum app version
        if (isVersionBelow(appVersion, deviceStatus.min_app_version)) {
            return OutboxResult.AppVersionUnsupported(deviceStatus.min_app_version)
        }

        // Check data_generation for server restore from backup (doc 7 Section 5.4, TC-SYNC-14)
        val storedGen = study.dataGeneration ?: 0L
        if (deviceStatus.data_generation > storedGen) {
            reconciliationManager.recheckAndResendAllHeldItems(study.id, deviceStatus.data_generation)
        }

        // 2. Reset any IN_FLIGHT rows to PENDING (killed run recovers, doc 7 Section 5.3, TC-SYNC-11)
        outboxDao.resetInFlightToPending()

        // 3. Process items in strict dependency order:
        // Level 1: CARD_BATCH
        // Level 2: PARTICIPANT
        // Level 3: SESSION_MANIFEST
        // Level 4: CAPTURE_IMAGE
        // Level 5: TSB
        var processedCount = 0
        val entityOrder = listOf("CARD_BATCH", "PARTICIPANT", "SESSION_MANIFEST", "CAPTURE_IMAGE", "TSB")

        for (entityType in entityOrder) {
            val pendingItems = outboxDao.getItemsByState("PENDING").filter { it.entityType == entityType }
            for (item in pendingItems) {
                val success = processItem(item, study, baseUrl, deviceId, keyAlias)
                if (success) {
                    processedCount++
                }
            }
        }

        return OutboxResult.Success(processedCount)
    }

    private suspend fun processItem(
        item: OutboxEntity,
        study: ResearchStudyEntity,
        baseUrl: String,
        deviceId: String,
        keyAlias: String
    ): Boolean {
        val now = System.currentTimeMillis()
        outboxDao.updateState(item.id, "IN_FLIGHT", now)

        try {
            when (item.entityType) {
                "CARD_BATCH" -> processCardBatch(item, baseUrl, deviceId, keyAlias)
                "PARTICIPANT" -> processParticipant(item, study, baseUrl, deviceId, keyAlias)
                "SESSION_MANIFEST" -> processSessionManifest(item, study, baseUrl, deviceId, keyAlias)
                "CAPTURE_IMAGE" -> processCaptureImage(item, baseUrl, deviceId, keyAlias)
                "TSB" -> processTsb(item, study, baseUrl, deviceId, keyAlias)
                else -> error("Unknown entity type: ${item.entityType}")
            }
            outboxDao.updateState(item.id, "ACKED", System.currentTimeMillis())
            return true
        } catch (e: ApiException) {
            handleApiError(item, e)
            return false
        } catch (e: Exception) {
            // Network failure / timeout: keep key, retry with backoff (TC-SYNC-03)
            outboxDao.recordAttempt(item.id, e.message?.take(100), System.currentTimeMillis())
            outboxDao.updateState(item.id, "PENDING", System.currentTimeMillis())
            return false
        }
    }

    private suspend fun processCardBatch(
        item: OutboxEntity,
        baseUrl: String,
        deviceId: String,
        keyAlias: String
    ) {
        val batch = batchDao.getBatch(item.entityId)
            ?: error("Card batch ${item.entityId} not found in database")
        val upload = CardBatchUpload(qr_payload = Base64Url.encodeStandard(batch.qrPayload))

        try {
            apiClient.putCardBatch(
                baseUrl = baseUrl,
                deviceId = deviceId,
                keyAlias = keyAlias,
                batchId = batch.batchId,
                idempotencyKey = item.idempotencyKey,
                appVersion = appVersion,
                upload = upload
            )
            batchDao.update(batch.copy(serverStatus = "ACCEPTED"))
        } catch (e: ApiException) {
            if (e.errorCode == ErrorCode.CARD_BATCH_INVALID) {
                // Batch rejected: mark REJECTED so sessions upload as UNVERIFIED (TC-SYNC-07)
                batchDao.update(batch.copy(serverStatus = "REJECTED"))
                outboxDao.updateState(item.id, "ACKED", System.currentTimeMillis())
                return
            }
            throw e
        }
    }

    private suspend fun processParticipant(
        item: OutboxEntity,
        study: ResearchStudyEntity,
        baseUrl: String,
        deviceId: String,
        keyAlias: String
    ) {
        val participant = participantDao.getParticipantById(item.entityId)
            ?: error("Participant ${item.entityId} not found")
        val consentDate = formatCalendarDate(participant.consentRecordedAt, study.timeZone)

        val upload = ParticipantUpload(
            participant_code = participant.participantCode,
            consent_form_id = participant.consentFormId,
            consent_date = consentDate
        )

        apiClient.putParticipant(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            participantId = participant.id,
            idempotencyKey = item.idempotencyKey,
            appVersion = appVersion,
            upload = upload
        )
    }

    private suspend fun processSessionManifest(
        item: OutboxEntity,
        study: ResearchStudyEntity,
        baseUrl: String,
        deviceId: String,
        keyAlias: String
    ) {
        val session = sessionDao.getSessionById(item.entityId)
            ?: error("Session ${item.entityId} not found")
        val participant = session.participantId?.let { participantDao.getParticipantById(it) }
            ?: error("Participant not linked to session ${item.entityId}")

        // Determine card status & card_batch_id
        var cardStatus = "UNVERIFIED"
        var cardBatchId: String? = null
        val sBatchId = session.cardBatchId
        if (sBatchId != null) {
            val batch = batchDao.getBatch(sBatchId)
            if (batch != null && batch.serverStatus != "REJECTED") {
                val now = System.currentTimeMillis()
                cardStatus = if (batch.expiresAt < now) "EXPIRED" else "VERIFIED"
                cardBatchId = sBatchId
            }
        }

        // Format dates in study time zone (doc 7 Section 1, TC-SYNC-18)
        val captureDate = formatCalendarDate(session.createdAt, study.timeZone)
        val capturedAt = if (study.timestampPrecision == "FULL") {
            formatIsoDateTime(session.createdAt)
        } else {
            null
        }

        val captures = captureDao.getCapturesForSession(session.id)
        val captureManifests = captures.map { c ->
            val hasImage = c.imageFile != null && c.imageSha256 != null
            val fileSizeBytes = if (hasImage) {
                try {
                    val file = encryptedFileStore.getEncryptedFile(c.imageFile!!)
                    if (file.exists()) file.length() else 1000000L
                } catch (_: Exception) { 1000000L }
            } else {
                null
            }

            val failReasons = c.qualityFailReasons?.let { reasonsJson ->
                try {
                    json.decodeFromString<List<String>>(reasonsJson)
                } catch (_: Exception) { null }
            }

            val features = c.features?.let { featJson ->
                try {
                    json.decodeFromString<Map<String, Double>>(featJson)
                } catch (_: Exception) { null }
            }

            val colorGains = c.colorGains?.let { cgJson ->
                try {
                    json.decodeFromString<List<Double>>(cgJson)
                } catch (_: Exception) { null }
            }

            CaptureManifest(
                capture_id = c.id,
                attempt_number = c.attemptNumber,
                site = c.site,
                quality_status = c.qualityStatus,
                quality_fail_reasons = failReasons,
                correction_residual_de = c.correctionResidualDe?.toDouble(),
                features = features,
                capture_metadata = CaptureMetadata(
                    iso = c.iso,
                    exposure_ns = c.exposureNs,
                    ae_locked = c.aeLocked == 1,
                    awb_locked = c.awbLocked == 1,
                    camera_hardware_level = "LIMITED",
                    color_gains = colorGains
                ),
                has_image = hasImage,
                image_sha256 = if (hasImage) c.imageSha256 else null,
                size_bytes = fileSizeBytes
            )
        }

        val manifest = SessionManifest(
            participant_id = participant.id,
            card_status = cardStatus,
            card_batch_id = cardBatchId,
            capture_date = captureDate,
            captured_at = capturedAt,
            age_hours = session.ageHours.toDouble(),
            gestational_age_weeks = session.gestationalAgeWeeks,
            flag_jaundice_under_24h = session.flagJaundiceUnder24h == 1,
            flag_danger_signs = session.flagDangerSigns == 1,
            flag_prior_phototherapy = session.flagPriorPhototherapy == 1,
            estimate_mg_dl = session.estimateMgDl?.toDouble(),
            estimate_low_mg_dl = session.estimateLowMgDl?.toDouble(),
            estimate_high_mg_dl = session.estimateHighMgDl?.toDouble(),
            app_version = session.appVersion,
            model_version = session.modelVersion,
            config_version = session.configVersion,
            captures = captureManifests
        )

        apiClient.putSessionManifest(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            sessionId = session.id,
            idempotencyKey = item.idempotencyKey,
            appVersion = appVersion,
            manifest = manifest
        )
    }

    private suspend fun processCaptureImage(
        item: OutboxEntity,
        baseUrl: String,
        deviceId: String,
        keyAlias: String
    ) {
        val capture = captureDao.getCaptureById(item.entityId)
            ?: error("Capture ${item.entityId} not found")
        val imageFile = capture.imageFile ?: error("No image file recorded for capture ${capture.id}")
        val imageSha256 = capture.imageSha256 ?: error("No image SHA-256 for capture ${capture.id}")

        // Decrypt and stream directly without writing plain image to disk (TC-SYNC-20)
        val file = encryptedFileStore.getEncryptedFile(imageFile)
        val sizeBytes = if (file.exists()) file.length() else 1000000L

        apiClient.putCaptureImage(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            sessionId = capture.sessionId,
            captureId = capture.id,
            idempotencyKey = item.idempotencyKey,
            appVersion = appVersion,
            imageSha256Hex = imageSha256,
            imageStreamProvider = { encryptedFileStore.openDecryptedStream(imageFile) },
            imageSizeBytes = sizeBytes
        )
    }

    private suspend fun processTsb(
        item: OutboxEntity,
        study: ResearchStudyEntity,
        baseUrl: String,
        deviceId: String,
        keyAlias: String
    ) {
        val tsb = tsbDao.getTsbById(item.entityId)
            ?: error("TSB measurement ${item.entityId} not found")

        val tsbSessionId = tsb.sessionId
        val minutesFromCapture: Int? = if (tsbSessionId != null) {
            val session = sessionDao.getSessionById(tsbSessionId)
            if (session != null) {
                ((tsb.drawTime - session.createdAt) / 60000).toInt()
            } else {
                null
            }
        } else {
            null
        }

        val drawTime = if (study.timestampPrecision == "FULL") {
            formatIsoDateTime(tsb.drawTime)
        } else {
            null
        }

        val upload = TsbUpload(
            participant_id = tsb.participantId,
            session_id = tsb.sessionId,
            tsb_mg_dl = tsb.tsbMgDl.toDouble(),
            minutes_from_capture = minutesFromCapture,
            draw_time = drawTime,
            method = tsb.method
        )

        apiClient.putTsbMeasurement(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            tsbId = tsb.id,
            idempotencyKey = item.idempotencyKey,
            appVersion = appVersion,
            upload = upload
        )
    }

    private suspend fun handleApiError(item: OutboxEntity, error: ApiException) {
        val now = System.currentTimeMillis()
        val code = error.errorCode

        // 1. Dependency errors: re-queue dependency first, cap at 3 (doc 7 Section 3, Section 5.2, TC-SYNC-05)
        if (code == ErrorCode.PARTICIPANT_UNKNOWN || code == ErrorCode.SESSION_UNKNOWN || code == ErrorCode.CARD_BATCH_UNKNOWN) {
            val currentRequeues = item.dependencyRequeues
            if (currentRequeues + 1 >= 3) {
                outboxDao.recordAttempt(item.id, code, now)
                outboxDao.updateState(item.id, "FAILED_PERMANENT", now)
            } else {
                outboxDao.recordDependencyRequeue(item.id, now)
                requeueMissingDependency(item, code)
            }
            return
        }

        // 2. Hash mismatch: retry up to 3 times, then permanent (TC-SYNC-06)
        if (code == ErrorCode.HASH_MISMATCH) {
            val attempts = item.attempts + 1
            if (attempts >= 3) {
                outboxDao.recordAttempt(item.id, code, now)
                outboxDao.updateState(item.id, "FAILED_PERMANENT", now)
            } else {
                outboxDao.recordAttempt(item.id, code, now)
                outboxDao.updateState(item.id, "PENDING", now)
            }
            return
        }

        // 3. Request in progress: retry after 30 seconds with same key (TC-SYNC-04)
        if (code == ErrorCode.REQUEST_IN_PROGRESS) {
            outboxDao.recordAttempt(item.id, code, now)
            outboxDao.updateState(item.id, "PENDING", now)
            return
        }

        // 4. Rate limited / 5xx / Maintenance: retryable with backoff
        if (error.statusCode in 500..599 || code == ErrorCode.RATE_LIMITED || code == ErrorCode.MAINTENANCE || code == ErrorCode.STORAGE_FULL) {
            outboxDao.recordAttempt(item.id, code, now)
            outboxDao.updateState(item.id, "PENDING", now)
            return
        }

        // 5. Unknown error code: follow retryable flag, fallback to HTTP class (TC-SYNC-08)
        val isRetryable = error.problem.retryable ?: (error.statusCode >= 500)
        if (isRetryable) {
            outboxDao.recordAttempt(item.id, code, now)
            outboxDao.updateState(item.id, "PENDING", now)
        } else {
            outboxDao.recordAttempt(item.id, code, now)
            outboxDao.updateState(item.id, "FAILED_PERMANENT", now)
        }
    }

    private suspend fun requeueMissingDependency(item: OutboxEntity, code: String) {
        val now = System.currentTimeMillis()
        when (code) {
            ErrorCode.PARTICIPANT_UNKNOWN -> {
                val participantId = when (item.entityType) {
                    "SESSION_MANIFEST" -> sessionDao.getSessionById(item.entityId)?.participantId
                    "TSB" -> tsbDao.getTsbById(item.entityId)?.participantId
                    else -> null
                }
                if (participantId != null) {
                    val pItem = outboxDao.getItemByEntity("PARTICIPANT", participantId)
                    if (pItem != null) {
                        outboxDao.update(pItem.copy(state = "PENDING", attempts = 0, updatedAt = now))
                    }
                }
            }
            ErrorCode.SESSION_UNKNOWN -> {
                val sessionId = when (item.entityType) {
                    "CAPTURE_IMAGE" -> captureDao.getCaptureById(item.entityId)?.sessionId
                    "TSB" -> tsbDao.getTsbById(item.entityId)?.sessionId
                    else -> null
                }
                if (sessionId != null) {
                    val sItem = outboxDao.getItemByEntity("SESSION_MANIFEST", sessionId)
                    if (sItem != null) {
                        outboxDao.update(sItem.copy(state = "PENDING", attempts = 0, updatedAt = now))
                    }
                }
            }
            ErrorCode.CARD_BATCH_UNKNOWN -> {
                if (item.entityType == "SESSION_MANIFEST") {
                    val batchId = sessionDao.getSessionById(item.entityId)?.cardBatchId
                    if (batchId != null) {
                        val bItem = outboxDao.getItemByEntity("CARD_BATCH", batchId)
                        if (bItem != null) {
                            outboxDao.update(bItem.copy(state = "PENDING", attempts = 0, updatedAt = now))
                        }
                    }
                }
            }
        }
    }

    /**
     * When the app updates, re-queue client-bug permanent failures once with a new idempotency key (TC-SYNC-09).
     */
    suspend fun recoverClientBugFailuresAfterAppUpdate(oldVersion: String, newVersion: String) {
        if (oldVersion == newVersion) return

        val permanentItems = outboxDao.getItemsByState("FAILED_PERMANENT")
        val now = System.currentTimeMillis()
        for (item in permanentItems) {
            if (item.lastError in ErrorCode.CLIENT_BUG_CODES) {
                val requeued = item.copy(
                    idempotencyKey = UUID.randomUUID().toString(),
                    revision = item.revision + 1,
                    state = "PENDING",
                    attempts = 0,
                    dependencyRequeues = 0,
                    lastError = null,
                    updatedAt = now
                )
                outboxDao.update(requeued)
            }
        }
    }

    companion object {
        fun formatCalendarDate(epochMs: Long, timeZone: String): String {
            val zone = try {
                ZoneId.of(timeZone)
            } catch (_: Exception) {
                ZoneId.of("Asia/Dhaka")
            }
            return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()
        }

        fun formatIsoDateTime(epochMs: Long): String {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))
        }

        fun isVersionBelow(current: String, min: String): Boolean {
            val c = parseSemVer(current)
            val m = parseSemVer(min)
            if (c[0] != m[0]) return c[0] < m[0]
            if (c[1] != m[1]) return c[1] < m[1]
            return c[2] < m[2]
        }

        private fun parseSemVer(v: String): List<Int> {
            return try {
                v.trim().split(".").map { it.toInt() }
            } catch (_: Exception) {
                listOf(1, 0, 0)
            }
        }
    }
}

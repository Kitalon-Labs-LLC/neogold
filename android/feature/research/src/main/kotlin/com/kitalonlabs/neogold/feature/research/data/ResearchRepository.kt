package com.kitalonlabs.neogold.feature.research.data

import androidx.room.withTransaction
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.core.data.local.entity.MAX_TSB_MG_DL
import com.kitalonlabs.neogold.core.data.local.entity.OutboxEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.data.local.entity.TsbMeasurementEntity
import com.kitalonlabs.neogold.core.data.pin.PinCheckResult
import com.kitalonlabs.neogold.core.data.pin.ResearchPinManager
import com.kitalonlabs.neogold.core.security.EncryptedFileStore
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.PinPolicyValidator
import com.kitalonlabs.neogold.core.security.pin.PinValidationResult
import com.kitalonlabs.neogold.engine.camera.research.ResearchJpegSanitizer
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.PayloadParser
import com.kitalonlabs.neogold.engine.cardtrust.StudyProfilePayload
import com.kitalonlabs.neogold.engine.cardtrust.TrustList
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import timber.log.Timber
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

enum class CardTrustStatus {
    VERIFIED,
    EXPIRED,
    UNVERIFIED
}

/**
 * One research photo and what is known about it. There are no defaults for what the camera and the quality gate say:
 * a value that was not measured is null, a lock that was not seen is off, and whether the photo passed the gate is
 * always stated by the caller.
 */
data class ResearchCaptureInput(
    val site: String, // STERNUM, FOREHEAD, SCLERA_FLASH, SCLERA_NOFLASH
    val attemptNumber: Int = 1,
    val imageBytes: ByteArray,
    val qualityPassed: Boolean,
    val qualityFailReasons: List<String> = emptyList(),
    val correctionResidualDe: Double? = null,
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val aeLocked: Int = 0,
    val awbLocked: Int = 0,
    val colorGains: String? = null
)

/** Where a saved session came from: the app, the model and the config that were running, and the phone's profile. */
data class ResearchProvenance(
    val appVersion: String,
    val modelVersion: String?,
    val configVersion: String,
    val deviceProfileId: String?
)

data class TsbInput(
    val tsbMgDl: Double,
    val drawTime: Long,
    val method: String // LAB, POINT_OF_CARE, OTHER
)

data class ResearchSessionResult(
    val sessionId: String,
    val participantId: String,
    val participantCode: String,
    val cardStatus: CardTrustStatus,
    val capturesCount: Int,
    val tsbEntered: Boolean
)

/**
 * Repository orchestrating research mode operations, study enrollment,
 * blinded session persistence, paired TSB management, and outbox synchronization (doc 8, doc 7, doc 5).
 */
class ResearchRepository(
    val database: NeoGoldDatabase,
    val encryptedFileStore: EncryptedFileStore,
    val trustList: TrustList,
    val pinManager: ResearchPinManager,
    private val cardTrustVerifier: CardTrustVerifier = CardTrustVerifier(trustList),
    private val pinHasher: Argon2PinHasher = Argon2PinHasher(),
    /** Read when a session is saved, so the record names the app, model, config and profile that really ran. */
    private val provenance: suspend () -> ResearchProvenance,
    /** Whether a photo may be kept: a JPEG that carries nothing but its orientation. Tests of other things pass `{ true }`. */
    private val isAllowedImage: (ByteArray) -> Boolean = {
        // A damaged file can make the check itself throw (a length that runs past the end): that is a refusal, not a crash.
        runCatching { ResearchJpegSanitizer.validateAllowList(it).isValid }.getOrDefault(false)
    }
) {

    /**
     * Checks a scanned study QR against the trust list, so the screen can show which study it names before a PIN is
     * asked for (R01).
     */
    fun verifyStudyProfile(
        qrPayload: ByteArray,
        evaluationDate: LocalDate = LocalDate.now()
    ): Result<StudyProfilePayload> {
        val verification = cardTrustVerifier.verifyStudyProfile(qrPayload, evaluationDate)
        if (verification !is VerificationResult.Verified) {
            val errorMsg = when (verification) {
                is VerificationResult.BadSignature -> "Invalid signature on study profile QR"
                is VerificationResult.Expired -> "Study profile has expired"
                is VerificationResult.Revoked -> "Study profile has been revoked"
                is VerificationResult.UnknownIssuer -> "Study profile issuer is not in trust list"
                is VerificationResult.WrongPurpose -> "Key is not certified for STUDY_ISSUER"
                else -> "Malformed study profile payload"
            }
            return Result.failure(SecurityException(errorMsg))
        }
        return Result.success(verification.payload)
    }

    /**
     * Unlocks or enrolls research mode by verifying the signed study profile QR payload
     * and checking/setting the PIN (FR-25, NG-028, TC-RSH-01, TC-RSH-02, TC-RSH-03).
     */
    @Suppress("ReturnCount") // each refusal returns at once: bad QR, PIN refused, a second study, a weak PIN
    suspend fun unlockWithStudyQr(
        qrPayload: ByteArray,
        pin: String,
        evaluationDate: LocalDate = LocalDate.now(),
        now: Long = System.currentTimeMillis()
    ): Result<ResearchStudyEntity> {
        val profile = verifyStudyProfile(qrPayload, evaluationDate).getOrElse { return Result.failure(it) }

        // Check if study already exists in database
        val existingStudy = database.researchStudyDao().getStudyByCode(profile.studyCode)
        return if (existingStudy != null) {
            // Verify provided PIN against existing study
            when (val check = pinManager.verifyPin(existingStudy.id, pin, now)) {
                is PinCheckResult.Success -> {
                    if (existingStudy.exportPublicKey == null || !existingStudy.exportPublicKey.contentEquals(profile.exportPublicKey)) {
                        val updated = existingStudy.copy(exportPublicKey = profile.exportPublicKey)
                        database.researchStudyDao().update(updated)
                        Result.success(updated)
                    } else {
                        Result.success(existingStudy)
                    }
                }
                is PinCheckResult.LockedOut -> Result.failure(
                    SecurityException("PIN locked out for ${check.remainingMinutes} minutes")
                )
                is PinCheckResult.WrongPin -> Result.failure(
                    SecurityException(
                        "Incorrect PIN. ${check.attemptsUntilNextLockout} attempts remaining before lockout."
                    )
                )
                is PinCheckResult.VerifierKeyLost -> Result.failure(
                    SecurityException("The PIN cannot be checked on this phone. Enroll in the study again.")
                )
            }
        } else {
            // A second study is never enrolled over an active one: whoever holds the phone could type a PIN of their own
            // and get past the first study's PIN. Leaving a study goes through R09 (C4), not through another QR.
            val active = database.researchStudyDao().getActiveStudy()
            if (active != null) {
                return Result.failure(
                    SecurityException(
                        "Another study is already set up on this phone. Leave it before enrolling in a new one."
                    )
                )
            }
            // New study: Validate PIN policy
            val validation = PinPolicyValidator.validate(pin)
            if (validation is PinValidationResult.Invalid) {
                return Result.failure(
                    IllegalArgumentException("PIN rejected: ${validation.error}")
                )
            }

            val salt = pinHasher.generateSalt()
            val hash = pinHasher.hashPin(pin, salt)

            val newStudy = ResearchStudyEntity(
                id = UUID.randomUUID().toString(),
                studyCode = profile.studyCode,
                siteCode = profile.studyCode, // Initial site code default
                displayName = profile.displayName,
                serverUrl = profile.host?.let { "https://$it/v1" },
                deviceKeyAlias = null,
                serverDeviceId = null,
                pinHash = hash,
                pinSalt = salt,
                failedPinAttempts = 0,
                lockedUntil = null,
                timestampPrecision = profile.timestampPrecision.name,
                timeZone = "Asia/Dhaka",
                dataGeneration = null,
                localRetentionAfterAckDays = 14,
                exportPublicKey = profile.exportPublicKey,
                status = "ACTIVE",
                enrolledAt = now
            )

            database.researchStudyDao().insert(newStudy)

            database.auditEventDao().insert(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    eventType = "STUDY_UNLOCKED",
                    occurredAt = now,
                    details = "{\"study_code\":\"${profile.studyCode}\"}"
                )
            )

            Result.success(newStudy)
        }
    }

    /**
     * Enrolls or retrieves a participant with format validation (FR-26, TC-RSH-08, TC-RSH-09).
     */
    @Suppress("ReturnCount") // one database transaction per call; reworked with the research session in C2
    suspend fun registerParticipant(
        studyId: String,
        participantCode: String,
        consentFormId: String,
        now: Long = System.currentTimeMillis()
    ): Result<ResearchParticipantEntity> {
        val cleanCode = participantCode.trim()
        val cleanConsent = consentFormId.trim()

        if (!ResearchFormats.isValidParticipantCode(cleanCode)) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid participant code format. Must match ^[A-Z]{2,5}-[0-9]{3,6}$"
                )
            )
        }
        if (!ResearchFormats.isValidConsentFormId(cleanConsent)) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid consent form ID format: '$cleanConsent'. Must match ^[A-Z]{2,5}-[A-Z0-9-]{2,26}$"
                )
            )
        }

        val existing = database.researchParticipantDao().getParticipantByCode(studyId, cleanCode)
        if (existing != null) {
            if (existing.consentFormId != cleanConsent) {
                return Result.failure(
                    IllegalStateException(
                        "Participant code is already registered with a different consent form ID"
                    )
                )
            }
            return Result.success(existing)
        }

        val participant = ResearchParticipantEntity(
            id = UUID.randomUUID().toString(),
            studyId = studyId,
            participantCode = cleanCode,
            consentFormId = cleanConsent,
            consentRecordedAt = now,
            createdAt = now
        )

        database.withTransaction {
            database.researchParticipantDao().insert(participant)

            // Queue outbox entry for participant
            val idempotencyKey = UUID.randomUUID().toString()
            database.outboxDao().insertOrUpdate(
                OutboxEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = "PARTICIPANT",
                    entityId = participant.id,
                    idempotencyKey = idempotencyKey,
                    revision = 0,
                    payloadSha256 = computeSha256("${participant.id}:$cleanCode:$cleanConsent"),
                    state = "PENDING",
                    attempts = 0,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        return Result.success(participant)
    }

    /**
     * Completes and saves a blinded research session atomically
     * (FR-27, FR-29, NFR-09, TC-RSH-10, TC-RSH-13, TC-RSH-14, TC-RSH-15, TC-RSH-16).
     */
    @Suppress(
        "LongMethod",
        "LongParameterList",
        "TooGenericExceptionCaught"
    ) // one transaction per call, any failure returned as Result.failure; reworked in C2
    suspend fun saveBlindedResearchSession(
        participant: ResearchParticipantEntity,
        ageHours: Double,
        gestationalAgeWeeks: Int,
        flagEarlyJaundice: Boolean,
        flagDangerSigns: Boolean,
        flagPriorPhototherapy: Boolean,
        captures: List<ResearchCaptureInput>,
        cardBatchBytes: ByteArray?,
        tsbInput: TsbInput? = null,
        now: Long = System.currentTimeMillis()
    ): Result<ResearchSessionResult> {
        if (captures.isEmpty()) {
            return Result.failure(IllegalArgumentException("Research session requires at least one capture"))
        }
        if (ageHours.isNaN() || ageHours < 0.0 || ageHours > MAX_AGE_HOURS) {
            return Result.failure(
                IllegalArgumentException("The baby's age is outside 0 to 720 hours. Check the birth time.")
            )
        }
        if (captures.any { !isAllowedImage(it.imageBytes) }) {
            return Result.failure(IllegalArgumentException("A capture image is not an allowed JPEG"))
        }

        // Verify card batch status (TC-CARD-02, TC-CARD-05: research goes on, and the record says what the card was)
        var cardBatch: CardBatchPayload? = null
        var cardStatus = CardTrustStatus.UNVERIFIED

        if (cardBatchBytes != null) {
            when (val batchResult = cardTrustVerifier.verifyCardBatch(cardBatchBytes)) {
                is VerificationResult.Verified -> {
                    cardBatch = batchResult.payload
                    cardStatus = CardTrustStatus.VERIFIED
                }
                is VerificationResult.Expired -> {
                    cardBatch = PayloadParser.parseCardBatch(cardBatchBytes)
                    cardStatus = CardTrustStatus.EXPIRED
                }
                else -> {
                    cardBatch = PayloadParser.parseCardBatch(cardBatchBytes)
                    cardStatus = CardTrustStatus.UNVERIFIED
                }
            }
        }

        val sessionId = UUID.randomUUID().toString()
        val ran = provenance()
        val writtenFiles = mutableListOf<String>()

        return try {
            database.withTransaction {
                // 0. The card batch row goes in before the session row that names it (foreign key, BUG-2026-060). A batch
                // already on the phone is not replaced, and a session never names a batch whose bytes differ from the
                // stored one.
                val cardBatchId = cardBatch?.let { storeCardBatch(it, cardBatchBytes!!, cardStatus, now) }

                // 1. Insert ScreeningSession with mode = RESEARCH, estimateHidden = 1 (BLINDED)
                val sessionEntity = ScreeningSessionEntity(
                    id = sessionId,
                    mode = "RESEARCH",
                    status = "COMPLETED",
                    createdAt = now,
                    completedAt = now,
                    ageHours = ageHours,
                    guardianConsentConfirmed = 1,
                    gestationalAgeWeeks = gestationalAgeWeeks,
                    flagJaundiceUnder24h = if (flagEarlyJaundice) 1 else 0,
                    flagDangerSigns = if (flagDangerSigns) 1 else 0,
                    flagPriorPhototherapy = if (flagPriorPhototherapy) 1 else 0,
                    ruleOutcome = null,
                    triageAction = null,
                    reasonCodes = null,
                    estimateMgDl = null, // Strictly hidden / null in research mode UI
                    estimateLowMgDl = null,
                    estimateHighMgDl = null,
                    thresholdMgDl = null,
                    recheckDueAt = null,
                    captureAttempts = captures.size,
                    registerNumber = null,
                    registerNumberErasedAt = null,
                    participantId = participant.id,
                    estimateHidden = 1, // Mandatory blinded flag
                    experimentalLabelShown = 1,
                    deviceProfileId = ran.deviceProfileId,
                    cardBatchId = cardBatchId,
                    appVersion = ran.appVersion,
                    modelVersion = ran.modelVersion,
                    configVersion = ran.configVersion
                )
                database.screeningSessionDao().insert(sessionEntity)

                // 2. Encrypt & write each capture image file and insert CaptureEntity
                for (cap in captures) {
                    val captureId = UUID.randomUUID().toString()
                    val imageSha256 = computeSha256(cap.imageBytes)
                    val filename = "cap_$captureId.enc"

                    // Listed before the write, so a write that fails halfway is cleaned up with the others.
                    writtenFiles.add(filename)
                    // Encrypt image with Tink streaming AEAD via EncryptedFileStore
                    encryptedFileStore.writeEncryptedFile(filename) { outputStream ->
                        outputStream.write(cap.imageBytes)
                    }

                    val captureEntity = CaptureEntity(
                        id = captureId,
                        sessionId = sessionId,
                        attemptNumber = cap.attemptNumber,
                        site = cap.site,
                        capturedAt = now,
                        iso = cap.iso,
                        exposureNs = cap.exposureNs,
                        aeLocked = cap.aeLocked,
                        awbLocked = cap.awbLocked,
                        colorGains = cap.colorGains,
                        qualityStatus = if (cap.qualityPassed) "PASSED" else "FAILED",
                        qualityFailReasons = cap.qualityFailReasons.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" },
                        correctionResidualDe = cap.correctionResidualDe,
                        features = null,
                        imageFile = filename,
                        imageSha256 = imageSha256
                    )
                    database.captureDao().insert(captureEntity)

                    // Enqueue outbox item for each capture image
                    database.outboxDao().insertOrUpdate(
                        OutboxEntity(
                            id = UUID.randomUUID().toString(),
                            entityType = "CAPTURE_IMAGE",
                            entityId = captureId,
                            idempotencyKey = UUID.randomUUID().toString(),
                            revision = 0,
                            payloadSha256 = imageSha256,
                            state = "PENDING",
                            attempts = 0,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }

                // 3. Enqueue outbox manifest
                database.outboxDao().insertOrUpdate(
                    OutboxEntity(
                        id = UUID.randomUUID().toString(),
                        entityType = "SESSION_MANIFEST",
                        entityId = sessionId,
                        idempotencyKey = UUID.randomUUID().toString(),
                        revision = 0,
                        payloadSha256 = computeSha256("$sessionId:${participant.id}:$cardStatus"),
                        state = "PENDING",
                        attempts = 0,
                        createdAt = now,
                        updatedAt = now
                    )
                )

                // 4. Record paired TSB measurement if entered immediately
                var tsbEntered = false
                if (tsbInput != null) {
                    val tsbId = UUID.randomUUID().toString()
                    val tsbEntity = TsbMeasurementEntity(
                        id = tsbId,
                        participantId = participant.id,
                        sessionId = sessionId,
                        tsbMgDl = tsbInput.tsbMgDl,
                        drawTime = tsbInput.drawTime,
                        method = tsbInput.method,
                        enteredAt = now
                    )
                    database.tsbMeasurementDao().insert(tsbEntity)

                    database.outboxDao().insertOrUpdate(
                        OutboxEntity(
                            id = UUID.randomUUID().toString(),
                            entityType = "TSB",
                            entityId = tsbId,
                            idempotencyKey = UUID.randomUUID().toString(),
                            revision = 0,
                            payloadSha256 = computeSha256("$tsbId:${tsbInput.tsbMgDl}"),
                            state = "PENDING",
                            attempts = 0,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    tsbEntered = true
                }

                database.auditEventDao().insert(
                    AuditEventEntity(
                        id = UUID.randomUUID().toString(),
                        eventType = "RESEARCH_SESSION_SAVED",
                        occurredAt = now,
                        details = "{\"session_id\":\"$sessionId\",\"captures\":${captures.size}}"
                    )
                )

                Result.success(
                    ResearchSessionResult(
                        sessionId = sessionId,
                        participantId = participant.id,
                        participantCode = participant.participantCode,
                        cardStatus = cardStatus,
                        capturesCount = captures.size,
                        tsbEntered = tsbEntered
                    )
                )
            }
        } catch (e: CancellationException) {
            // The save was cancelled: the database rolled back, so remove the files and let the cancellation go on.
            writtenFiles.forEach { runCatching { encryptedFileStore.delete(it) } }
            throw e
        } catch (e: Exception) {
            // Only the type is logged: the message of a database error can carry values (doc 8 Section 6.6).
            Timber.e("Error saving research session (%s); rolling back transaction", e.javaClass.name)
            // The database rolled back, so the encrypted files written inside it would be left with no row: remove them.
            writtenFiles.forEach { runCatching { encryptedFileStore.delete(it) } }
            Result.failure(e)
        }
    }

    /**
     * Keeps the card batch a research session used, and returns the ID the session may name, or null if the stored
     * batch of that ID is not the same card (a different signed payload under the same ID).
     */
    private suspend fun storeCardBatch(
        batch: CardBatchPayload,
        payload: ByteArray,
        status: CardTrustStatus,
        now: Long
    ): String? {
        val existing = database.cardBatchDao().getBatch(batch.batchId)
        if (existing != null) return batch.batchId.takeIf { existing.qrPayload.contentEquals(payload) }
        val referenceValues = batch.referenceValuesJson() ?: return null
        database.cardBatchDao().insert(
            CardBatchEntity(
                batchId = batch.batchId,
                issuerId = batch.issuerKeyId,
                formatVersion = batch.formatVersion,
                referenceValues = referenceValues,
                issuedAt = batch.issuedAt.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                expiresAt = batch.expiresAt.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                signatureValid = if (status == CardTrustStatus.UNVERIFIED) 0 else 1,
                qrPayload = payload,
                serverStatus = "NOT_SENT",
                firstSeenAt = now
            )
        )
        return batch.batchId
    }

    /**
     * Records a TSB measurement by participant code (FR-28, NG-031, TC-RSH-11).
     * Can be entered later without a session ID.
     */
    @Suppress("ReturnCount") // one database transaction per call; reworked with the research session in C2
    suspend fun recordTsb(
        participantCode: String,
        tsbMgDl: Double,
        drawTime: Long,
        method: String,
        sessionId: String? = null,
        now: Long = System.currentTimeMillis()
    ): Result<TsbMeasurementEntity> {
        val cleanCode = participantCode.trim()
        val study = database.researchStudyDao().getActiveStudy()
            ?: return Result.failure(IllegalStateException("No active research study"))

        val participant = database.researchParticipantDao().getParticipantByCode(study.id, cleanCode)
            ?: return Result.failure(IllegalArgumentException("Participant not found"))

        if (tsbMgDl <= 0.0 || tsbMgDl > MAX_TSB_MG_DL) {
            return Result.failure(
                IllegalArgumentException("TSB ($tsbMgDl mg/dL) must be between 0.0 and $MAX_TSB_MG_DL")
            )
        }
        if (method !in setOf("LAB", "POINT_OF_CARE", "OTHER")) {
            return Result.failure(IllegalArgumentException("Invalid TSB method: $method"))
        }

        val tsbId = UUID.randomUUID().toString()
        val tsbEntity = TsbMeasurementEntity(
            id = tsbId,
            participantId = participant.id,
            sessionId = sessionId,
            tsbMgDl = tsbMgDl,
            drawTime = drawTime,
            method = method,
            enteredAt = now
        )

        database.withTransaction {
            database.tsbMeasurementDao().insert(tsbEntity)

            database.outboxDao().insertOrUpdate(
                OutboxEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = "TSB",
                    entityId = tsbId,
                    idempotencyKey = UUID.randomUUID().toString(),
                    revision = 0,
                    payloadSha256 = computeSha256("$tsbId:$tsbMgDl"),
                    state = "PENDING",
                    attempts = 0,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        return Result.success(tsbEntity)
    }

    /**
     * Links an existing unlinked TSB measurement to a session and re-queues the outbox row
     * with an incremented revision and new idempotency key (FR-28, TC-RSH-12).
     */
    suspend fun linkTsbToSession(
        tsbId: String,
        sessionId: String,
        now: Long = System.currentTimeMillis()
    ): Result<Unit> {
        val tsb = database.tsbMeasurementDao().getTsbById(tsbId)
            ?: return Result.failure(IllegalArgumentException("TSB record $tsbId not found"))

        val session = database.screeningSessionDao().getSessionById(sessionId)
            ?: return Result.failure(IllegalArgumentException("Session $sessionId not found"))

        if (tsb.participantId != session.participantId) {
            return Result.failure(IllegalStateException("TSB and session belong to different participants"))
        }

        database.withTransaction {
            database.tsbMeasurementDao().linkTsbToSession(tsbId, sessionId)

            // Re-queue outbox row as a new revision (doc 5 Section 2.2, TC-RSH-12)
            val existingOutbox = database.outboxDao().getItemByEntity("TSB", tsbId)
            val nextRevision = (existingOutbox?.revision ?: 0) + 1
            val newIdempotencyKey = UUID.randomUUID().toString()

            val updatedOutbox = OutboxEntity(
                id = existingOutbox?.id ?: UUID.randomUUID().toString(),
                entityType = "TSB",
                entityId = tsbId,
                idempotencyKey = newIdempotencyKey,
                revision = nextRevision,
                payloadSha256 = computeSha256("$tsbId:${tsb.tsbMgDl}:$sessionId"),
                state = "PENDING",
                attempts = 0,
                createdAt = existingOutbox?.createdAt ?: now,
                updatedAt = now
            )
            database.outboxDao().insertOrUpdate(updatedOutbox)
        }

        return Result.success(Unit)
    }

    private fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun getActiveStudy(): ResearchStudyEntity? {
        return database.researchStudyDao().getActiveStudy()
    }

    suspend fun getActiveStudyExportPublicKey(): ByteArray? {
        return database.researchStudyDao().getActiveStudy()?.exportPublicKey
    }

    suspend fun getPendingUploadsCount(): Int {
        return database.outboxDao().getPendingItems().size
    }

    suspend fun verifyPin(studyId: String, pin: String, now: Long = System.currentTimeMillis()): PinCheckResult {
        return pinManager.verifyPin(studyId, pin, now)
    }

    private fun computeSha256(text: String): String {
        return computeSha256(text.toByteArray(Charsets.UTF_8))
    }
}

/** The study server takes ages of 0 to 720 hours (doc 7, `age_hours`). */
private const val MAX_AGE_HOURS = 720.0

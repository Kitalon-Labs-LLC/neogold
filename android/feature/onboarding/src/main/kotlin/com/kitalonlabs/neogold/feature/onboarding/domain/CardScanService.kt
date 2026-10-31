package com.kitalonlabs.neogold.feature.onboarding.domain

import com.kitalonlabs.neogold.core.data.local.dao.AuditEventDao
import com.kitalonlabs.neogold.core.data.local.dao.CardBatchDao
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.cardtrust.CardTrustVerifier
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Why a scanned card was not accepted (TC-CARD-02 to TC-CARD-06); NOT_SAVED is a storage failure, not the card's fault. */
enum class CardRejection { UNREADABLE, NOT_TRUSTED, EXPIRED, REVOKED, NOT_SAVED }

sealed interface CardScanOutcome {
    /** The card's signature, issuer, purpose and dates are good, and its batch is stored. */
    data class Verified(val batchId: String, val expiresAt: LocalDate) : CardScanOutcome

    data class Rejected(val reason: CardRejection) : CardScanOutcome
}

/**
 * Checks a scanned card QR against the trust list and, if it passes, keeps the batch (S06, NG-039, doc 5 Section 2.2).
 *
 * Nothing is stored for a card that fails, and the reason is written to the audit log as a value (`CARD_REJECTED`,
 * with the reason and no payload). A card that fails never reaches qualification, and so never reaches screening.
 *
 * @param today the date the card's validity is judged against; tests pass their own
 */
class CardScanService(
    private val verifier: CardTrustVerifier,
    private val cardBatches: CardBatchDao,
    private val audit: AuditEventDao,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now
) {

    suspend fun accept(payload: ByteArray): CardScanOutcome {
        return when (val result = verifier.verifyCardBatch(payload, today())) {
            is VerificationResult.Verified -> store(result.payload, payload)
            VerificationResult.Expired -> reject(CardRejection.EXPIRED)
            VerificationResult.Revoked -> reject(CardRejection.REVOKED)
            VerificationResult.UnknownIssuer,
            VerificationResult.WrongPurpose,
            VerificationResult.BadSignature -> reject(CardRejection.NOT_TRUSTED)
            VerificationResult.Malformed -> reject(CardRejection.UNREADABLE)
        }
    }

    private suspend fun store(batch: CardBatchPayload, payload: ByteArray): CardScanOutcome {
        val referenceValues = CardBatchReference.toJson(batch) ?: return reject(CardRejection.UNREADABLE)
        val existing = cardBatches.getBatch(batch.batchId)
        // The same batch ID with other signed content is not a re-scan: the server refuses it too (CONFLICT_IMMUTABLE),
        // and the colors of a batch already in use must not change under the phone.
        if (existing != null && !existing.qrPayload.contentEquals(payload)) return reject(CardRejection.NOT_TRUSTED)
        val entity = CardBatchEntity(
            batchId = batch.batchId,
            issuerId = batch.issuerKeyId,
            formatVersion = batch.formatVersion,
            referenceValues = referenceValues,
            issuedAt = batch.issuedAt.startOfDayMs(),
            expiresAt = batch.expiresAt.startOfDayMs(),
            signatureValid = 1,
            qrPayload = payload,
            // A batch that was seen before keeps when it was first seen and what the server knows of it.
            serverStatus = existing?.serverStatus ?: "NOT_SENT",
            firstSeenAt = existing?.firstSeenAt ?: clockMs()
        )
        if (existing == null) cardBatches.insert(entity)
        return CardScanOutcome.Verified(batch.batchId, batch.expiresAt)
    }

    private suspend fun reject(reason: CardRejection): CardScanOutcome {
        audit.insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                eventType = "CARD_REJECTED",
                occurredAt = clockMs(),
                details = "{\"reason\":\"${reason.name}\"}"
            )
        )
        return CardScanOutcome.Rejected(reason)
    }

    private fun LocalDate.startOfDayMs(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

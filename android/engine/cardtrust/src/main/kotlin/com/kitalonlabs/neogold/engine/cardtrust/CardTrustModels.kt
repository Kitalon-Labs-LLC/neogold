package com.kitalonlabs.neogold.engine.cardtrust

import java.time.LocalDate

/**
 * Key purpose bound to trusted cryptographic keys (doc 8 Section 4).
 */
enum class KeyPurpose {
    CARD_ISSUER,
    STUDY_ISSUER
}

/**
 * Timestamp minimization precision policy (doc 4 Section 9, doc 7 Section 4.1).
 */
enum class TimestampPrecision {
    DAY_PLUS_OFFSETS,
    FULL
}

/**
 * CIELAB reference measurement for a card color patch.
 */
data class PatchReference(
    val index: Int,
    val lStar: Double,
    val aStar: Double,
    val bStar: Double
)

/**
 * Decoded Card Batch payload (payload_type = 0x01).
 */
data class CardBatchPayload(
    val formatVersion: Int,
    val issuedAtDays: Int,
    val expiresAtDays: Int,
    val issuedAt: LocalDate,
    val expiresAt: LocalDate,
    val issuerKeyId: String,
    val batchId: String,
    val patchCount: Int,
    val patches: List<PatchReference>,
    val signedBytes: ByteArray,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CardBatchPayload) return false
        return formatVersion == other.formatVersion &&
            issuedAtDays == other.issuedAtDays &&
            expiresAtDays == other.expiresAtDays &&
            issuerKeyId == other.issuerKeyId &&
            batchId == other.batchId &&
            patchCount == other.patchCount &&
            patches == other.patches &&
            signedBytes.contentEquals(other.signedBytes) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = formatVersion
        result = 31 * result + issuedAtDays
        result = 31 * result + expiresAtDays
        result = 31 * result + issuerKeyId.hashCode()
        result = 31 * result + batchId.hashCode()
        result = 31 * result + patchCount
        result = 31 * result + patches.hashCode()
        result = 31 * result + signedBytes.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Decoded Study Profile payload (payload_type = 0x02).
 */
data class StudyProfilePayload(
    val formatVersion: Int,
    val expiresAtDays: Int,
    val expiresAt: LocalDate,
    val timestampPrecision: TimestampPrecision,
    val issuerKeyId: String,
    val studyCode: String,
    val displayName: String,
    val partner: String,
    val host: String?,
    val exportPublicKey: ByteArray,
    val signedBytes: ByteArray,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StudyProfilePayload) return false
        return formatVersion == other.formatVersion &&
            expiresAtDays == other.expiresAtDays &&
            timestampPrecision == other.timestampPrecision &&
            issuerKeyId == other.issuerKeyId &&
            studyCode == other.studyCode &&
            displayName == other.displayName &&
            partner == other.partner &&
            host == other.host &&
            exportPublicKey.contentEquals(other.exportPublicKey) &&
            signedBytes.contentEquals(other.signedBytes) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = formatVersion
        result = 31 * result + expiresAtDays
        result = 31 * result + timestampPrecision.hashCode()
        result = 31 * result + issuerKeyId.hashCode()
        result = 31 * result + studyCode.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + partner.hashCode()
        result = 31 * result + (host?.hashCode() ?: 0)
        result = 31 * result + exportPublicKey.contentHashCode()
        result = 31 * result + signedBytes.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Typed outcome of offline QR payload verification.
 */
sealed interface VerificationResult<out T> {
    data class Verified<T>(val payload: T) : VerificationResult<T>
    data object Expired : VerificationResult<Nothing>
    data object Revoked : VerificationResult<Nothing>
    data object UnknownIssuer : VerificationResult<Nothing>
    data object WrongPurpose : VerificationResult<Nothing>
    data object BadSignature : VerificationResult<Nothing>
    data object Malformed : VerificationResult<Nothing>
}

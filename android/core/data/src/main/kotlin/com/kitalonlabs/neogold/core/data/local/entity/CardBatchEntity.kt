package com.kitalonlabs.neogold.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "card_batch")
data class CardBatchEntity(
    @PrimaryKey
    @ColumnInfo(name = "batch_id")
    val batchId: String,
    @ColumnInfo(name = "issuer_id")
    val issuerId: String,
    @ColumnInfo(name = "format_version")
    val formatVersion: Int,
    @ColumnInfo(name = "reference_values")
    val referenceValues: String, // JSON array of CIELAB values per patch (D65)
    @ColumnInfo(name = "issued_at")
    val issuedAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
    @ColumnInfo(name = "signature_valid")
    val signatureValid: Int, // 0 or 1
    @ColumnInfo(name = "qr_payload", typeAffinity = ColumnInfo.BLOB)
    val qrPayload: ByteArray, // Exact signed payload
    @ColumnInfo(name = "server_status")
    val serverStatus: String, // NOT_SENT, ACCEPTED, REJECTED
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CardBatchEntity
        if (batchId != other.batchId) return false
        if (issuerId != other.issuerId) return false
        if (formatVersion != other.formatVersion) return false
        if (referenceValues != other.referenceValues) return false
        if (issuedAt != other.issuedAt) return false
        if (expiresAt != other.expiresAt) return false
        if (signatureValid != other.signatureValid) return false
        if (!qrPayload.contentEquals(other.qrPayload)) return false
        if (serverStatus != other.serverStatus) return false
        if (firstSeenAt != other.firstSeenAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = batchId.hashCode()
        result = 31 * result + issuerId.hashCode()
        result = 31 * result + formatVersion
        result = 31 * result + referenceValues.hashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + signatureValid
        result = 31 * result + qrPayload.contentHashCode()
        result = 31 * result + serverStatus.hashCode()
        result = 31 * result + firstSeenAt.hashCode()
        return result
    }
}

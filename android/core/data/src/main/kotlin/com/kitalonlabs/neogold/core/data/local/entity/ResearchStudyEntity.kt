package com.kitalonlabs.neogold.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "research_study",
    indices = [
        Index(value = ["study_code"], unique = true)
    ]
)
data class ResearchStudyEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "study_code")
    val studyCode: String,
    @ColumnInfo(name = "site_code")
    val siteCode: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "server_url")
    val serverUrl: String?,
    @ColumnInfo(name = "device_key_alias")
    val deviceKeyAlias: String?,
    @ColumnInfo(name = "server_device_id")
    val serverDeviceId: String?,
    @ColumnInfo(name = "pin_hash", typeAffinity = ColumnInfo.BLOB)
    val pinHash: ByteArray,
    @ColumnInfo(name = "pin_salt", typeAffinity = ColumnInfo.BLOB)
    val pinSalt: ByteArray,
    @ColumnInfo(name = "failed_pin_attempts", defaultValue = "0")
    val failedPinAttempts: Int = 0,
    @ColumnInfo(name = "locked_until")
    val lockedUntil: Long?,
    @ColumnInfo(name = "timestamp_precision", defaultValue = "'DAY_PLUS_OFFSETS'")
    val timestampPrecision: String = "DAY_PLUS_OFFSETS", // DAY_PLUS_OFFSETS, FULL
    @ColumnInfo(name = "time_zone", defaultValue = "'Asia/Dhaka'")
    val timeZone: String = "Asia/Dhaka",
    @ColumnInfo(name = "data_generation")
    val dataGeneration: Long?,
    @ColumnInfo(name = "local_retention_after_ack_days", defaultValue = "14")
    val localRetentionAfterAckDays: Int = 14,
    val status: String, // ACTIVE, CLOSED
    @ColumnInfo(name = "enrolled_at")
    val enrolledAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ResearchStudyEntity
        if (id != other.id) return false
        if (studyCode != other.studyCode) return false
        if (siteCode != other.siteCode) return false
        if (displayName != other.displayName) return false
        if (serverUrl != other.serverUrl) return false
        if (deviceKeyAlias != other.deviceKeyAlias) return false
        if (serverDeviceId != other.serverDeviceId) return false
        if (!pinHash.contentEquals(other.pinHash)) return false
        if (!pinSalt.contentEquals(other.pinSalt)) return false
        if (failedPinAttempts != other.failedPinAttempts) return false
        if (lockedUntil != other.lockedUntil) return false
        if (timestampPrecision != other.timestampPrecision) return false
        if (timeZone != other.timeZone) return false
        if (dataGeneration != other.dataGeneration) return false
        if (localRetentionAfterAckDays != other.localRetentionAfterAckDays) return false
        if (status != other.status) return false
        if (enrolledAt != other.enrolledAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + studyCode.hashCode()
        result = 31 * result + siteCode.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + (serverUrl?.hashCode() ?: 0)
        result = 31 * result + (deviceKeyAlias?.hashCode() ?: 0)
        result = 31 * result + (serverDeviceId?.hashCode() ?: 0)
        result = 31 * result + pinHash.contentHashCode()
        result = 31 * result + pinSalt.contentHashCode()
        result = 31 * result + failedPinAttempts
        result = 31 * result + (lockedUntil?.hashCode() ?: 0)
        result = 31 * result + timestampPrecision.hashCode()
        result = 31 * result + timeZone.hashCode()
        result = 31 * result + (dataGeneration?.hashCode() ?: 0)
        result = 31 * result + localRetentionAfterAckDays
        result = 31 * result + status.hashCode()
        result = 31 * result + enrolledAt.hashCode()
        return result
    }
}

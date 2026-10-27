package com.kitalonlabs.neogold.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "capture",
    foreignKeys = [
        ForeignKey(
            entity = ScreeningSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id", "attempt_number"])
    ]
)
data class CaptureEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "attempt_number")
    val attemptNumber: Int, // 1 to 3
    val site: String, // STERNUM, FOREHEAD, SCLERA_FLASH, SCLERA_NOFLASH, CALIBRATION
    @ColumnInfo(name = "captured_at")
    val capturedAt: Long,
    val iso: Int?,
    @ColumnInfo(name = "exposure_ns")
    val exposureNs: Long?,
    @ColumnInfo(name = "ae_locked")
    val aeLocked: Int, // 0 or 1
    @ColumnInfo(name = "awb_locked")
    val awbLocked: Int, // 0 or 1
    @ColumnInfo(name = "color_gains")
    val colorGains: String?, // JSON
    @ColumnInfo(name = "quality_status")
    val qualityStatus: String, // PASSED, FAILED
    @ColumnInfo(name = "quality_fail_reasons")
    val qualityFailReasons: String?, // JSON array
    @ColumnInfo(name = "correction_residual_de")
    val correctionResidualDe: Double?,
    val features: String?, // JSON
    @ColumnInfo(name = "image_file")
    val imageFile: String?, // Encrypted file name, research mode only; null in screening mode
    @ColumnInfo(name = "image_sha256")
    val imageSha256: String? // Plaintext SHA-256 hash, research mode only
) {
    init {
        require(
            attemptNumber in 1..3
        ) { "CHECK constraint failed: attempt_number ($attemptNumber) must be between 1 and 3" }
        require(imageFile == null || imageSha256 != null) {
            "CHECK constraint failed: image_file ($imageFile) requires image_sha256 to not be null"
        }
    }
}

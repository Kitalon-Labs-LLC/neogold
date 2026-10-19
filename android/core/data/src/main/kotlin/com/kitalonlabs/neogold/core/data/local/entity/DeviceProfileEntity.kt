package com.kitalonlabs.neogold.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "device_profile",
    foreignKeys = [
        ForeignKey(
            entity = CardBatchEntity::class,
            parentColumns = ["batch_id"],
            childColumns = ["card_batch_id"]
        )
    ],
    indices = [
        Index(value = ["card_batch_id"]),
        Index(value = ["status"])
    ]
)
data class DeviceProfileEntity(
    @PrimaryKey
    val id: String,
    val manufacturer: String,
    @ColumnInfo(name = "device_model")
    val deviceModel: String,
    @ColumnInfo(name = "android_api")
    val androidApi: Int,
    @ColumnInfo(name = "camera_id")
    val cameraId: String,
    @ColumnInfo(name = "ram_mb")
    val ramMb: Int,
    @ColumnInfo(name = "is_low_ram_device")
    val isLowRamDevice: Int, // 0 or 1
    @ColumnInfo(name = "camera_hardware_level")
    val cameraHardwareLevel: String, // LEGACY, LIMITED, FULL, LEVEL_3, EXTERNAL
    @ColumnInfo(name = "supports_ae_lock")
    val supportsAeLock: Int, // 0 or 1
    @ColumnInfo(name = "supports_awb_lock")
    val supportsAwbLock: Int, // 0 or 1
    @ColumnInfo(name = "supports_manual_sensor")
    val supportsManualSensor: Int, // 0 or 1
    @ColumnInfo(name = "supports_raw")
    val supportsRaw: Int, // 0 or 1
    @ColumnInfo(name = "residual_correction")
    val residualCorrection: String?, // JSON
    @ColumnInfo(name = "repeatability_de")
    val repeatabilityDe: Double,
    @ColumnInfo(name = "uniformity_de")
    val uniformityDe: Double,
    @ColumnInfo(name = "calibration_residual_de")
    val calibrationResidualDe: Double,
    val qualification: String, // QUALIFIED, RESEARCH_ONLY
    @ColumnInfo(name = "card_batch_id")
    val cardBatchId: String,
    @ColumnInfo(name = "calibrated_at")
    val calibratedAt: Long, // Epoch ms
    @ColumnInfo(name = "app_version")
    val appVersion: String,
    val status: String // ACTIVE, SUPERSEDED, FAILED
)

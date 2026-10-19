package com.kitalonlabs.neogold.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "screening_session",
    foreignKeys = [
        ForeignKey(
            entity = ResearchParticipantEntity::class,
            parentColumns = ["id"],
            childColumns = ["participant_id"]
        ),
        ForeignKey(
            entity = DeviceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["device_profile_id"]
        ),
        ForeignKey(
            entity = CardBatchEntity::class,
            parentColumns = ["batch_id"],
            childColumns = ["card_batch_id"]
        )
    ],
    indices = [
        Index(value = ["mode", "created_at"]),
        Index(value = ["status"]),
        Index(value = ["recheck_due_at"]),
        Index(value = ["participant_id"]),
        Index(value = ["device_profile_id"]),
        Index(value = ["card_batch_id"])
    ]
)
data class ScreeningSessionEntity(
    @PrimaryKey
    val id: String,
    val mode: String, // SCREENING, RESEARCH
    val status: String, // IN_PROGRESS, COMPLETED, ABANDONED
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
    @ColumnInfo(name = "age_hours")
    val ageHours: Double,
    @ColumnInfo(name = "guardian_consent_confirmed")
    val guardianConsentConfirmed: Int, // 1: confirmed before camera; 0: not confirmed
    @ColumnInfo(name = "gestational_age_weeks")
    val gestationalAgeWeeks: Int,
    @ColumnInfo(name = "flag_jaundice_under_24h")
    val flagJaundiceUnder24h: Int, // 0 or 1
    @ColumnInfo(name = "flag_danger_signs")
    val flagDangerSigns: Int, // 0 or 1
    @ColumnInfo(name = "flag_prior_phototherapy")
    val flagPriorPhototherapy: Int, // 0 or 1
    @ColumnInfo(name = "rule_outcome")
    val ruleOutcome: String?,
    @ColumnInfo(name = "triage_action")
    val triageAction: String?, // REFER_URGENT, BLOOD_TEST_TODAY, RECHECK, NO_FURTHER_ACTION_FROM_SCREEN, etc.
    @ColumnInfo(name = "reason_codes")
    val reasonCodes: String?, // JSON array
    @ColumnInfo(name = "estimate_mg_dl")
    val estimateMgDl: Double?,
    @ColumnInfo(name = "estimate_low_mg_dl")
    val estimateLowMgDl: Double?,
    @ColumnInfo(name = "estimate_high_mg_dl")
    val estimateHighMgDl: Double?,
    @ColumnInfo(name = "threshold_mg_dl")
    val thresholdMgDl: Double?,
    @ColumnInfo(name = "recheck_due_at")
    val recheckDueAt: Long?,
    @ColumnInfo(name = "capture_attempts", defaultValue = "0")
    val captureAttempts: Int = 0,
    @ColumnInfo(name = "register_number")
    val registerNumber: String?,
    @ColumnInfo(name = "register_number_erased_at")
    val registerNumberErasedAt: Long?,
    @ColumnInfo(name = "participant_id")
    val participantId: String?,
    @ColumnInfo(name = "estimate_hidden")
    val estimateHidden: Int, // 1 when blinded in research mode, 0 otherwise
    @ColumnInfo(name = "experimental_label_shown")
    val experimentalLabelShown: Int, // 0 or 1
    @ColumnInfo(name = "device_profile_id")
    val deviceProfileId: String?,
    @ColumnInfo(name = "card_batch_id")
    val cardBatchId: String?,
    @ColumnInfo(name = "app_version")
    val appVersion: String,
    @ColumnInfo(name = "model_version")
    val modelVersion: String?,
    @ColumnInfo(name = "config_version")
    val configVersion: String
) {
    init {
        // Enforce CHECK constraints in Kotlin as defined in doc 5 Section 2.2
        require(ageHours in 0.0..720.0) { "CHECK constraint failed: age_hours ($ageHours) must be in 0.0..720.0" }
        require(gestationalAgeWeeks in 22..44) { "CHECK constraint failed: gestational_age_weeks ($gestationalAgeWeeks) must be between 22 and 44" }
        if (mode == "SCREENING") {
            require(participantId == null) { "CHECK constraint failed: screening sessions cannot link to a participant" }
        } else if (mode == "RESEARCH") {
            require(participantId != null) { "CHECK constraint failed: research sessions always belong to a participant" }
            require(registerNumber == null) { "CHECK constraint failed: research sessions cannot have a register number" }
        }
    }
}

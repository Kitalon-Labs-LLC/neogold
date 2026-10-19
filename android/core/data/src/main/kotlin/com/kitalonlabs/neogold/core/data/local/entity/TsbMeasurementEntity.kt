package com.kitalonlabs.neogold.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tsb_measurement",
    foreignKeys = [
        ForeignKey(
            entity = ResearchParticipantEntity::class,
            parentColumns = ["id"],
            childColumns = ["participant_id"]
        ),
        ForeignKey(
            entity = ScreeningSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"]
        )
    ],
    indices = [
        Index(value = ["participant_id"]),
        Index(value = ["session_id"])
    ]
)
data class TsbMeasurementEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "participant_id")
    val participantId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String?,
    @ColumnInfo(name = "tsb_mg_dl")
    val tsbMgDl: Double,
    @ColumnInfo(name = "draw_time")
    val drawTime: Long,
    val method: String, // LAB, POINT_OF_CARE, OTHER
    @ColumnInfo(name = "entered_at")
    val enteredAt: Long
) {
    init {
        require(tsbMgDl > 0.0 && tsbMgDl <= 40.0) {
            "CHECK constraint failed: tsb_mg_dl ($tsbMgDl) must be > 0.0 and <= 40.0"
        }
    }
}

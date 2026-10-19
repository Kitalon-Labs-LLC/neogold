package com.kitalonlabs.neogold.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "research_participant",
    foreignKeys = [
        ForeignKey(
            entity = ResearchStudyEntity::class,
            parentColumns = ["id"],
            childColumns = ["study_id"]
        )
    ],
    indices = [
        Index(value = ["study_id", "participant_code"], unique = true)
    ]
)
data class ResearchParticipantEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "study_id")
    val studyId: String,
    @ColumnInfo(name = "participant_code")
    val participantCode: String,
    @ColumnInfo(name = "consent_form_id")
    val consentFormId: String,
    @ColumnInfo(name = "consent_recorded_at")
    val consentRecordedAt: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

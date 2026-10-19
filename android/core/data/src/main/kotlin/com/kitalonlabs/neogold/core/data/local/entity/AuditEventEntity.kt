package com.kitalonlabs.neogold.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_event",
    indices = [
        Index(value = ["occurred_at"])
    ]
)
data class AuditEventEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
    @ColumnInfo(name = "event_type")
    val eventType: String,
    val details: String? // JSON; never contains register numbers, participant codes, or estimates
)

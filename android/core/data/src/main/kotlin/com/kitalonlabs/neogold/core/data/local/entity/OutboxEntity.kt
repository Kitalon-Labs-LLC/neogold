package com.kitalonlabs.neogold.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["entity_type", "entity_id"], unique = true),
        Index(value = ["state", "updated_at"])
    ]
)
data class OutboxEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String, // CARD_BATCH, PARTICIPANT, SESSION_MANIFEST, CAPTURE_IMAGE, TSB
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(defaultValue = "0")
    val revision: Int = 0,
    @ColumnInfo(name = "dependency_requeues", defaultValue = "0")
    val dependencyRequeues: Int = 0,
    @ColumnInfo(name = "payload_sha256")
    val payloadSha256: String,
    val state: String, // PENDING, IN_FLIGHT, ACKED, FAILED_PERMANENT
    @ColumnInfo(defaultValue = "0")
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

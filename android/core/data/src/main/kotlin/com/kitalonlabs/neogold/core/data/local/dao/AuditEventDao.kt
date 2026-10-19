package com.kitalonlabs.neogold.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity

@Dao
interface AuditEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: AuditEventEntity)

    @Query("SELECT * FROM audit_event ORDER BY occurred_at DESC")
    suspend fun getAllEvents(): List<AuditEventEntity>

    @Query("SELECT * FROM audit_event WHERE event_type = :eventType ORDER BY occurred_at DESC")
    suspend fun getEventsByType(eventType: String): List<AuditEventEntity>

    @Query("SELECT * FROM audit_event WHERE occurred_at >= :sinceTimeMs ORDER BY occurred_at DESC")
    suspend fun getEventsSince(sinceTimeMs: Long): List<AuditEventEntity>

    @Query("DELETE FROM audit_event")
    suspend fun deleteAll()
}

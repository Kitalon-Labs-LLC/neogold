package com.kitalonlabs.neogold.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kitalonlabs.neogold.core.data.local.entity.OutboxEntity

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(item: OutboxEntity)

    @Update
    suspend fun update(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun getItemById(id: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun getItemByEntity(entityType: String, entityId: String): OutboxEntity?

    @Query(
        "SELECT * FROM outbox WHERE entity_id = :sessionId OR entity_id IN (SELECT id FROM capture WHERE session_id = :sessionId)"
    )
    suspend fun getItemsForSession(sessionId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE state = 'PENDING' ORDER BY updated_at ASC")
    suspend fun getPendingItems(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE state = :state ORDER BY updated_at ASC")
    suspend fun getItemsByState(state: String): List<OutboxEntity>

    @Query("UPDATE outbox SET state = 'PENDING' WHERE state = 'IN_FLIGHT'")
    suspend fun resetInFlightToPending(): Int

    @Query("UPDATE outbox SET state = :state, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateState(id: String, state: String, updatedAt: Long): Int

    @Query("UPDATE outbox SET attempts = attempts + 1, last_error = :error, updated_at = :updatedAt WHERE id = :id")
    suspend fun recordAttempt(id: String, error: String?, updatedAt: Long): Int

    @Query(
        "UPDATE outbox SET dependency_requeues = dependency_requeues + 1, " +
            "state = CASE WHEN dependency_requeues + 1 >= 3 THEN 'FAILED_PERMANENT' ELSE 'PENDING' END, " +
            "updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun recordDependencyRequeue(id: String, updatedAt: Long): Int

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteItem(id: String): Int

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}

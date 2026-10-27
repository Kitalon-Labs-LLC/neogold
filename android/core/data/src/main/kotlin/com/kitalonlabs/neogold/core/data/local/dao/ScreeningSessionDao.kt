package com.kitalonlabs.neogold.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity

@Dao
interface ScreeningSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ScreeningSessionEntity)

    @Update
    suspend fun update(session: ScreeningSessionEntity)

    @Query("SELECT * FROM screening_session WHERE id = :id")
    suspend fun getSessionById(id: String): ScreeningSessionEntity?

    @Query("SELECT * FROM screening_session WHERE mode = :mode ORDER BY created_at DESC")
    suspend fun getSessionsByMode(mode: String): List<ScreeningSessionEntity>

    @Query("SELECT * FROM screening_session WHERE participant_id = :participantId ORDER BY created_at DESC")
    suspend fun getSessionsForParticipant(participantId: String): List<ScreeningSessionEntity>

    @Query(
        "SELECT * FROM screening_session WHERE recheck_due_at IS NOT NULL AND recheck_due_at <= :currentTimeMs ORDER BY recheck_due_at ASC"
    )
    suspend fun getDueRechecks(currentTimeMs: Long): List<ScreeningSessionEntity>

    @Query("SELECT * FROM screening_session WHERE status = 'IN_PROGRESS' AND created_at < :cutoffTimeMs")
    suspend fun getAbandonedSessions(cutoffTimeMs: Long): List<ScreeningSessionEntity>

    @Query("DELETE FROM screening_session WHERE status = 'IN_PROGRESS' AND created_at < :cutoffTimeMs")
    suspend fun deleteAbandonedSessions(cutoffTimeMs: Long): Int

    @Query(
        "UPDATE screening_session SET register_number = NULL, register_number_erased_at = :erasureTimeMs " +
            "WHERE mode = 'SCREENING' AND register_number IS NOT NULL AND created_at < :cutoffTimeMs"
    )
    suspend fun eraseRegisterNumbersOlderThan(cutoffTimeMs: Long, erasureTimeMs: Long): Int

    @Query("DELETE FROM screening_session WHERE mode = :mode AND created_at < :cutoffTimeMs")
    suspend fun deleteSessionsOlderThan(mode: String, cutoffTimeMs: Long): Int

    @Query("DELETE FROM screening_session WHERE id = :id")
    suspend fun deleteSession(id: String): Int

    @Query("DELETE FROM screening_session")
    suspend fun deleteAll()
}

package com.kitalonlabs.neogold.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity

@Dao
interface CaptureDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(capture: CaptureEntity)

    @Query("SELECT * FROM capture WHERE session_id = :sessionId ORDER BY attempt_number ASC")
    suspend fun getCapturesForSession(sessionId: String): List<CaptureEntity>

    @Query("SELECT * FROM capture WHERE id = :id")
    suspend fun getCaptureById(id: String): CaptureEntity?

    @Query("DELETE FROM capture WHERE session_id = :sessionId")
    suspend fun deleteCapturesForSession(sessionId: String): Int

    @Query("DELETE FROM capture")
    suspend fun deleteAll()
}

package com.kitalonlabs.neogold.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kitalonlabs.neogold.core.data.local.entity.TsbMeasurementEntity

@Dao
interface TsbMeasurementDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tsb: TsbMeasurementEntity)

    @Update
    suspend fun update(tsb: TsbMeasurementEntity)

    @Query("SELECT * FROM tsb_measurement WHERE id = :id")
    suspend fun getTsbById(id: String): TsbMeasurementEntity?

    @Query("SELECT * FROM tsb_measurement WHERE participant_id = :participantId ORDER BY draw_time DESC")
    suspend fun getTsbForParticipant(participantId: String): List<TsbMeasurementEntity>

    @Query("SELECT * FROM tsb_measurement WHERE session_id = :sessionId")
    suspend fun getTsbForSession(sessionId: String): List<TsbMeasurementEntity>

    @Query("UPDATE tsb_measurement SET session_id = :sessionId WHERE id = :tsbId AND session_id IS NULL")
    suspend fun linkTsbToSession(tsbId: String, sessionId: String): Int

    @Query("DELETE FROM tsb_measurement")
    suspend fun deleteAll()
}

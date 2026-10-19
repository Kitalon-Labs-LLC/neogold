package com.kitalonlabs.neogold.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity

@Dao
interface CardBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: CardBatchEntity)

    @Update
    suspend fun update(batch: CardBatchEntity)

    @Query("SELECT * FROM card_batch WHERE batch_id = :batchId")
    suspend fun getBatch(batchId: String): CardBatchEntity?

    @Query("SELECT * FROM card_batch ORDER BY first_seen_at DESC")
    suspend fun getAllBatches(): List<CardBatchEntity>

    @Query("DELETE FROM card_batch")
    suspend fun deleteAll()
}

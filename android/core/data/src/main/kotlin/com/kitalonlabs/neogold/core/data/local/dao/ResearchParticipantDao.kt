package com.kitalonlabs.neogold.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity

@Dao
interface ResearchParticipantDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(participant: ResearchParticipantEntity)

    @Query("SELECT * FROM research_participant WHERE id = :id")
    suspend fun getParticipantById(id: String): ResearchParticipantEntity?

    @Query("SELECT * FROM research_participant WHERE study_id = :studyId AND participant_code = :participantCode")
    suspend fun getParticipantByCode(studyId: String, participantCode: String): ResearchParticipantEntity?

    @Query("SELECT * FROM research_participant WHERE study_id = :studyId ORDER BY created_at DESC")
    suspend fun getParticipantsForStudy(studyId: String): List<ResearchParticipantEntity>

    @Query("DELETE FROM research_participant")
    suspend fun deleteAll()
}

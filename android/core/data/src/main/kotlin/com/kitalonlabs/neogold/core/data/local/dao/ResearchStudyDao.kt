package com.kitalonlabs.neogold.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity

@Dao
interface ResearchStudyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(study: ResearchStudyEntity)

    @Update
    suspend fun update(study: ResearchStudyEntity)

    @Query("SELECT * FROM research_study WHERE id = :id")
    suspend fun getStudyById(id: String): ResearchStudyEntity?

    @Query("SELECT * FROM research_study WHERE study_code = :studyCode")
    suspend fun getStudyByCode(studyCode: String): ResearchStudyEntity?

    @Query("SELECT * FROM research_study WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveStudy(): ResearchStudyEntity?

    @Query("DELETE FROM research_study")
    suspend fun deleteAll()
}

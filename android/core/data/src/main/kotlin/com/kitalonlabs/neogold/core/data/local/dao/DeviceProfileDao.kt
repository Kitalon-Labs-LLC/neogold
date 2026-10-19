package com.kitalonlabs.neogold.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kitalonlabs.neogold.core.data.local.entity.DeviceProfileEntity

@Dao
interface DeviceProfileDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: DeviceProfileEntity)

    @Update
    suspend fun update(profile: DeviceProfileEntity)

    @Query("SELECT * FROM device_profile WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveProfile(): DeviceProfileEntity?

    @Query("SELECT * FROM device_profile WHERE id = :id")
    suspend fun getProfileById(id: String): DeviceProfileEntity?

    @Query("UPDATE device_profile SET status = 'SUPERSEDED' WHERE status = 'ACTIVE'")
    suspend fun supersedeActiveProfiles()

    @Query("DELETE FROM device_profile")
    suspend fun deleteAll()
}

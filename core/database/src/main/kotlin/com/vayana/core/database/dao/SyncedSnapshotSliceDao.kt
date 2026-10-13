package com.vayana.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vayana.core.database.entity.SyncedSnapshotSliceEntity

@Dao
interface SyncedSnapshotSliceDao {
    @Query("SELECT * FROM synced_snapshot_slices")
    suspend fun getAll(): List<SyncedSnapshotSliceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(slices: List<SyncedSnapshotSliceEntity>)
}

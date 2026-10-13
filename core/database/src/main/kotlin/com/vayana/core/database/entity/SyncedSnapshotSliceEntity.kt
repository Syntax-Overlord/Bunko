package com.vayana.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The published snapshot slice file, per slice kind, whose records this database already holds: written after
 * a full sync publishes, so a later sync can skip downloading and merging a slice still at that path. Lives in
 * the database, not preferences, so a restored backup brings back marks that match its own records.
 */
@Entity(tableName = "synced_snapshot_slices")
data class SyncedSnapshotSliceEntity(
    @PrimaryKey val sliceKey: String,
    val path: String,
    val dataHash: String,
)

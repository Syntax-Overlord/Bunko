package com.vayana.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A snapshot slice's local change counter; see `SyncSliceVersions`, whose triggers maintain it. */
@Entity(tableName = "sync_slice_versions")
data class SyncSliceVersionEntity(
    @PrimaryKey val sliceKey: String,
    val version: Long,
)

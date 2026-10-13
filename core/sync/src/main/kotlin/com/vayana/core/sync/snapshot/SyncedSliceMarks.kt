package com.vayana.core.sync.snapshot

import com.vayana.core.database.dao.SyncedSnapshotSliceDao

/** The slice files this device last published, for [RemotePortableSnapshotDocument.skipAlreadySynced]. */
suspend fun SyncedSnapshotSliceDao.markedSlices(): List<SyncedSnapshotSlice> =
    getAll().map { SyncedSnapshotSlice(it.sliceKey, it.path, it.dataHash) }

/**
 * Slices no local write has touched since this device published them (their change counter is where that publish
 * recorded it). Together with [RemotePortableSnapshotDocument.isAlreadySynced], the cloud file and this device hold
 * exactly the same records: nothing to export, merge or push. [versions] defaults to the current counters.
 */
suspend fun SyncedSnapshotSliceDao.locallyUnchangedSliceKeys(versions: Map<String, Long>? = null): Set<String> {
    val current = versions ?: getSliceVersions().associate { it.sliceKey to it.version }
    return getAll()
        .filter { mark -> mark.localVersion >= 0 && current[mark.sliceKey] == mark.localVersion }
        .mapTo(mutableSetOf()) { it.sliceKey }
}

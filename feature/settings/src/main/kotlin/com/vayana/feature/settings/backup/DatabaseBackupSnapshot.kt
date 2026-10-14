package com.vayana.feature.settings.backup

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vayana.core.resources.R
import com.vayana.core.resources.failUnless
import java.io.File

/**
 * Capture committed database pages and WAL together while a write transaction prevents new writes or WAL resets.
 * A passive checkpoint may still copy committed WAL pages into the main file; replaying the captured WAL on the
 * private copy produces the same committed state. The shared-memory file contains locks, not database records.
 * Only the local file copy holds the write lock; checkpointing and ZIP/SAF writes use the private snapshot.
 */
internal fun createDatabaseBackupSnapshot(source: SupportSQLiteDatabase, destination: File) {
    val sourceFile = File(checkNotNull(source.path))
    require(sourceFile.canonicalFile != destination.canonicalFile) { "Backup snapshot must be a separate file" }
    check(!source.inTransaction()) { "Cannot snapshot an active database transaction" }
    try {
        source.beginTransactionNonExclusive()
        try {
            sourceFile.copyTo(destination, overwrite = true)
            File(sourceFile.path + "-wal").takeIf { it.isFile }?.copyTo(File(destination.path + "-wal"), overwrite = true)
            source.setTransactionSuccessful()
        } finally {
            source.endTransaction()
        }
        SQLiteDatabase.openDatabase(destination.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { snapshot ->
            snapshot.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                failUnless(cursor.moveToFirst() && cursor.getInt(0) == 0,
                    R.string.settings_error_could_not_save_pending_database_changes)
            }
            // The archive contains one standalone database file, with no dependence on a WAL or shared memory.
            snapshot.disableWriteAheadLogging()
        }
    } catch (failure: Throwable) {
        if (destination.isFile) destination.delete()
        throw failure
    } finally {
        listOf("-wal", "-shm", "-journal").forEach { suffix -> File(destination.path + suffix).delete() }
    }
}

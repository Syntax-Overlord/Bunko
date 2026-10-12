package com.vayana.core.database

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Housekeeping that keeps the database small and its searches fast; safe to run at any time, if rarely needed. */
@Singleton
class DatabaseMaintenance @Inject constructor(private val database: VayanaDatabase) {
    /**
     * Merges each full-text index into one segment and lets SQLite refresh its query-planner statistics. FTS4 adds a
     * segment per batch of writes; merged, a notes search on a library of ~8,000 highlights went from 2.6 ms to 0.1 ms.
     * Merging also drops the entries of re-indexed and removed books, which then sit as free pages until a VACUUM
     * gives them back: the file never shrinks on its own.
     */
    suspend fun optimize() = withContext(Dispatchers.IO) {
        val db = database.openHelper.writableDatabase
        FtsTables.forEach { table -> db.execSQL("INSERT INTO $table($table) VALUES('optimize')") }
        db.query("PRAGMA optimize").close()
        if (db.pragma("freelist_count") * db.pragma("page_size") >= VacuumThresholdBytes) {
            // Needs free disk about the size of the database; without it the space just waits for next week.
            try { db.execSQL("VACUUM") } catch (_: SQLiteException) {}
        }
    }

    private fun SupportSQLiteDatabase.pragma(name: String): Long =
        query("PRAGMA $name").use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private companion object {
        val FtsTables = listOf("books_fts", "annotations_fts", "epub_passages_fts")
        const val VacuumThresholdBytes = 8L * 1024 * 1024
    }
}

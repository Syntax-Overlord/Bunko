package com.vayana.feature.settings.backup

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DatabaseBackupSnapshotTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun capturesCommittedWalRowsAndRemainsStableAfterLiveChanges() {
        openSource().use { helper ->
            val source = helper.writableDatabase
            source.execSQL("INSERT INTO records VALUES (1, 'committed in WAL')")
            val wal = File(source.path + "-wal")
            assertTrue(wal.isFile && wal.length() > 0)
            val snapshot = File(temp.newFolder(), "snapshot.db")

            createDatabaseBackupSnapshot(source, snapshot)
            source.execSQL("UPDATE records SET value = 'later live change'")
            source.query("PRAGMA wal_checkpoint(TRUNCATE)").use { assertTrue(it.moveToFirst()) }

            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                copy.rawQuery("SELECT value FROM records", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("committed in WAL", it.getString(0))
                }
                copy.rawQuery("PRAGMA integrity_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
            }
            assertFalse(File(snapshot.path + "-wal").exists())
            assertFalse(File(snapshot.path + "-shm").exists())
        }
    }

    @Test
    fun snapshotBlocksACompetingWriterAndReleasesTheLock() {
        openSource().use { helper ->
            val source = helper.writableDatabase
            source.execSQL("INSERT INTO records VALUES (1, 'before')")
            val executor = Executors.newSingleThreadExecutor()
            var lockChecked = false
            val checkedSource = object : SupportSQLiteDatabase by source {
                override fun beginTransactionNonExclusive() {
                    source.beginTransactionNonExclusive()
                    executor.submit {
                        SQLiteDatabase.openDatabase(checkNotNull(source.path), null,
                            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { other ->
                            other.rawQuery("PRAGMA busy_timeout = 0", null).use { assertTrue(it.moveToFirst()) }
                            val failure = assertFails { other.execSQL("UPDATE records SET value = 'during copy'") }
                            assertTrue(failure.message.orEmpty().contains("locked", ignoreCase = true))
                        }
                    }.get(10, TimeUnit.SECONDS)
                    lockChecked = true
                }
            }
            try {
                createDatabaseBackupSnapshot(checkedSource, File(temp.newFolder(), "snapshot.db"))
                assertTrue(lockChecked)
                assertFalse(source.inTransaction())
                source.execSQL("UPDATE records SET value = 'after copy'")
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun failedCopyReleasesTheTransactionAndDeletesPartialFiles() {
        openSource().use { helper ->
            val source = helper.writableDatabase
            source.execSQL("INSERT INTO records VALUES (1, 'before')")
            val snapshot = File(temp.newFile(), "snapshot.db") // the parent is a file: copying must fail
            assertFails { createDatabaseBackupSnapshot(source, snapshot) }
            assertFalse(source.inTransaction())
            assertFalse(File(snapshot.path + "-wal").exists())
            source.execSQL("UPDATE records SET value = 'after failure'")
        }
    }

    @Test
    fun rollbackJournalDatabaseAlsoProducesAStandaloneSnapshot() {
        openSource(wal = false).use { helper ->
            val source = helper.writableDatabase
            source.query("PRAGMA journal_mode").use {
                assertTrue(it.moveToFirst())
                assertFalse(it.getString(0).equals("wal", ignoreCase = true))
            }
            source.execSQL("INSERT INTO records VALUES (1, 'rollback journal')")
            val snapshot = File(temp.newFolder(), "snapshot.db")
            createDatabaseBackupSnapshot(source, snapshot)

            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                copy.rawQuery("SELECT value FROM records", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("rollback journal", it.getString(0))
                }
            }
        }
    }

    private fun openSource(wal: Boolean = true): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(File(temp.newFolder(), "live.db").absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE records(id INTEGER PRIMARY KEY, value TEXT)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
    ).apply { setWriteAheadLoggingEnabled(wal) }
}

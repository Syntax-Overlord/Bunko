package com.vayana.feature.settings.backup

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
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
    fun realRoomBackupOmitsIndexPreservesNotesAndCanRebuild() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = com.vayana.core.database.di.DatabaseModule.provideVayanaDatabase(context)
        val snapshot = File(temp.newFolder(), "snapshot.db")
        try {
            val bookId = db.bookDao().insert(com.vayana.core.database.entity.BookEntity(
                title = "Backup book", author = null, series = null, seriesNumber = null, description = null, coverPath = null,
                filePath = "books/book.epub", format = "EPUB", fileHash = "hash", lastLocator = "page:2",
                readingPercent = 0.5f, rating = 4f, groupId = null, isDeleted = false, wordCount = null,
                pageEstimate = null, createdAt = 1, updatedAt = 1, lastReadAt = 1,
            ))
            val noteId = db.annotationDao().insert(com.vayana.core.database.entity.AnnotationEntity(
                bookId = bookId, type = "NOTE", colorKey = "yellow", locator = "page:2", chapterTitle = null,
                chapterHref = null, selectedText = "", readerNote = "My reflection", createdAt = 1, updatedAt = 1,
            ))
            val shelfId = db.shelfDao().insert(com.vayana.core.database.entity.ShelfEntity(name = "Keep", createdAt = 1, updatedAt = 1))
            db.shelfDao().addBookToShelf(com.vayana.core.database.entity.BookShelfCrossRefEntity(bookId, shelfId))
            val source = db.openHelper.writableDatabase
            source.execSQL("INSERT INTO epub_chapters (id, bookId, fileHash, href, title, compressedText) VALUES (1, $bookId, 'hash', 'one.xhtml', 'One', zeroblob(262144))")
            source.execSQL("INSERT INTO epub_passages VALUES (1, 1, 0, 5)")
            source.execSQL("INSERT INTO epub_passages_fts_content VALUES (1, 'river boat')")
            source.execSQL("INSERT INTO epub_passages_fts(docid, text) VALUES (1, 'river boat')")
            // Personal records and Room's identity must survive.
            val identity = source.query("SELECT identity_hash FROM room_master_table").use { it.moveToFirst(); it.getString(0) }
            val originalBytes = source.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) } *
                source.query("PRAGMA page_size").use { it.moveToFirst(); it.getLong(0) }
            createDatabaseBackupSnapshot(source, snapshot)
            assertTrue(snapshot.length() < originalBytes)
            assertFalse(File(snapshot.path + "-wal").exists())
            assertFalse(File(snapshot.path + "-shm").exists())
            assertEquals(1, source.query("SELECT count(*) FROM epub_chapters").use { it.moveToFirst(); it.getInt(0) })
            val restored = androidx.room.Room.databaseBuilder(context, com.vayana.core.database.VayanaDatabase::class.java, snapshot.path).build()
            try {
                val copy = restored.openHelper.writableDatabase
                assertEquals(identity, copy.query("SELECT identity_hash FROM room_master_table").use { it.moveToFirst(); it.getString(0) })
                assertEquals("My reflection", copy.query("SELECT readerNote FROM annotations WHERE id = $noteId").use { it.moveToFirst(); it.getString(0) })
                assertEquals("page:2", copy.query("SELECT lastLocator FROM books WHERE id = $bookId").use { it.moveToFirst(); it.getString(0) })
                assertEquals(4f, copy.query("SELECT rating FROM books WHERE id = $bookId").use { it.moveToFirst(); it.getFloat(0) })
                assertEquals(1, copy.query("SELECT count(*) FROM book_shelf_cross_ref WHERE bookId = $bookId AND shelfId = $shelfId").use { it.moveToFirst(); it.getInt(0) })
                for (table in listOf("epub_chapters", "epub_passages", "epub_passages_fts_content")) {
                    assertEquals(0, copy.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getInt(0) })
                }
                assertEquals(0, copy.query("SELECT count(*) FROM epub_passages_fts WHERE epub_passages_fts MATCH 'river'").use { it.moveToFirst(); it.getInt(0) })
                restored.epubPassageDao().replace(bookId, "hash", sequenceOf(com.vayana.core.database.dao.EpubChapterText("one.xhtml", "One", "river boat", listOf(0..9))))
                assertEquals(1, restored.epubPassageDao().search("river*", 10).first().size)
                assertEquals("ok", copy.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) })
            } finally { restored.close() }
        } finally {
            db.close()
            context.deleteDatabase("vayana.db")
        }
    }

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

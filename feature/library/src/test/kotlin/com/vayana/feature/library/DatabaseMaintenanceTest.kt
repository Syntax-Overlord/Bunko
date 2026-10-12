package com.vayana.feature.library

import androidx.room.Room
import com.vayana.core.database.DatabaseMaintenance
import com.vayana.core.database.VayanaDatabase
import com.vayana.core.database.dao.EpubChapterText
import com.vayana.core.database.entity.BookEntity
import com.vayana.core.database.search.EpubPassageIndexCallback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DatabaseMaintenanceTest {
    private lateinit var db: VayanaDatabase
    @BeforeTest fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VayanaDatabase::class.java)
            .addCallback(EpubPassageIndexCallback).allowMainThreadQueries().build()
    }
    @AfterTest fun close() = db.close()

    private suspend fun epub(title: String): Long = db.bookDao().insert(BookEntity(title = title, author = null,
        series = null, seriesNumber = null, description = null, coverPath = null, filePath = "books/$title",
        format = "EPUB", fileHash = "hash-$title", lastLocator = null, readingPercent = 0f, rating = 0f, groupId = null,
        isDeleted = false, wordCount = null, pageEstimate = null, createdAt = 1, updatedAt = 1, lastReadAt = null))

    private fun segments(): Long =
        db.openHelper.readableDatabase.query("SELECT count(*) FROM epub_passages_fts_segdir").use { it.moveToFirst(); it.getLong(0) }

    @Test fun optimizeMergesTheEpubIndexAndKeepsSearchWorking() = runBlocking {
        val dao = db.epubPassageDao()
        // Each book (and each re-index) writes its own index segments.
        listOf("One", "Two", "Three").forEach { title ->
            dao.replace(epub(title), "hash-$title", sequenceOf(EpubChapterText("c.xhtml", "Chapter", "a $title quokka", listOf(0..(title.length + 8)))))
        }
        val removed = epub("Removed")
        dao.replace(removed, "hash-Removed", sequenceOf(EpubChapterText("c.xhtml", "Chapter", "gone wombat", listOf(0..10))))
        dao.deleteForBook(removed)
        assertTrue(segments() > 1)

        DatabaseMaintenance(db).optimize()

        assertEquals(1L, segments())
        assertEquals(3, dao.search("quokka", 10).first().size)
        assertTrue(dao.search("wombat", 10).first().isEmpty())
    }
}

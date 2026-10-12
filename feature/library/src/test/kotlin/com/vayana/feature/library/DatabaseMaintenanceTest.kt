package com.vayana.feature.library

import androidx.room.Room
import com.vayana.core.database.DatabaseMaintenance
import com.vayana.core.database.VayanaDatabase
import com.vayana.core.database.entity.BookEntity
import com.vayana.core.database.entity.EpubPassageEntity
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
            .allowMainThreadQueries().build()
    }
    @AfterTest fun close() = db.close()

    private suspend fun epub(title: String): Long = db.bookDao().insert(BookEntity(title = title, author = null,
        series = null, seriesNumber = null, description = null, coverPath = null, filePath = "books/$title",
        format = "EPUB", fileHash = "hash-$title", lastLocator = null, readingPercent = 0f, rating = 0f, groupId = null,
        isDeleted = false, wordCount = null, pageEstimate = null, createdAt = 1, updatedAt = 1, lastReadAt = null))

    private fun pragma(name: String): Long =
        db.openHelper.readableDatabase.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }

    @Test fun optimizeGivesBackSpaceOfARemovedBooksPassagesAndKeepsSearchWorking() = runBlocking {
        val removed = epub("Removed")
        val kept = epub("Kept")
        val filler = "lorem ipsum dolor sit amet ".repeat(66)
        db.epubPassageDao().insert(List(6_000) { index ->
            EpubPassageEntity(bookId = removed, fileHash = "hash-Removed", chapterHref = "c$index.xhtml",
                chapterTitle = "Chapter", text = "$filler word$index")
        })
        db.epubPassageDao().insert(listOf(EpubPassageEntity(bookId = kept, fileHash = "hash-Kept",
            chapterHref = "one.xhtml", chapterTitle = "One", text = "a quokka smiles")))
        db.epubPassageDao().deleteForBook(removed)
        assertTrue(pragma("freelist_count") * pragma("page_size") >= 8L * 1024 * 1024)

        DatabaseMaintenance(db).optimize()

        assertEquals(0L, pragma("freelist_count"))
        assertEquals(listOf("a quokka smiles"), db.epubPassageDao().search("quokka", 10).first().map { it.text })
        assertTrue(db.epubPassageDao().search("lorem", 10).first().isEmpty())
    }
}

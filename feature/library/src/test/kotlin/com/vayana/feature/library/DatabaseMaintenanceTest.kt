package com.vayana.feature.library

import androidx.room.Room
import com.vayana.core.database.DatabaseMaintenance
import com.vayana.core.database.VayanaDatabase
import com.vayana.core.database.dao.EpubChapterText
import com.vayana.core.database.entity.BookEntity
import com.vayana.core.database.search.EpubPassageIndexCallback
import kotlin.random.Random
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

    private fun pragma(name: String): Long =
        db.openHelper.readableDatabase.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }

    @Test fun optimizeGivesBackSpaceOfARemovedBooksPassagesAndKeepsSearchWorking() = runBlocking {
        val removed = epub("Removed")
        val kept = epub("Kept")
        // Varied words, so neither the compressed text nor the index stays small.
        val random = Random(7)
        val chapters = List(60) { index ->
            val text = List(12_000) { (1..random.nextInt(4, 9)).map { 'a' + random.nextInt(26) }.joinToString("") }
                .joinToString(" ")
            EpubChapterText("c$index.xhtml", "Chapter", text, text.indices.chunked(1800) { it.first()..it.last() })
        }
        val dao = db.epubPassageDao()
        dao.replace(removed, "hash-Removed", chapters.asSequence())
        dao.replace(kept, "hash-Kept", sequenceOf(EpubChapterText("one.xhtml", "One", "a quokka smiles", listOf(0..14))))
        dao.deleteForBook(removed)
        assertTrue(pragma("freelist_count") * pragma("page_size") >= 8L * 1024 * 1024)

        DatabaseMaintenance(db).optimize()

        assertEquals(0L, pragma("freelist_count"))
        val hit = dao.search("quokka", 10).first().single()
        assertEquals("a quokka smiles", dao.chapterTexts(listOf(hit.chapterId)).getValue(hit.chapterId).substring(hit.startOffset, hit.endOffset))
    }
}

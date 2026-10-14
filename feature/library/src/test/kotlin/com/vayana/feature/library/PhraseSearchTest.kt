package com.vayana.feature.library

import com.vayana.core.database.VayanaDatabase
import com.vayana.core.database.di.DatabaseModule
import com.vayana.core.database.dao.EpubChapterText
import com.vayana.core.database.entity.BookEntity
import com.vayana.core.database.entity.AnnotationEntity
import com.vayana.core.database.repository.BookRepositoryImpl
import com.vayana.core.database.repository.AnnotationRepositoryImpl
import com.vayana.core.database.search.ftsSearchMatch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PhraseSearchTest {
    private lateinit var db: VayanaDatabase
    @BeforeTest fun setup() {
        db = DatabaseModule.provideVayanaDatabase(RuntimeEnvironment.getApplication())
    }
    @AfterTest fun close() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase("vayana.db")
    }

    @Test fun bookSearchRequiresPhraseOrderAndWholeWords() = runBlocking {
        val expected = book("River boat")
        val others = listOf("River slow boat", "Boat river", "River boating").map { book(it) }
        val books = BookRepositoryImpl(db, db.bookDao(), db.bookAliasDao(), db.tombstoneDao(),
            db.readingSessionDao(), db.vocabularyCardDao(), db.pendingCloudDeletionDao())
        assertEquals(listOf(expected), books.observeSearchIds("\"river boat\"", 10).first())
        assertEquals((others + expected).toSet(), books.observeSearchIds("river boa", 10).first().toSet())
    }

    @Test fun noteSearchRequiresAnAdjacentPhrase() = runBlocking {
        val id = book("Notes")
        val expected = annotation(id, "moon light")
        annotation(id, "moon then light")
        annotation(id, "light moon")
        val notes = AnnotationRepositoryImpl(db, db.annotationDao(), db.bookDao(), db.bookAliasDao(), db.tombstoneDao())
        assertEquals(listOf(expected), notes.observeSearch("\"moon light\"", 10).first().map { it.id })
    }

    @Test fun epubPassagesUseTheSamePhraseRules() = runBlocking {
        val id = book("EPUB", "EPUB")
        val texts = listOf("river boat at dawn", "river slow boat at dawn", "boat river at dawn", "river boating at dawn")
        db.epubPassageDao().replace(id, "hash-EPUB", texts.asSequence().mapIndexed { index, text ->
            EpubChapterText("$index.xhtml", "Chapter $index", text, listOf(text.indices))
        })
        val hits = db.epubPassageDao().search(requireNotNull(ftsSearchMatch("\"river boat\" dawn")), 10).first()
        assertEquals(listOf("0.xhtml"), hits.map { it.chapterHref })
    }

    @Test fun scopesApplyBeforeLimitsToBooksNotesAndContent() = runBlocking {
        val outside = book("river boat outside", "EPUB")
        val target = book("river boat target", "EPUB")
        val shelf = db.shelfDao().insert(com.vayana.core.database.entity.ShelfEntity(name = "Favorites", createdAt = 1, updatedAt = 1))
        db.shelfDao().addBookToShelf(com.vayana.core.database.entity.BookShelfCrossRefEntity(target, shelf))
        val targetNote = annotation(target, "river boat")
        repeat(5) { annotation(outside, "river boat") }
        for (id in listOf(outside, target)) {
            val title = if (id == outside) "river boat outside" else "river boat target"
            db.epubPassageDao().replace(id, "hash-$title", sequenceOf(EpubChapterText("1.xhtml", "Chapter", "river boat", listOf(0..9))))
        }
        val match = requireNotNull(ftsSearchMatch("\"river boat\""))
        assertEquals(listOf(outside), db.bookDao().observeSearchIds(match, 1).first())
        for ((bookId, shelfId) in listOf(target to null, null to shelf, target to shelf)) {
            assertEquals(listOf(target), db.bookDao().observeSearchIds(match, 1, bookId, shelfId).first())
            assertEquals(listOf(targetNote), db.annotationDao().observeSearch(match, 1, bookId, shelfId).first().map { it.id })
            assertEquals(listOf(target), db.epubPassageDao().search(match, 1, bookId, shelfId).first().map { it.bookId })
        }
        assertTrue(db.bookDao().observeSearchIds(match, 1, outside, shelf).first().isEmpty())
        assertTrue(db.annotationDao().observeSearch(match, 1, outside, shelf).first().isEmpty())
        assertTrue(db.epubPassageDao().search(match, 1, outside, shelf).first().isEmpty())
        val updates = kotlinx.coroutines.channels.Channel<List<com.vayana.core.database.dao.EpubPassageHit>>()
        val collector = launch {
            db.epubPassageDao().search(match, 1, shelfId = shelf).collect { updates.send(it) }
        }
        try {
            assertEquals(listOf(target), kotlinx.coroutines.withTimeout(10_000) { updates.receive() }.map { it.bookId })
            db.shelfDao().removeBookFromShelf(target, shelf)
            assertTrue(kotlinx.coroutines.withTimeout(10_000) { updates.receive() }.isEmpty())
        } finally { collector.cancel(); updates.cancel() }
    }

    private suspend fun book(title: String, format: String = "PHYSICAL") = db.bookDao().insert(BookEntity(
        title = title, author = null, series = null, seriesNumber = null, description = null, coverPath = null,
        filePath = "books/$title", format = format, fileHash = "hash-$title", lastLocator = null,
        readingPercent = 0f, rating = 0f, groupId = null, isDeleted = false, wordCount = null,
        pageEstimate = null, createdAt = 1, updatedAt = 1, lastReadAt = null,
    ))

    private suspend fun annotation(bookId: Long, text: String) = db.annotationDao().insert(AnnotationEntity(
        bookId = bookId, type = "HIGHLIGHT", colorKey = "yellow", locator = "page:1", chapterTitle = null,
        chapterHref = null, selectedText = text, readerNote = null, createdAt = 1, updatedAt = 1,
    ))
}

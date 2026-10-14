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

package com.vayana.feature.reader

import com.vayana.core.database.VayanaDatabase
import com.vayana.core.database.di.DatabaseModule
import com.vayana.core.database.model.Annotation
import com.vayana.core.database.model.AnnotationType
import com.vayana.core.database.model.BookFormat
import com.vayana.core.database.repository.AnnotationRepository
import com.vayana.core.database.repository.AnnotationRepositoryImpl
import com.vayana.core.database.repository.BookRepositoryImpl
import com.vayana.reader.api.Locator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BookFinishedReflectionTest {
    private lateinit var db: VayanaDatabase
    private lateinit var books: BookRepositoryImpl
    private lateinit var notes: AnnotationRepositoryImpl
    private var id = 0L

    @BeforeTest fun setup() = runBlocking {
        db = DatabaseModule.provideVayanaDatabase(RuntimeEnvironment.getApplication())
        books = BookRepositoryImpl(db, db.bookDao(), db.bookAliasDao(), db.tombstoneDao(),
            db.readingSessionDao(), db.vocabularyCardDao(), db.pendingCloudDeletionDao())
        notes = AnnotationRepositoryImpl(db, db.annotationDao(), db.bookDao(), db.bookAliasDao(), db.tombstoneDao())
        id = books.insertOfflineBook("Reflection", "Author", BookFormat.PHYSICAL, null, null).id
    }
    @AfterTest fun close() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase("vayana.db")
    }

    @Test fun reflectionIsAnOrdinaryJournalNoteAtTheCapturedPosition() = runBlocking {
        db.fullSyncStateDao().markSatisfied()
        val locator = Locator(cfi = "epubcfi(/6/2)", progression = 1f, href = "last.xhtml", chapterTitle = "Final chapter")
        finishBookWithReflection(db, books, notes, id, 4f, "  Café — 雪 stayed with me.  ", locator)

        val book = assertNotNull(books.getById(id))
        assertNotNull(book.finishedReadingAt)
        assertEquals(4f, book.rating)
        val note = notes.observeForBook(id).first().single()
        assertEquals(AnnotationType.NOTE, note.type)
        assertEquals("journal", note.colorKey)
        assertEquals(locator.cfi, note.locator)
        assertEquals("last.xhtml", note.chapterHref)
        assertEquals("Final chapter", note.chapterTitle)
        assertEquals("Café — 雪 stayed with me.", note.readerNote)
        assertEquals("", note.selectedText)
        assertTrue(note.syncId.isNotBlank())
        assertTrue(db.fullSyncStateDao().observeRequired().first())
    }

    @Test fun blankReflectionNeedsNoLocatorAndPreservesTheExistingRating() = runBlocking {
        books.updateRating(id, 3f)
        finishBookWithReflection(db, books, notes, id, null, " \n ", null)
        assertEquals(3f, books.getById(id)?.rating)
        assertNotNull(books.getById(id)?.finishedReadingAt)
        assertTrue(notes.observeForBook(id).first().isEmpty())
    }

    @Test fun reflectionUsesLastSavedPositionWhenTheCurrentPositionIsUnavailable() = runBlocking {
        val row = assertNotNull(db.bookDao().getById(id))
        db.bookDao().update(row.copy(lastLocator = "pdf:page:12"))
        finishBookWithReflection(db, books, notes, id, null, "Remember this ending", null)
        assertEquals("pdf:page:12", notes.observeForBook(id).first().single().locator)
    }

    @Test fun journalLengthUsesTheExistingFourThousandCharacterLimit() = runBlocking {
        finishBookWithReflection(db, books, notes, id, null, "x".repeat(5000), endPosition())
        assertEquals(4000, notes.observeForBook(id).first().single().readerNote?.length)
    }

    @Test fun missingPositionDoesNotPartiallySaveCompletionOrRating() = runBlocking {
        assertFailsWith<IllegalStateException> {
            finishBookWithReflection(db, books, notes, id, 4f, "My reflection", null)
        }
        assertNull(books.getById(id)?.finishedReadingAt)
        assertEquals(0f, books.getById(id)?.rating)
    }

    @Test fun failedNoteWriteRollsBackCompletionAndRatingForASafeRetry() = runBlocking {
        val failingNotes = object : AnnotationRepository by notes {
            override suspend fun create(bookId: Long, type: AnnotationType, colorKey: String, locator: String,
                chapterTitle: String?, chapterHref: String?, selectedText: String, readerNote: String?): Annotation {
                throw java.io.IOException("Note write failed")
            }
        }
        assertFailsWith<java.io.IOException> {
            finishBookWithReflection(db, books, failingNotes, id, 4f, "My reflection", endPosition())
        }
        assertNull(books.getById(id)?.finishedReadingAt)
        assertEquals(0f, books.getById(id)?.rating)
        assertTrue(notes.observeForBook(id).first().isEmpty())
        finishBookWithReflection(db, books, notes, id, 4f, "My reflection", endPosition())
        assertEquals(1, notes.observeForBook(id).first().size)
    }

    private fun endPosition() = Locator(cfi = "epubcfi(/6/2)", href = null, chapterTitle = null, progression = 1f)
}

package com.vayana.feature.notes

import com.vayana.core.database.model.*
import com.vayana.core.common.Hashing
import kotlin.test.*

class NotebookExportInputsTest {
    @Test fun oneEditedNotebookGeneratesOnlyOneOfTwoHundredVerifiedNotebooks() {
        val cache = NotebookExportHashes()
        val library = (1L..200L).map { book().copy(id = it, syncId = "book-$it") }
        val original = listOf(note())
        library.forEach { cache.recordInputs(automaticNotebookFileName(it.syncId), it, original.map { note -> note.copy(bookId = it.id) }) }
        var generated = 0
        for (book in library) {
            val notes = if (book.id == 75L) listOf(note().copy(bookId = book.id, readerNote = "Edited reflection")) else original.map { it.copy(bookId = book.id) }
            val name = automaticNotebookFileName(book.syncId)
            if (!cache.inputsUnchanged(name, book, notes)) {
                generated++
                val content = highlightsMarkdown(book, notes)
                cache.record(name, Hashing.sha256(content.toByteArray()))
                cache.recordInputs(name, book, notes)
            }
        }
        assertEquals(1, generated)
        assertTrue(cache.inputsUnchanged(automaticNotebookFileName("book-75"), library[74], listOf(note().copy(bookId = 75, readerNote = "Edited reflection"))))
    }

    @Test fun metadataAndNoteChangesInvalidateButReadingProgressDoesNot() {
        val cache = NotebookExportHashes()
        val source = book()
        val notes = listOf(note())
        cache.recordInputs("book.md", source, notes)
        assertTrue(cache.inputsUnchanged("book.md", source.copy(readingPercent = 0.8f, rating = 5f, lastReadAt = 10), notes))
        for (edited in listOf(source.copy(title = "New title"), source.copy(author = "New author"),
            source.copy(tagsCsv = "new tag"), source.copy(syncId = "new-id"))) {
            assertFalse(cache.inputsUnchanged("book.md", edited, notes))
        }
        assertFalse(cache.inputsUnchanged("book.md", source, emptyList())) // last note deleted
        assertFalse(cache.inputsUnchanged("book.md", source, listOf(note().copy(readerNote = "Edited"))))
        assertFalse(cache.inputsUnchanged("book.md", source, notes + note().copy(id = 2)))
        assertFalse(cache.inputsUnchanged("book.md", source, listOf(note().copy(isDeleted = true))))
    }

    @Test fun failedReconciliationAndMissingFilesCannotReuseVerifiedInputs() {
        val cache = NotebookExportHashes()
        cache.record("book.md", "hash")
        cache.recordInputs("book.md", book(), listOf(note()))
        var checked = false
        assertFailsWith<IllegalArgumentException> {
            cache.isUnchanged("book.md", "hash", reconcile = true) { checked = true; throw IllegalArgumentException("Foreign file") }
        }
        assertTrue(checked)
        assertFalse(cache.inputsUnchanged("book.md", book(), listOf(note())))
        repeat(2) { assertFalse(cache.inputsUnchanged("book.md", book(), listOf(note()))) } // retry until recorded
        cache.recordInputs("book.md", book(), listOf(note()))
        cache.forget("book.md")
        assertFalse(cache.inputsUnchanged("book.md", book(), listOf(note())))
    }

    @Test fun successfulReconciliationClearsOldInputsBeforeComparisonAndCanRecordNewOnes() {
        val cache = NotebookExportHashes()
        cache.record("book.md", "hash")
        cache.recordInputs("book.md", book(), listOf(note()))
        assertTrue(cache.isUnchanged("book.md", "hash", reconcile = true) {
            assertFalse(cache.inputsUnchanged("book.md", book(), listOf(note())))
            true
        })
        cache.recordInputs("book.md", book(), listOf(note()))
        assertTrue(cache.inputsUnchanged("book.md", book(), listOf(note())))
    }

    @Test fun removedBooksAndFolderChangesReleaseInputsAndInputListsAreSnapshots() {
        val cache = NotebookExportHashes()
        val mutableNotes = mutableListOf(note())
        cache.recordInputs("kept.md", book(), mutableNotes)
        cache.recordInputs("removed.md", book(), mutableNotes)
        mutableNotes.clear()
        assertFalse(cache.inputsUnchanged("kept.md", book(), mutableNotes))
        cache.retain(setOf("kept.md"))
        assertTrue(cache.inputsUnchanged("kept.md", book(), listOf(note())))
        assertFalse(cache.inputsUnchanged("removed.md", book(), listOf(note())))
        cache.clear()
        assertFalse(cache.inputsUnchanged("kept.md", book(), listOf(note())))
    }

    private fun book() = Book(
        id = 1, syncId = "book", title = "Book", author = "Author", series = null, seriesNumber = null,
        description = null, tagsCsv = "fiction", coverPath = null, filePath = "", fileAvailability = BookFileAvailability.LOCAL,
        format = BookFormat.EPUB, fileHash = "", readingPercent = 0f, rating = 0f, createdAt = 0, updatedAt = 0,
        lastReadAt = null, lastLocator = null,
    )
    private fun note() = Annotation(
        id = 1, bookId = 1, type = AnnotationType.NOTE, colorKey = "journal", locator = "page:1", chapterTitle = "One",
        chapterHref = null, selectedText = "", readerNote = "Reflection", createdAt = 0, updatedAt = 0,
    )
}

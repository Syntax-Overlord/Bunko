package com.vayana.feature.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NotebookExportHashesTest {
    @Test
    fun editingOneNotebookChecksOnlyThatFileDuringAutomaticRefresh() {
        val hashes = NotebookExportHashes()
        val exported = (1..200).associate { "book-$it.md" to "hash-$it" }
        var reads = 0
        exported.forEach { (name, hash) ->
            assertTrue(hashes.isUnchanged(name, hash, reconcile = false) { reads++; true })
        }
        assertEquals(200, reads)

        reads = 0
        exported.forEach { (name, hash) ->
            val current = if (name == "book-1.md") "edited-hash" else hash
            val unchanged = hashes.isUnchanged(name, current, reconcile = false) { reads++; false }
            if (!unchanged) hashes.record(name, current)
        }
        assertEquals(1, reads)
    }

    @Test
    fun fullReconciliationReadsEvenCachedFilesAndInvalidatesFailedChecks() {
        val hashes = NotebookExportHashes()
        hashes.record("book.md", "hash")
        assertFailsWith<IllegalArgumentException> {
            hashes.isUnchanged("book.md", "hash", reconcile = true) { errorForeignFile() }
        }
        var read = false
        assertFalse(hashes.isUnchanged("book.md", "hash", reconcile = false) { read = true; false })
        assertTrue(read)
    }

    @Test
    fun failedWriteIsNotRememberedAndMissingFilesAreRechecked() {
        val hashes = NotebookExportHashes()
        hashes.record("book.md", "old")
        repeat(2) {
            assertFalse(hashes.isUnchanged("book.md", "new", reconcile = false) { false })
        }
        hashes.record("book.md", "new")
        hashes.forget("book.md")
        assertFalse(hashes.isUnchanged("book.md", "new", reconcile = false) { false })
    }

    @Test
    fun changingOrDisablingTheFolderClearsRememberedHashes() {
        val hashes = NotebookExportHashes()
        hashes.record("book.md", "hash")
        hashes.clear()
        assertFalse(hashes.isUnchanged("book.md", "hash", reconcile = false) { false })
    }

    private fun errorForeignFile(): Boolean = throw IllegalArgumentException("Foreign notebook")
}

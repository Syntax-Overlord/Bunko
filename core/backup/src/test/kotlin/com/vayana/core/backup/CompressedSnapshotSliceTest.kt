package com.vayana.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

class CompressedSnapshotSliceTest {

    @Test
    fun annotationSliceAcceptsZipPath() {
        val compressedPath = "vayana/snapshot-slices/1000/annotations.zip"
        val manifest = """{"slices":{"annotations":"$compressedPath"}}"""

        assertEquals(compressedPath, portableSnapshotSlicePaths(manifest)["annotations"])
    }

    @Test
    fun everySliceAndTheBooksSliceAcceptZipAndJsonPaths() {
        val manifest = JSONObject().put(
            "slices",
            JSONObject()
                .put("readingSessions", "vayana/snapshot-slices/1000/reading-sessions.zip")
                .put("wordLookupCounters", "vayana/snapshot-slices/1000/word-lookup-counters.zip")
                .put("books", "vayana/snapshot-slices/1000/books.zip")
                .put("tombstones", "vayana/snapshot-slices/1000/tombstones.json"),
        ).toString()

        assertEquals(
            mapOf(
                "readingSessions" to "vayana/snapshot-slices/1000/reading-sessions.zip",
                "wordLookupCounters" to "vayana/snapshot-slices/1000/word-lookup-counters.zip",
                "books" to "vayana/snapshot-slices/1000/books.zip",
                "tombstones" to "vayana/snapshot-slices/1000/tombstones.json",
            ),
            portableSnapshotSlicePaths(manifest),
        )
    }

    @Test
    fun aZipPathNamedForAnotherSliceIsIgnored() {
        val manifest = """{"slices":{"books":"vayana/snapshot-slices/1000/reading-sessions.zip"}}"""

        assertTrue(portableSnapshotSlicePaths(manifest).isEmpty())
    }

    @Test
    fun unsafeCompressedPathsAreIgnored() {
        val manifest = """{"slices":{"annotations":"../annotations.zip"}}"""

        assertTrue(portableSnapshotSlicePaths(manifest).isEmpty())
    }
}

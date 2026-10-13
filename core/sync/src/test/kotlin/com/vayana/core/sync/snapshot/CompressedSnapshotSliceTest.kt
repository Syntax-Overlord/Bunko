package com.vayana.core.sync.snapshot

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CompressedSnapshotSliceTest {

    @Test
    fun annotationJsonZipRoundTripsAndActuallyCompresses() {
        val json = """{"annotations":[${"{\"selectedText\":\"repeated quote text\"},".repeat(200)}{}]}"""

        val zipped = zipSnapshotJson(json)

        assertTrue(zipped.size < json.toByteArray().size)
        assertEquals(json, unzipSnapshotJson(zipped))
        assertContentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()), zipped.copyOf(2))
    }

    @Test
    fun remoteDocumentLoadsCompressedAnnotationPath() = runBlocking {
        val loadedPaths = mutableListOf<String>()
        val compressedPath = "vayana/snapshot-slices/1000/annotations.zip"
        val document = remotePortableSnapshotDocumentFrom(
            latestJson = "{}",
            sha = "sha",
            slicePaths = mapOf("annotations" to compressedPath),
            loadSlice = { path -> loadedPaths += path; "{\"annotations\":[]}" },
        )

        assertEquals("{\"annotations\":[]}", document.jsonFor(RemotePortableSnapshotSlice.Annotations))
        assertEquals(listOf(compressedPath), loadedPaths)
        assertEquals(compressedPath, document.slicePathOrNull("annotations"))
    }


}

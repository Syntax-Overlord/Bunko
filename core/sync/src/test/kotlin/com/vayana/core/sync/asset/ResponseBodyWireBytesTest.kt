package com.vayana.core.sync.asset

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseBodyWireBytesTest {
    private val json = """{"books":[${"{\"title\":\"A book with a long repeated title\"},".repeat(300)}{}]}""".toByteArray()

    @Test
    fun aGzippedBodyIsUnzippedButCountedAtItsCompressedSize() {
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json) } }.toByteArray()

        val (body, wire) = readResponseBody(ByteArrayInputStream(compressed), gzip = true, maxBytes = 1_000_000)

        assertContentEquals(json, body)
        assertEquals(compressed.size.toLong(), wire)
        assertTrue(wire < json.size / 5, "repetitive JSON should compress well")
    }

    @Test
    fun aPlainBodyCountsItsOwnSize() {
        val (body, wire) = readResponseBody(ByteArrayInputStream(json), gzip = false, maxBytes = 1_000_000)

        assertContentEquals(json, body)
        assertEquals(json.size.toLong(), wire)
    }

    @Test
    fun anEmptyBodyMarkedGzipIsEmpty() {
        val (body, wire) = readResponseBody(ByteArrayInputStream(ByteArray(0)), gzip = true, maxBytes = 10)

        assertEquals(0, body.size)
        assertEquals(0L, wire)
    }
}

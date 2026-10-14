package com.vayana.core.common

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.*

class HashingTest {
    @Test fun preservesStandardSha256DigestsAndLowercaseEncoding() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256(byteArrayOf()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256("abc".toByteArray()))
        val bytes = ByteArray(256) { it.toByte() }
        val original = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(original, Hashing.sha256(bytes))
    }

    @Test fun streamedBinaryDataMatchesByteArrayHashAndClosesInput() {
        val bytes = ByteArray(70_001) { it.toByte() }
        var closed = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(Hashing.sha256(bytes), Hashing.sha256(input))
        assertTrue(closed)
    }
}

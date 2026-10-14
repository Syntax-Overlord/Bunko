package com.vayana.reader.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookResourceIsolationTest {
    @Test
    fun `book documents disallow scripts while retaining normal resource loading`() {
        assertEquals("no-store", BookResourceHeaders["Cache-Control"])
        assertEquals("script-src 'none'; object-src 'none'", BookResourceHeaders["Content-Security-Policy"])
    }

    @Test
    fun `valid reader events reach the handler`() {
        var fraction = 0.0
        withBridgeEventPayload("""{"fraction":0.5}""") { fraction = it.getDouble("fraction") }
        assertEquals(0.5, fraction)
    }

    @Test
    fun `malformed JSON does not reach the handler or throw`() {
        var handled = false
        for (json in listOf("{", "[]", "null", "")) {
            withBridgeEventPayload(json) { handled = true }
        }
        assertFalse(handled)
    }

    @Test
    fun `invalid fields cannot throw out of bridge event dispatch`() {
        var entered = false
        withBridgeEventPayload("""{"sentences":[null]}""") {
            entered = true
            it.getJSONArray("sentences").getJSONObject(0)
        }
        assertTrue(entered)
    }
}

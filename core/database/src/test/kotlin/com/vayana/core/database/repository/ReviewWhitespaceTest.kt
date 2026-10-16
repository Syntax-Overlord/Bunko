package com.vayana.core.database.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewWhitespaceTest {
    @Test fun `literal holds exactly the chars Kotlin treats as whitespace`() {
        val expected = buildString { for (code in 0..0xffff) if (code.toChar().isWhitespace()) append(code.toChar()) }
        assertEquals(expected, ReviewWhitespace)
    }
}

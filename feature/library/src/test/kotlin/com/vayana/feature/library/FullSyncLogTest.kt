package com.vayana.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals

class FullSyncLogTest {
    @Test
    fun phasesAreTimedFromTheEndOfThePreviousPhase() {
        var now = 0L
        val log = FullSyncLog("Full sync") { now }
        log.note("network=wifi")
        now = 5_000_000
        log.phase("Pull", "remoteSha=abc")
        now = 12_000_000
        log.phase("Upload")

        assertEquals("network=wifi\nPull: 5 ms | remoteSha=abc\nUpload: 7 ms", log.text())
        assertEquals(12, log.elapsedMillis)
    }
}

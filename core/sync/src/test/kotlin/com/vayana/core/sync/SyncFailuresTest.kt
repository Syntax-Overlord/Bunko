package com.vayana.core.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncFailuresTest {
    @Test
    fun noNetworkIsNotAnIssue() {
        assertTrue(isOfflineSyncFailure("Unable to resolve host \"api.github.com\": No address associated with hostname"))
        assertTrue(isOfflineSyncFailure("connect failed: ENETUNREACH (Network is unreachable)"))
    }

    @Test
    fun realFailuresStillAre() {
        assertFalse(isOfflineSyncFailure("GitHub git/commits write failed: HTTP 422"))
        assertFalse(isOfflineSyncFailure("Snapshot slice exceeds the 16 MiB uncompressed limit"))
        assertFalse(isOfflineSyncFailure(null))
    }
}

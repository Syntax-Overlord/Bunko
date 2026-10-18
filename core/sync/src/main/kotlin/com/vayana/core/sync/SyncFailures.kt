package com.vayana.core.sync

/**
 * Whether a sync failure only means the device had no usable network (no DNS, no route). Those are the normal
 * outcome of syncing while offline: logged, but not counted as issues in Diagnostics, where they drowned out real
 * problems. The messages are the ones java.net and Android's network stack use.
 */
fun isOfflineSyncFailure(message: String?): Boolean {
    if (message == null) return false
    return OfflineMessages.any { message.contains(it, ignoreCase = true) }
}

private val OfflineMessages = listOf(
    "Unable to resolve host",
    "No address associated with hostname",
    "Network is unreachable",
    "failed to connect to", // ConnectException while the network is down
)

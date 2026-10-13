package com.vayana.feature.library

/** Timed notes on one full sync, saved to diagnostics as a single event so slow phases are easy to spot. */
internal class FullSyncLog(private val nanoClock: () -> Long = System::nanoTime) {
    private val startedAt = nanoClock()
    private var phaseStartedAt = startedAt
    private val lines = mutableListOf<String>()

    /** Network totals for the sync, read when the log is written. */
    var requestStats: (() -> String)? = null

    val elapsedMillis: Long
        get() = (nanoClock() - startedAt) / 1_000_000

    /** Ends the phase that started when the previous one ended, noting how long it took. */
    fun phase(name: String, details: String = "") {
        val now = nanoClock()
        lines += buildString {
            append(name).append(": ").append((now - phaseStartedAt) / 1_000_000).append(" ms")
            if (details.isNotBlank()) append(" | ").append(details)
        }
        phaseStartedAt = now
    }

    fun note(line: String) {
        lines += line
    }

    fun text(): String = (lines + listOfNotNull(requestStats?.invoke())).joinToString("\n")
}

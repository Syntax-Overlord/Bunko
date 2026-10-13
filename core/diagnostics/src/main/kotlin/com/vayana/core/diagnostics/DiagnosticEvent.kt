package com.vayana.core.diagnostics

enum class DiagnosticCategory {
    CRASH,

    /** Something in sync went wrong: counted as a sync issue. */
    SYNC,

    /** A sync that went fine, recorded for its timings and counts: shown, but not counted as an issue. */
    SYNC_LOG,
}

data class DiagnosticEvent(
    val id: String,
    val timestamp: Long,
    val category: DiagnosticCategory,
    /** Short tag for where this came from, e.g. a class name or thread name. */
    val source: String,
    val message: String,
    /** Stack trace or other extended context, if any. */
    val detail: String? = null,
)

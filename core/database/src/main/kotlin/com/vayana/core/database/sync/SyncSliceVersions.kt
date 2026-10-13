package com.vayana.core.database.sync

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * A change counter per snapshot slice, bumped by SQLite triggers on every write to the tables that slice is exported
 * from (and, for slices naming books or shelves, on changes to those). Full sync records the counter when it
 * publishes a slice; while the counter and the cloud's pointer to that file both stay put, the next sync neither
 * exports nor hashes the slice. Triggers see every write path, merges included; a missed bump would only cost an
 * export, never skip one. Migrations that rebuild a tracked table must call [recreate].
 */
object SyncSliceVersions {
    const val CreateTable = "CREATE TABLE IF NOT EXISTS `sync_slice_versions` " +
        "(`sliceKey` TEXT NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`sliceKey`))"

    private const val Annotations = "annotations"
    private const val CommunityQuotes = "communityQuotes"
    private const val Shelves = "shelves"
    private const val ShelfMemberships = "shelfMemberships"
    private const val ReadingSessions = "readingSessions"
    private const val VocabularyCards = "vocabularyCards"
    private const val WordLookupCounters = "wordLookupCounters"
    private const val BookAliases = "bookAliases"
    private const val Tombstones = "tombstones"
    private val SliceKeys = listOf(
        Annotations, CommunityQuotes, Shelves, ShelfMemberships, ReadingSessions, VocabularyCards,
        WordLookupCounters, BookAliases, Tombstones,
    )

    /** Slices that name books by sync id or file hash: a book's identity changing rewrites them. */
    private val BookDependents = listOf(Annotations, CommunityQuotes, ShelfMemberships, ReadingSessions, VocabularyCards)

    // Same test as isCommunityQuoteLocator (case-sensitive prefix): GLOB, not LIKE.
    private fun isQuote(row: String) = "($row.`locator` GLOB 'quote:*' OR $row.`locator` GLOB 'goodreads-quote:*')"

    private fun bump(keys: List<String>, condition: String? = null): String =
        "UPDATE `sync_slice_versions` SET `version` = `version` + 1 WHERE `sliceKey` IN " +
            keys.joinToString(", ", "(", ")") { "'$it'" } + (condition?.let { " AND $it" } ?: "") + ";"

    private fun trigger(table: String, operation: String, body: String, whenClause: String? = null) =
        "CREATE TRIGGER IF NOT EXISTS `slice_version_${table}_$operation` AFTER ${operation.uppercase()} ON `$table`" +
            (whenClause?.let { " WHEN $it" } ?: "") + " BEGIN $body END"

    private val triggers: List<String> = buildList {
        add(trigger("annotations", "insert", bump(listOf(CommunityQuotes), isQuote("NEW")) + bump(listOf(Annotations), "NOT ${isQuote("NEW")}")))
        add(trigger("annotations", "delete", bump(listOf(CommunityQuotes), isQuote("OLD")) + bump(listOf(Annotations), "NOT ${isQuote("OLD")}")))
        add(
            trigger(
                "annotations", "update",
                bump(listOf(CommunityQuotes), "(${isQuote("OLD")} OR ${isQuote("NEW")})") +
                    bump(listOf(Annotations), "NOT (${isQuote("OLD")} AND ${isQuote("NEW")})"),
            ),
        )
        // Home Library mirrors are rewritten by their own sync and never exported.
        add(trigger("books", "insert", bump(BookDependents), "NEW.`source` IS NOT 'home_library'"))
        add(trigger("books", "delete", bump(BookDependents), "OLD.`source` IS NOT 'home_library'"))
        add(
            trigger(
                "books", "update", bump(BookDependents),
                "OLD.`syncId` IS NOT NEW.`syncId` OR OLD.`fileHash` IS NOT NEW.`fileHash` OR OLD.`source` IS NOT NEW.`source`",
            ),
        )
        listOf(
            "shelves" to listOf(Shelves, ShelfMemberships),
            "book_shelf_cross_ref" to listOf(ShelfMemberships),
            "reading_sessions" to listOf(ReadingSessions),
            "vocabulary_cards" to listOf(VocabularyCards),
            "word_lookup_stats" to listOf(WordLookupCounters),
            "book_aliases" to listOf(BookAliases),
            "tombstones" to listOf(Tombstones),
        ).forEach { (table, keys) ->
            listOf("insert", "update", "delete").forEach { operation -> add(trigger(table, operation, bump(keys))) }
        }
    }

    private val triggerNames: List<String> = triggers.map { it.substringAfter("EXISTS `").substringBefore('`') }

    fun create(connection: SQLiteConnection) {
        connection.execSQL(CreateTable)
        SliceKeys.forEach { key -> connection.execSQL("INSERT OR IGNORE INTO `sync_slice_versions` (`sliceKey`, `version`) VALUES ('$key', 0)") }
        triggers.forEach(connection::execSQL)
    }

    fun recreate(connection: SQLiteConnection) {
        triggerNames.forEach { name -> connection.execSQL("DROP TRIGGER IF EXISTS `$name`") }
        create(connection)
    }
}

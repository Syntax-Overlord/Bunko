package com.vayana.feature.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.vayana.core.database.MIGRATION_37_38
import com.vayana.core.database.sync.SyncSliceVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Migration37To38Test {
    /** The book triggers as versions 30-37 built them: rows mirrored from Home Library were skipped. */
    private fun version37Connection(): SQLiteConnection {
        val connection = AndroidSQLiteDriver().open(":memory:")
        connection.createFullSyncTrackedTables()
        connection.execSQL("ALTER TABLE `books` ADD COLUMN `syncUuid` TEXT")
        connection.execSQL("ALTER TABLE `books` ADD COLUMN `source` TEXT")
        connection.execSQL("ALTER TABLE `annotations` ADD COLUMN `locator` TEXT")
        listOf("reading_sessions", "word_lookup_stats", "book_aliases", "tombstones").forEach { table ->
            connection.execSQL("CREATE TABLE `$table` (`id` INTEGER PRIMARY KEY NOT NULL)")
        }
        connection.execSQL("CREATE TABLE `full_sync_state` (`id` INTEGER NOT NULL, `required` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        SyncSliceVersions.create(connection)
        val markRequired = "UPDATE `full_sync_state` SET `required` = 1 WHERE `id` = 0; " +
            "INSERT INTO `full_sync_state` (`id`, `required`) SELECT 0, 1 WHERE NOT EXISTS (SELECT 1 FROM `full_sync_state` WHERE `id` = 0);"
        connection.execSQL("DROP TRIGGER IF EXISTS `slice_version_books_insert`")
        connection.execSQL(
            "CREATE TRIGGER `slice_version_books_insert` AFTER INSERT ON `books` WHEN NEW.`source` IS NOT 'home_library' " +
                "BEGIN UPDATE `sync_slice_versions` SET `version` = `version` + 1 WHERE `sliceKey` = 'readingSessions'; END",
        )
        connection.execSQL(
            "CREATE TRIGGER `full_sync_books_insert` AFTER INSERT ON `books` WHEN NEW.`source` IS NOT 'home_library' " +
                "BEGIN $markRequired END",
        )
        connection.execSQL(
            "CREATE TRIGGER `full_sync_books_metadata_update` AFTER UPDATE ON `books` " +
                "WHEN NEW.`source` IS NOT 'home_library' AND (OLD.`title` IS NOT NEW.`title`) BEGIN $markRequired END",
        )
        return connection
    }

    @Test
    fun version37SkippedMirroredBooks() {
        val connection = version37Connection()

        connection.execSQL("INSERT INTO books (id, title, syncUuid, source) VALUES (1, 'Mirrored', 'u1', 'home_library')")

        assertFalse(connection.fullSyncRequired())
        assertEquals(0L, connection.readingSessionsSliceVersion())
    }

    @Test
    fun mirroredBooksRecommendAFullSyncAndBumpTheirSlicesAfterMigrating() {
        val connection = version37Connection()
        MIGRATION_37_38.migrate(connection)

        connection.execSQL("INSERT INTO books (id, title, syncUuid, source) VALUES (1, 'Mirrored', 'u1', 'home_library')")
        assertTrue(connection.fullSyncRequired())
        assertEquals(1L, connection.readingSessionsSliceVersion())

        connection.execSQL("UPDATE full_sync_state SET required = 0 WHERE id = 0")
        connection.execSQL("UPDATE books SET title = 'Retitled by Home Library' WHERE id = 1")
        assertTrue(connection.fullSyncRequired())
    }

    @Test
    fun ownBooksStillRecommendAFullSync() {
        val connection = version37Connection()
        MIGRATION_37_38.migrate(connection)

        connection.execSQL("INSERT INTO books (id, title) VALUES (2, 'Own')")

        assertTrue(connection.fullSyncRequired())
    }

    private fun SQLiteConnection.fullSyncRequired(): Boolean = prepare(
        "SELECT EXISTS(SELECT 1 FROM full_sync_state WHERE id = 0 AND required = 1)",
    ).use { statement ->
        statement.step()
        statement.getLong(0) == 1L
    }

    private fun SQLiteConnection.readingSessionsSliceVersion(): Long =
        prepare("SELECT version FROM sync_slice_versions WHERE sliceKey = 'readingSessions'").use { statement ->
            statement.step()
            statement.getLong(0)
        }
}

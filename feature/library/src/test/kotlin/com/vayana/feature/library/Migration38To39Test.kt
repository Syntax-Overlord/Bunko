package com.vayana.feature.library

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.vayana.core.database.MIGRATION_38_39
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class Migration38To39Test {
    @Test
    fun everySliceCounterMovesSoSlicesPublishedWithoutHomeLibraryBooksAreRebuilt() {
        val connection = AndroidSQLiteDriver().open(":memory:")
        connection.execSQL("CREATE TABLE `sync_slice_versions` (`sliceKey` TEXT NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`sliceKey`))")
        connection.execSQL("INSERT INTO sync_slice_versions VALUES ('readingSessions', 7), ('annotations', 0)")

        MIGRATION_38_39.migrate(connection)

        val versions = connection.prepare("SELECT sliceKey, version FROM sync_slice_versions ORDER BY sliceKey").use { statement ->
            buildMap { while (statement.step()) put(statement.getText(0), statement.getLong(1)) }
        }
        assertEquals(mapOf("annotations" to 1L, "readingSessions" to 8L), versions)
    }
}

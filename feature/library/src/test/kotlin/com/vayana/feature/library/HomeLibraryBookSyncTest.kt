package com.vayana.feature.library

import androidx.room.Room
import com.vayana.core.database.VayanaDatabase
import com.vayana.core.database.model.BookFormat
import com.vayana.core.database.model.BookSource
import com.vayana.core.database.repository.BookRepositoryImpl
import com.vayana.core.database.repository.CloudBookMergeResult
import com.vayana.core.database.repository.CloudBookRecord
import com.vayana.core.database.repository.CloudHomeLibraryOrigin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Home Library books syncing to a device: the device mirroring Home Library keeps its catalog unless a newer copy of
 * it arrives, while reading data merges as for any offline book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HomeLibraryBookSyncTest {
    private lateinit var database: VayanaDatabase
    private lateinit var repository: BookRepositoryImpl

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VayanaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BookRepositoryImpl(
            database = database,
            bookDao = database.bookDao(),
            bookAliasDao = database.bookAliasDao(),
            tombstoneDao = database.tombstoneDao(),
            readingSessionDao = database.readingSessionDao(),
            vocabularyCardDao = database.vocabularyCardDao(),
            pendingCloudDeletionDao = database.pendingCloudDeletionDao(),
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun aDeviceWithoutHomeLibraryGetsTheBookAsARead_onlyMirror() = runBlocking {
        assertEquals(CloudBookMergeResult.CREATED, repository.mergeCloudBook(record(catalogAt = 100, updatedAt = 500)))

        val book = database.bookDao().findBySyncId(SyncId)!!
        assertEquals(BookSource.HOME_LIBRARY, book.source)
        assertEquals(Uuid, book.syncUuid)
        assertEquals(100L, book.sourceUpdatedAt)
        assertEquals("""{"shelf":"A"}""", book.sourceMetadata)
        assertEquals(0.25f, book.readingPercent)
        assertEquals(1_800L, book.totalReadingSeconds)
    }

    @Test
    fun readingOnTheOtherDeviceNeverPutsBackAnOlderCatalog() = runBlocking {
        repository.mergeCloudBook(record(catalogAt = 100, updatedAt = 500))
        // Home Library renamed the book here since.
        val local = database.bookDao().findBySyncId(SyncId)!!
        database.bookDao().update(local.copy(title = "Contessa (2nd ed.)", sourceUpdatedAt = 200, updatedAt = 600))

        // The other device read further, with the catalog as it knew it.
        repository.mergeCloudBook(record(catalogAt = 100, updatedAt = 900, title = "Contessa", readingPercent = 0.5f, pageCount = 300))

        val merged = database.bookDao().findBySyncId(SyncId)!!
        assertEquals("Contessa (2nd ed.)", merged.title)
        assertEquals(200L, merged.sourceUpdatedAt)
        assertEquals(0.5f, merged.readingPercent)
    }

    @Test
    fun aNewerCatalogFromTheOtherDeviceIsTaken() = runBlocking {
        repository.mergeCloudBook(record(catalogAt = 100, updatedAt = 500))

        repository.mergeCloudBook(record(catalogAt = 300, updatedAt = 400, title = "Contessa (2nd ed.)", metadata = """{"shelf":"B"}"""))

        val merged = database.bookDao().findBySyncId(SyncId)!!
        assertEquals("Contessa (2nd ed.)", merged.title)
        assertEquals(300L, merged.sourceUpdatedAt)
        assertEquals("""{"shelf":"B"}""", merged.sourceMetadata)
        // Its reading data was older: the local reading stays.
        assertEquals(0.25f, merged.readingPercent)
    }

    @Test
    fun aCopyWithoutProvenanceNeverOverridesTheMirroredCatalog() = runBlocking {
        repository.mergeCloudBook(record(catalogAt = 100, updatedAt = 500))

        // An older app version on the other device exports the book without its Home Library fields.
        repository.mergeCloudBook(record(catalogAt = 100, updatedAt = 900, title = "Edited elsewhere").copy(homeLibrary = null))

        val merged = database.bookDao().findBySyncId(SyncId)!!
        assertEquals("Contessa", merged.title)
        assertEquals(BookSource.HOME_LIBRARY, merged.source)
    }

    @Test
    fun aRowSyncedByAnOlderVersionGainsItsProvenance() = runBlocking {
        repository.mergeCloudBook(record(catalogAt = 100, updatedAt = 500).copy(homeLibrary = null))
        assertNull(database.bookDao().findBySyncId(SyncId)!!.source)

        repository.mergeCloudBook(record(catalogAt = 100, updatedAt = 500))

        val upgraded = database.bookDao().findBySyncId(SyncId)!!
        assertEquals(BookSource.HOME_LIBRARY, upgraded.source)
        assertEquals(Uuid, upgraded.syncUuid)
    }

    private fun record(
        catalogAt: Long,
        updatedAt: Long,
        title: String = "Contessa",
        readingPercent: Float = 0.25f,
        pageCount: Int = 189,
        metadata: String = """{"shelf":"A"}""",
    ) = CloudBookRecord(
        syncId = SyncId,
        title = title,
        author = "SP Sarath",
        series = null,
        seriesNumber = null,
        description = null,
        tagsCsv = null,
        format = BookFormat.PHYSICAL,
        fileHash = "physical:$Uuid",
        assetId = null,
        assetSha256 = null,
        assetSizeBytes = null,
        assetUploadedAt = null,
        coverAssetId = null,
        coverAssetSha256 = null,
        coverAssetSizeBytes = null,
        coverAssetUploadedAt = null,
        lastLocator = null,
        readingPercent = readingPercent,
        rating = 0f,
        wordCount = null,
        pageEstimate = pageCount,
        createdAt = 1,
        updatedAt = updatedAt,
        lastReadAt = updatedAt,
        startedReadingAt = 10,
        finishedReadingAt = null,
        totalReadingSeconds = 1_800,
        customFontSizePercent = null,
        customLineHeight = null,
        customFontFamily = null,
        customSideMarginPercent = null,
        readNextAddedAt = null,
        readNextUpdatedAt = null,
        goodreadsUrl = null,
        goodreadsRating = null,
        goodreadsRatingsCount = null,
        originalPublicationYear = null,
        physicalOwnership = null,
        borrowReturnAt = null,
        homeLibrary = CloudHomeLibraryOrigin(Uuid, catalogAt, sourceHasCover = false, sourceMetadata = metadata),
    )

    private companion object {
        const val Uuid = "dc123e04-dc49-4678-8a8d-5de58740ccd7"
        val SyncId = BookSource.homeLibrarySyncId(Uuid)
    }
}

package com.vayana.core.sync.usage

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncDataUsageLogTest {
    private lateinit var dir: File
    private var now = 0L
    private var network = DataNetwork.WIFI

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sync-usage").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun log() = SyncDataUsageLog(File(dir, "sync-usage.ndjson"), { network }, { now })

    @Test
    fun recordsEachSyncWithItsNetworkNewestFirst() {
        val log = log()
        now = 1_000; log.record(SyncKind.FULL, GitHubTransferStats(9, downBytes = 300, upBytes = 200))
        now = 2_000; network = DataNetwork.MOBILE; log.record(SyncKind.BACKGROUND, GitHubTransferStats(4, 50, 80))

        val entries = log().readAll()

        assertEquals(listOf(SyncKind.BACKGROUND, SyncKind.FULL), entries.map { it.kind })
        assertEquals(DataNetwork.MOBILE, entries.first().network)
        assertEquals(130L, entries.first().totalBytes)
        assertEquals(9, entries.last().requests)
    }

    @Test
    fun aSyncThatMadeNoRequestRecordsNothing() {
        log().record(SyncKind.BACKGROUND, GitHubTransferStats(0, 0, 0))

        assertTrue(log().readAll().isEmpty())
    }

    @Test
    fun aDamagedLineIsSkipped() {
        val log = log()
        now = 1_000; log.record(SyncKind.FULL, GitHubTransferStats(1, 10, 10))
        File(dir, "sync-usage.ndjson").appendText("{not json\n")

        assertEquals(1, log().readAll().size)
    }

    @Test
    fun aGrowingFileKeepsOnlyRecentSyncs() {
        val log = log()
        val day = SyncDataUsageLog.DayMillis
        now = day; log.record(SyncKind.FULL, GitHubTransferStats(1, 1, 1))
        // Enough newer lines to pass the size limit, 200 days later.
        now = 201 * day
        repeat(15_000) { log.record(SyncKind.BACKGROUND, GitHubTransferStats(1, 1, 1)) }

        val entries = log().readAll()
        assertTrue(entries.none { it.at == day }, "a sync older than the retention window was kept")
        assertTrue(entries.isNotEmpty())
    }

    @Test
    fun daysSplitWifiAndMobileAndFillQuietDays() {
        val zone = ZoneId.of("Asia/Kolkata")
        val today = LocalDate.of(2026, 10, 17)
        fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val entries = listOf(
            SyncDataUsageEntry(at(today, 0), SyncKind.BACKGROUND, DataNetwork.MOBILE, 100, 0, 1),
            SyncDataUsageEntry(at(today, 23), SyncKind.FULL, DataNetwork.WIFI, 0, 400, 1),
            SyncDataUsageEntry(at(today.minusDays(1), 23), SyncKind.FULL, DataNetwork.OTHER, 50, 0, 1),
        )

        val days = entries.dailyUsage(3, today, zone)

        assertEquals(listOf(today.minusDays(2), today.minusDays(1), today), days.map { it.date })
        assertEquals(NetworkSplit(), days[0].split)
        assertEquals(NetworkSplit(wifiBytes = 50), days[1].split) // other networks count as unmetered
        assertEquals(NetworkSplit(wifiBytes = 400, mobileBytes = 100), days[2].split)
        assertEquals(2, entries.since(today, zone).size)
    }

    @Test
    fun wifiWinsOverCellularWhenBothAreUp() {
        assertEquals(DataNetwork.WIFI, dataNetworkOf(wifi = true, cellular = true))
        assertEquals(DataNetwork.MOBILE, dataNetworkOf(wifi = false, cellular = true))
        assertEquals(DataNetwork.OTHER, dataNetworkOf(wifi = false, cellular = false))
    }
}

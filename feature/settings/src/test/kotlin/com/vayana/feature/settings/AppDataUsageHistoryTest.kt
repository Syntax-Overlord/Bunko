package com.vayana.feature.settings

import com.vayana.core.sync.usage.DayUsage
import com.vayana.core.sync.usage.NetworkSplit
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppDataUsageHistoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private lateinit var dir: File
    private var today = LocalDate.of(2026, 10, 18)
    /** What "Android" reports per day; days missing from it have been aged out. */
    private var android = mapOf<LocalDate, NetworkSplit>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("app-usage").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun history() = AppDataUsageHistory(
        File(dir, "app-usage-daily.ndjson"),
        { start, _ -> android[java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()] ?: NetworkSplit() },
        { zone },
        { today },
    )

    @Test
    fun savedDaysOutliveAndroidsHistory() {
        android = mapOf(today to NetworkSplit(100, 10), today.minusDays(1) to NetworkSplit(200, 20))
        history().update()

        // Two months later Android only remembers recent days.
        today = today.plusDays(60)
        android = mapOf(today to NetworkSplit(5, 0))
        history().update()

        val days = history().read().associate { it.date to it.split }
        assertEquals(NetworkSplit(100, 10), days[LocalDate.of(2026, 10, 18)])
        assertEquals(NetworkSplit(200, 20), days[LocalDate.of(2026, 10, 17)])
        assertEquals(NetworkSplit(5, 0), days[today])
    }

    @Test
    fun aDayStillFillingUpIsUpdatedOnTheNextRun() {
        android = mapOf(today to NetworkSplit(100, 0))
        history().update()
        android = mapOf(today to NetworkSplit(400, 50))
        history().update()

        assertEquals(NetworkSplit(400, 50), history().read().single { it.date == today }.split)
    }

    @Test
    fun monthsTotalNewestFirstAndQuietDaysFillTheChart() {
        val days = listOf(
            DayUsage(LocalDate.of(2026, 9, 30), NetworkSplit(10, 1)),
            DayUsage(LocalDate.of(2026, 10, 1), NetworkSplit(20, 2)),
            DayUsage(LocalDate.of(2026, 10, 18), NetworkSplit(30, 3)),
        )

        assertEquals(
            listOf(YearMonth.of(2026, 10) to NetworkSplit(50, 5), YearMonth.of(2026, 9) to NetworkSplit(10, 1)),
            days.monthlyTotals(),
        )
        val chart = days.lastDays(30, LocalDate.of(2026, 10, 18))
        assertEquals(30, chart.size)
        assertEquals(LocalDate.of(2026, 10, 18), chart.last().date)
        assertEquals(NetworkSplit(), chart[chart.size - 2].split)
    }
}

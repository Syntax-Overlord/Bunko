package com.vayana.feature.settings

import com.vayana.core.sync.usage.DataNetwork
import com.vayana.core.sync.usage.NetworkSplit
import com.vayana.core.sync.usage.SyncDataUsageEntry
import com.vayana.core.sync.usage.SyncKind
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DataUsageSummaryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 17)
    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 30).atZone(zone).toInstant().toEpochMilli()
    private fun entry(at: Long, network: DataNetwork, bytes: Long) = SyncDataUsageEntry(at, SyncKind.BACKGROUND, network, bytes, 0, 1)

    @Test
    fun todayCountsFromLocalMidnightAndTheMonthReachesBack30Days() {
        val entries = listOf(
            entry(at(today, 0), DataNetwork.MOBILE, 100),
            entry(at(today, 22), DataNetwork.WIFI, 300),
            entry(at(today.minusDays(1), 23), DataNetwork.WIFI, 1_000), // yesterday, just before midnight
            entry(at(today.minusDays(29), 1), DataNetwork.MOBILE, 10),
            entry(at(today.minusDays(30), 23), DataNetwork.MOBILE, 99_999), // outside 30 days
        )

        val summary = entries.summary(today, zone)

        assertEquals(2, summary.todayCount)
        assertEquals(NetworkSplit(wifiBytes = 300, mobileBytes = 100), summary.today)
        assertEquals(NetworkSplit(wifiBytes = 1_300, mobileBytes = 110), summary.lastMonth)
        assertEquals(14, summary.days.size)
        assertEquals(today, summary.days.last().date)
        assertEquals(listOf(entries[1].at, entries[0].at), summary.recent.take(2).map { it.at })
    }
}

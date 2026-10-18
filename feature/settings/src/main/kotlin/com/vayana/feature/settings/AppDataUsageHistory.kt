package com.vayana.feature.settings

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import com.vayana.core.sync.usage.DayUsage
import com.vayana.core.sync.usage.NetworkSplit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * The app's Wi-Fi and mobile bytes per day, kept on this device only (never synced) so Data usage can show months
 * of history after Android has dropped its own. Each [update] re-reads the last [RefreshDays] days from Android,
 * which settles the still-open days and backfills any missed while the app wasn't opened.
 */
@Singleton
class AppDataUsageHistory internal constructor(
    private val file: File,
    private val dayUsage: (start: Long, end: Long) -> NetworkSplit?,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, "diagnostics/app-usage-daily.ndjson"), { start, end -> context.appSplit(start, end) })

    private val lock = Any()

    fun update() = synchronized(lock) {
        val days = readLocked().toMutableMap()
        val zone = zone()
        val today = today()
        for (offset in 0 until RefreshDays) {
            val date = today.minusDays(offset.toLong())
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            // Older days Android no longer holds come back empty: keep what was stored then.
            val split = dayUsage(start, end) ?: continue
            if (split.totalBytes > 0 || date !in days) days[date] = split
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(days.toSortedMap().entries.joinToString("") { (date, split) -> split.toLine(date) + "\n" })
        }
    }

    /** Every stored day, oldest first. */
    fun read(): List<DayUsage> = synchronized(lock) { readLocked().toSortedMap().map { (date, split) -> DayUsage(date, split) } }

    private fun readLocked(): Map<LocalDate, NetworkSplit> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                runCatching {
                    val o = JSONObject(line)
                    LocalDate.parse(o.getString("date")) to NetworkSplit(o.getLong("wifi"), o.getLong("mobile"))
                }.getOrNull()
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val RefreshDays = 35
    }
}

private fun NetworkSplit.toLine(date: LocalDate) =
    JSONObject().put("date", date.toString()).put("wifi", wifiBytes).put("mobile", mobileBytes).toString()

/** Months with any stored traffic, newest first. */
internal fun List<DayUsage>.monthlyTotals(): List<Pair<YearMonth, NetworkSplit>> =
    groupBy { YearMonth.from(it.date) }
        .mapValues { (_, days) -> days.fold(NetworkSplit()) { sum, day -> NetworkSplit(sum.wifiBytes + day.split.wifiBytes, sum.mobileBytes + day.split.mobileBytes) } }
        .toSortedMap(compareByDescending { it })
        .map { it.key to it.value }

/** The last [count] days ending [today], empty days filled in, oldest first. */
internal fun List<DayUsage>.lastDays(count: Int, today: LocalDate): List<DayUsage> {
    val byDate = associateBy { it.date }
    return (count - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { byDate[it] ?: DayUsage(it, NetworkSplit()) } }
}

/** Null when Android can't say for Wi-Fi; mobile it can't report (Android 8–9) counts as 0. */
private fun Context.appSplit(start: Long, end: Long): NetworkSplit? {
    val wifi = appBytes(start, end, @Suppress("DEPRECATION") ConnectivityManager.TYPE_WIFI) ?: return null
    return NetworkSplit(wifi, appBytes(start, end, @Suppress("DEPRECATION") ConnectivityManager.TYPE_MOBILE) ?: 0L)
}

/** The app's own traffic needs no permission to read; mobile without a subscriber id fails before Android 10. */
internal fun Context.appBytes(start: Long, end: Long, networkType: Int): Long? = runCatching {
    getSystemService(NetworkStatsManager::class.java).queryDetailsForUid(networkType, null, start, end, Process.myUid()).use { stats ->
        val bucket = NetworkStats.Bucket()
        var bytes = 0L
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            bytes += bucket.rxBytes + bucket.txBytes
        }
        bytes
    }
}.getOrNull()

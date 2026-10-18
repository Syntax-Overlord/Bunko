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
 * of history after Android has dropped its own. Each [update] re-reads the last [RefreshDays] days from Android in
 * one query per network, which settles the still-open days and backfills any missed while the app wasn't opened.
 */
@Singleton
class AppDataUsageHistory internal constructor(
    private val file: File,
    private val readDays: (start: Long, end: Long, zone: ZoneId) -> AndroidDays?,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, "diagnostics/app-usage-daily.ndjson"), { start, end, zone -> context.androidDays(start, end, zone) })

    private val lock = Any()

    /** Whether Android reported mobile data on the last [update] (Android 8–9 may not). */
    @Volatile var mobileReported: Boolean = true
        private set

    fun update() = synchronized(lock) {
        val zone = zone()
        val today = today()
        val first = today.minusDays(RefreshDays - 1L)
        // Ending a bucket past now takes in the 2-hour bucket still filling up.
        val android = readDays(first.atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis() + BucketMillis, zone)
            ?: return@synchronized
        mobileReported = android.mobileReported
        val days = readLocked().toMutableMap()
        var changed = false
        for (offset in 0 until RefreshDays) {
            val date = first.plusDays(offset.toLong())
            // A day Android no longer holds comes back empty: keep what was saved then.
            val split = android.days[date] ?: NetworkSplit()
            if ((split.totalBytes > 0 || date !in days) && days[date] != split) {
                days[date] = split
                changed = true
            }
        }
        if (!changed) return@synchronized
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
        const val BucketMillis = 2L * 60 * 60 * 1000
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

/** The app's bytes per local day as Android reports them. */
class AndroidDays(val days: Map<LocalDate, NetworkSplit>, val mobileReported: Boolean)

/** Null when Android can't say for Wi-Fi; mobile it can't report (Android 8–9) counts as 0. */
@Suppress("DEPRECATION") // NetworkStatsManager still takes the ConnectivityManager network types.
private fun Context.androidDays(start: Long, end: Long, zone: ZoneId): AndroidDays? {
    val wifi = bytesPerDay(start, end, ConnectivityManager.TYPE_WIFI, zone) ?: return null
    val mobile = bytesPerDay(start, end, ConnectivityManager.TYPE_MOBILE, zone)
    val days = (wifi.keys + mobile.orEmpty().keys).associateWith { date -> NetworkSplit(wifi[date] ?: 0L, mobile?.get(date) ?: 0L) }
    return AndroidDays(days, mobileReported = mobile != null)
}

/**
 * One query for the whole range, its 2-hour buckets added up by the local day each starts on. The app's own traffic
 * needs no permission to read; mobile without a subscriber id fails before Android 10.
 */
private fun Context.bytesPerDay(start: Long, end: Long, networkType: Int, zone: ZoneId): Map<LocalDate, Long>? = runCatching {
    getSystemService(NetworkStatsManager::class.java).queryDetailsForUid(networkType, null, start, end, Process.myUid()).use { stats ->
        val bucket = NetworkStats.Bucket()
        val days = HashMap<LocalDate, Long>()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            val date = java.time.Instant.ofEpochMilli(bucket.startTimeStamp).atZone(zone).toLocalDate()
            days[date] = (days[date] ?: 0L) + bucket.rxBytes + bucket.txBytes
        }
        days
    }
}.getOrNull()

package com.vayana.feature.settings

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vayana.core.common.DispatcherProvider
import com.vayana.core.sync.usage.DayUsage
import com.vayana.core.sync.usage.NetworkSplit
import com.vayana.core.sync.usage.SyncDataUsageEntry
import com.vayana.core.sync.usage.SyncDataUsageLog
import com.vayana.core.sync.usage.dailyUsage
import com.vayana.core.sync.usage.since
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class UsagePeriod(val days: Long) { TODAY(1), WEEK(7), MONTH(30) }

/** What Android counted for this app over a period; null where it can't say (mobile on Android 8–9). */
internal data class AppPeriodUsage(val period: UsagePeriod, val wifiBytes: Long?, val mobileBytes: Long?)

internal data class SyncUsageSummary(
    val todayCount: Int,
    val today: NetworkSplit,
    val lastMonth: NetworkSplit,
    val days: List<DayUsage>,
    val recent: List<SyncDataUsageEntry>,
)

internal data class DataUsageState(val app: List<AppPeriodUsage>, val sync: SyncUsageSummary, val history: List<DayUsage>)

@HiltViewModel
internal class DataUsageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncDataUsageLog: SyncDataUsageLog,
    private val appDataUsageHistory: AppDataUsageHistory,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val _state = MutableStateFlow<DataUsageState?>(null)
    val state: StateFlow<DataUsageState?> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = withContext(dispatchers.io) {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                DataUsageState(
                    app = UsagePeriod.entries.map { period ->
                        val start = today.minusDays(period.days - 1).atStartOfDay(zone).toInstant().toEpochMilli()
                        // Android only returns whole 2-hour buckets inside the range, so end past now to include the
                        // bucket still filling up; otherwise today reads 0 B for up to two hours after a sync.
                        context.appDataUsage(start, System.currentTimeMillis() + CurrentBucketMillis, period)
                    },
                    sync = syncDataUsageLog.readAll().summary(today, zone),
                    history = appDataUsageHistory.run { update(); read() },
                )
            }
        }
    }
}

internal fun List<SyncDataUsageEntry>.summary(today: LocalDate, zone: ZoneId): SyncUsageSummary {
    val todays = since(today, zone)
    return SyncUsageSummary(
        todayCount = todays.size,
        today = todays.fold(NetworkSplit()) { split, entry -> split + entry },
        lastMonth = since(today.minusDays(29), zone).fold(NetworkSplit()) { split, entry -> split + entry },
        days = dailyUsage(ChartDays, today, zone),
        recent = sortedByDescending { it.at }.take(RecentSyncs),
    )
}

private fun Context.appDataUsage(start: Long, end: Long, period: UsagePeriod): AppPeriodUsage {
    @Suppress("DEPRECATION") // NetworkStatsManager still takes the ConnectivityManager network types.
    return AppPeriodUsage(period, appBytes(start, end, ConnectivityManager.TYPE_WIFI), appBytes(start, end, ConnectivityManager.TYPE_MOBILE))
}

private const val ChartDays = 14
private const val CurrentBucketMillis = 2L * 60 * 60 * 1000
private const val RecentSyncs = 50

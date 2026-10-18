package com.vayana.feature.settings

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
                appDataUsageHistory.update()
                val history = appDataUsageHistory.read()
                DataUsageState(
                    app = history.periods(today, appDataUsageHistory.mobileReported),
                    sync = syncDataUsageLog.readAll().summary(today, zone),
                    history = history,
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

/** Today, 7 and 30 days from the same saved days as the chart, so the tiles and bars always agree. */
internal fun List<DayUsage>.periods(today: LocalDate, mobileReported: Boolean): List<AppPeriodUsage> =
    UsagePeriod.entries.map { period ->
        val days = lastDays(period.days.toInt(), today)
        AppPeriodUsage(period, days.sumOf { it.split.wifiBytes }, days.sumOf { it.split.mobileBytes }.takeIf { mobileReported })
    }

private const val ChartDays = 14
private const val RecentSyncs = 50

package com.vayana.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayana.core.designsystem.theme.LocalFloatingNavigationInset
import com.vayana.core.designsystem.theme.PagedLazyColumn
import com.vayana.core.designsystem.theme.asAppDateTime
import com.vayana.core.designsystem.tokens.Elevations
import com.vayana.core.designsystem.tokens.Paddings
import com.vayana.core.designsystem.tokens.Radii
import com.vayana.core.designsystem.tokens.Spacing
import com.vayana.core.resources.R
import com.vayana.core.sync.usage.DataNetwork
import com.vayana.core.sync.usage.DayUsage
import com.vayana.core.sync.usage.SyncDataUsageEntry
import com.vayana.core.sync.usage.SyncKind

@Composable
fun DataUsageRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: DataUsageViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    DataUsageScreen(state = state, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataUsageScreen(state: DataUsageState?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_usage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.notes_back_content_description))
                    }
                },
                // The app shell already pads for the status bar, as on Settings and Diagnostics.
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        if (state == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        PagedLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Paddings.screenHorizontal,
                end = Paddings.screenHorizontal,
                top = innerPadding.calculateTopPadding() + Spacing.sm,
                bottom = innerPadding.calculateBottomPadding() + Spacing.xl + LocalFloatingNavigationInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item { SettingsContentContainer { AppDataCard(state.app) } }
            item { SettingsContentContainer { SyncSummaryCard(state.sync) } }
            item {
                SettingsContentContainer {
                    UsageCard(stringResource(R.string.data_usage_sync_recent)) {
                        if (state.sync.recent.isEmpty()) {
                            Text(stringResource(R.string.data_usage_sync_empty), style = MaterialTheme.typography.bodyMedium)
                        }
                        state.sync.recent.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SyncRow(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.large),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = Elevations.none,
    ) {
        Column(Modifier.padding(Paddings.card), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            content()
        }
    }
}

@Composable
private fun AppDataCard(rows: List<AppPeriodUsage>) {
    UsageCard(stringResource(R.string.data_usage_app_section)) {
        UsageTableRow(
            "", stringResource(R.string.data_usage_wifi), stringResource(R.string.data_usage_mobile),
            stringResource(R.string.data_usage_total), header = true,
        )
        val notAvailable = stringResource(R.string.data_usage_not_available)
        rows.forEach { row ->
            val total = listOfNotNull(row.wifiBytes, row.mobileBytes).takeIf { it.isNotEmpty() }?.sum()
            UsageTableRow(
                label = stringResource(
                    when (row.period) {
                        UsagePeriod.TODAY -> R.string.data_usage_period_today
                        UsagePeriod.WEEK -> R.string.data_usage_period_week
                        UsagePeriod.MONTH -> R.string.data_usage_period_month
                    },
                ),
                wifi = row.wifiBytes?.let { bytes(it) } ?: notAvailable,
                mobile = row.mobileBytes?.let { bytes(it) } ?: notAvailable,
                total = total?.let { bytes(it) } ?: notAvailable,
            )
        }
        Text(stringResource(R.string.data_usage_app_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rows.any { it.mobileBytes == null }) {
            Text(stringResource(R.string.data_usage_not_available_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageTableRow(label: String, wifi: String, mobile: String, total: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, color = color, modifier = Modifier.weight(1.3f))
        listOf(wifi, mobile, total).forEach { value ->
            Text(value, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SyncSummaryCard(sync: SyncUsageSummary) {
    UsageCard(stringResource(R.string.data_usage_sync_section)) {
        Text(
            pluralStringResource(R.plurals.data_usage_sync_today, sync.todayCount, sync.todayCount, bytes(sync.today.wifiBytes), bytes(sync.today.mobileBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.data_usage_sync_month, bytes(sync.lastMonth.totalBytes), bytes(sync.lastMonth.mobileBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.data_usage_sync_chart_title), style = MaterialTheme.typography.labelLarge)
        val total = sync.days.sumOf { it.split.totalBytes }
        val mobile = sync.days.sumOf { it.split.mobileBytes }
        SyncDaysChart(
            days = sync.days,
            description = stringResource(R.string.data_usage_sync_chart_description, bytes(total), bytes(mobile)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(MaterialTheme.colorScheme.primary, stringResource(R.string.data_usage_wifi))
            LegendDot(MaterialTheme.colorScheme.tertiary, stringResource(R.string.data_usage_mobile))
        }
    }
}

/** One bar per day: Wi-Fi at the bottom, mobile stacked on top, scaled to the busiest day. */
@Composable
private fun SyncDaysChart(days: List<DayUsage>, description: String) {
    val wifiColor = MaterialTheme.colorScheme.primary
    val mobileColor = MaterialTheme.colorScheme.tertiary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val max = days.maxOfOrNull { it.split.totalBytes }?.takeIf { it > 0 } ?: 1L
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description },
        ) {
            val slot = size.width / days.size
            val barWidth = slot * 0.6f
            days.forEachIndexed { index, day ->
                val left = index * slot + (slot - barWidth) / 2
                val wifiHeight = size.height * day.split.wifiBytes / max
                val mobileHeight = size.height * day.split.mobileBytes / max
                drawRect(wifiColor, Offset(left, size.height - wifiHeight), Size(barWidth, wifiHeight))
                drawRect(mobileColor, Offset(left, size.height - wifiHeight - mobileHeight), Size(barWidth, mobileHeight))
            }
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { index, day ->
                // Every other day labelled, so two-digit dates never collide on a phone.
                Text(
                    if (index % 2 == days.lastIndex % 2) "${day.date.dayOfMonth}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(10.dp), shape = CircleShape, color = color) {}
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SyncRow(entry: SyncDataUsageEntry) {
    val kind = stringResource(
        when (entry.kind) {
            SyncKind.FULL -> R.string.data_usage_kind_full
            SyncKind.BACKGROUND -> R.string.data_usage_kind_background
            SyncKind.LAUNCH_CHECK -> R.string.data_usage_kind_launch_check
        },
    )
    val network = stringResource(
        when (entry.network) {
            DataNetwork.WIFI -> R.string.data_usage_wifi
            DataNetwork.MOBILE -> R.string.data_usage_mobile
            DataNetwork.OTHER -> R.string.data_usage_network_other
            DataNetwork.NONE -> R.string.data_usage_network_none
        },
    )
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(entry.at.asAppDateTime(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.data_usage_sync_row, kind, network, bytes(entry.upBytes), bytes(entry.downBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun bytes(value: Long): String = Formatter.formatShortFileSize(LocalContext.current, value)

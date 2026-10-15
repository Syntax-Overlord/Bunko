package com.vayana.app.widget

import com.vayana.core.database.model.ReadingSession
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingTimeChartTest {
    private val today = LocalDate.of(2026, 9, 29)

    @Test
    fun `average leaves today out and counts empty days`() {
        val week = ReadingWeek(today, listOf(80, 72, 68, 55, 8, 3, 17, 66))
        assertEquals(43, week.averageMinutes) // 303 / 7
        assertEquals(66, week.todayMinutes)
    }

    @Test
    fun `reading today does not move the average`() {
        val before = ReadingWeek(today, listOf(10, 0, 0, 20, 0, 0, 30, 5))
        val after = ReadingWeek(today, listOf(10, 0, 0, 20, 0, 0, 30, 95))
        assertEquals(before.averageMinutes, after.averageMinutes)
    }

    @Test
    fun `no reading at all is no history`() {
        assertFalse(ReadingWeek(today, List(8) { 0 }).hasHistory)
        assertTrue(ReadingWeek(today, List(8) { if (it == 7) 1 else 0 }).hasHistory)
    }

    @Test
    fun `scale picks the smallest step whose three intervals hold the maximum`() {
        assertEquals(10, scaleStep(0))
        assertEquals(10, scaleStep(30))
        assertEquals(20, scaleStep(31))
        assertEquals(30, scaleStep(80)) // 90 >= 80
        assertEquals(60, scaleStep(180))
        assertEquals(180, scaleStep(540))
        assertEquals(240, scaleStep(720)) // beyond the table: round up to whole hours
    }

    @Test
    fun `axis labels are compact`() {
        assertEquals("0", axisLabel(0))
        assertEquals("30m", axisLabel(30))
        assertEquals("1h", axisLabel(60))
        assertEquals("1h30", axisLabel(90))
        assertEquals("2h", axisLabel(120))
        assertEquals("1h20", axisLabel(80))
    }

    @Test
    fun `sessions count on the day they started and only from a minute`() {
        val zone = ZoneId.of("UTC")
        fun session(day: LocalDate, seconds: Long): ReadingSession {
            val start = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            return ReadingSession(
                id = 0, syncId = "s", bookId = 1, startedAt = start, endedAt = start + seconds * 1000,
                durationSeconds = seconds,
            )
        }
        val week = readingWeek(
            listOf(session(today, 3_600), session(today, 30), session(today.minusDays(7), 600), session(today.minusDays(8), 999)),
            today,
            zone,
        )
        assertEquals(60, week.todayMinutes)
        assertEquals(10, week.minutesByDay.first())
        assertEquals(8, week.minutesByDay.size)
    }

    @Test
    fun `digital and physical sessions from different devices share the same daily total`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val start = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val digital = ReadingSession(1, "device-a", 1, start, start + 600_000, 600)
        val physical = ReadingSession(2, "device-b", 2, start + 900_000, start + 2_100_000, 1200, 10, 20)
        val week = readingWeek(listOf(digital, physical), today, zone)
        assertEquals(30, week.todayMinutes)
        assertEquals(0, week.averageMinutes)
        val updated = readingWeek(listOf(digital.copy(endedAt = start + 900_000, durationSeconds = 900), physical), today, zone)
        assertEquals(35, updated.todayMinutes)
    }

    @Test
    fun `a month keeps today last and averages only the seven days before it`() {
        val minutes = List(31) { if (it in 23..29) 14 else 500 }
        val month = ReadingWeek(today, minutes)
        assertEquals(14, month.averageMinutes)
        assertEquals(500, month.todayMinutes)
        assertEquals(today.minusDays(30), month.days.first())
    }

    @Test
    fun `a month of sessions reaches back thirty days`() {
        val zone = ZoneId.of("UTC")
        val start = today.minusDays(30).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val month = readingWeek(listOf(ReadingSession(1, "s", 1, start, start + 600_000, 600)), today, zone, 31)
        assertEquals(31, month.minutesByDay.size)
        assertEquals(10, month.minutesByDay.first())
    }

    @Test
    fun `day labels thin out only when they would touch`() {
        assertEquals(1, dayLabelEvery(labelWidthPx = 10f, dayWidthPx = 30f, gapPx = 6f))
        assertEquals(3, dayLabelEvery(labelWidthPx = 14f, dayWidthPx = 7f, gapPx = 6f)) // 20 / 7 -> every third day
    }

    @Test
    fun `layout sheds header parts as the widget shrinks`() {
        val large = ReadingWidgetLayout.forSize(440, 220)
        assertTrue(large.showAverageWords && large.showAverage)
        val narrow = ReadingWidgetLayout.forSize(250, 200)
        assertTrue(narrow.showAverage && !narrow.showAverageWords)
        val small = ReadingWidgetLayout.forSize(180, 110)
        assertFalse(small.showAverage)
    }

    @Test
    fun `without a header the chart takes its height up to the top margin`() {
        val withHeader = ReadingWidgetLayout.forSize(320, 160)
        val without = ReadingWidgetLayout.forSize(320, 160, header = false)
        assertFalse(without.showHeader)
        assertEquals(without.paddingTopDp + without.paddingBottomDp, without.chromeHeightDp)
        assertTrue(without.chromeHeightDp < withHeader.chromeHeightDp)
        val filled = ReadingWidgetLayout.forSize(320, 160, header = false, fill = true)
        assertTrue(filled.paddingHorizontalDp < without.paddingHorizontalDp)
        assertEquals(filled.paddingTopDp * 2, filled.chromeHeightDp)
        // Fill needs the header off.
        assertEquals(withHeader, ReadingWidgetLayout.forSize(320, 160, header = true, fill = true))
    }

    @Test
    fun `fill applies only without a header`() {
        assertTrue(WidgetAppearance(chartHeader = false, chartFill = true).chartFillsWidget)
        assertFalse(WidgetAppearance(chartHeader = true, chartFill = true).chartFillsWidget)
    }
}

package com.vayana.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.vayana.core.database.model.ReadingSession
import com.vayana.core.datastore.settings.WidgetChartStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reading minutes for the seven days before [today] and for today itself, oldest first ([minutesByDay] has
 * [DaysShown] entries, today last). The average covers the seven days before today only, counting empty days.
 */
data class ReadingWeek(val today: LocalDate, val minutesByDay: List<Int>) {
    init {
        require(minutesByDay.size == DaysShown)
    }

    val todayMinutes: Int get() = minutesByDay.last()
    val days: List<LocalDate> get() = List(DaysShown) { today.minusDays((DaysShown - 1 - it).toLong()) }

    /** No reading in the whole window: there is nothing to average or compare with. */
    val hasHistory: Boolean get() = minutesByDay.any { it > 0 }

    val averageMinutes: Int get() = (minutesByDay.take(AveragedDays).sum().toDouble() / AveragedDays).roundToInt()

    companion object {
        const val DaysShown = 8
        const val AveragedDays = DaysShown - 1
    }
}

/**
 * The week from sessions (this device's and synced ones), counted as Statistics counts them: sessions of a minute or
 * more, on the day they started.
 */
fun readingWeek(sessions: List<ReadingSession>, today: LocalDate, zone: ZoneId): ReadingWeek {
    val first = today.minusDays((ReadingWeek.DaysShown - 1).toLong())
    val seconds = LongArray(ReadingWeek.DaysShown)
    sessions.forEach { session ->
        if (session.durationSeconds < MinCountedSessionSeconds) return@forEach
        val day = Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate()
        val index = (day.toEpochDay() - first.toEpochDay()).toInt()
        if (index in seconds.indices) seconds[index] += session.durationSeconds
    }
    return ReadingWeek(today, seconds.map { (it / SecondsPerMinute).toInt() })
}

/** Colours the chart is drawn in; the widget resolves them from the app's theme. */
class ReadingChartColors(
    val plotBackground: Int,
    val grid: Int,
    val frame: Int,
    val line: Int,
    val average: Int,
    val textPrimary: Int,
    val textMuted: Int,
    /** Fill of the past points' rings, the widget's own surface. */
    val surface: Int,
    /** E-ink has no hue to tell the average from the line, so the average is dashed there. */
    val eInk: Boolean,
)

/** Labels under and beside the plot. */
class ReadingChartLabels(val days: List<String>) {
    init {
        require(days.size == ReadingWeek.DaysShown)
    }
}

/**
 * The chart: a framed grid with three equal steps, the week in [style] (a line of straight segments or a smooth curve
 * with a point per day, today's larger and on the right edge, or a bar per day), and the seven-day average as a
 * horizontal line. [showAxisLabels] adds the day and hour labels when there is room. [density] and [scaledDensity] turn
 * dp and sp into pixels.
 */
fun drawReadingTimeChart(
    week: ReadingWeek,
    labels: ReadingChartLabels,
    colors: ReadingChartColors,
    widthPx: Int,
    heightPx: Int,
    density: Float,
    scaledDensity: Float,
    style: WidgetChartStyle = WidgetChartStyle.LINE,
    showAxisLabels: Boolean = true,
    gridOpacity: Int = 100,
): Bitmap {
    val bitmap = createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
    val canvas = Canvas(bitmap)
    val showLabels = chartLabelsShown(showAxisLabels, heightPx, density)
    // Points sit on the day lines; bars sit between them, one column per day.
    val bars = style == WidgetChartStyle.BARS
    val spans = if (bars) ReadingWeek.DaysShown else ReadingWeek.DaysShown - 1
    val step = week.scaleStep()
    val axis = axisPaint(scaledDensity).apply { color = colors.textMuted }
    val axisLabels = (0..Intervals).map { axisLabel(step * it) }
    val (leftInset, rightInset) = plotInsets(week, showLabels, density, scaledDensity)
    val left = leftInset
    val right = widthPx - rightInset
    val top = TopInsetDp * density
    val bottom = (if (showLabels) heightPx - LabelBandDp * density else heightPx - TopInsetDp * density)
        .coerceAtLeast(top + 1f)

    val yMax = step * Intervals.toFloat()
    fun x(index: Int) = left + (right - left) * (index + if (bars) 0.5f else 0f) / spans
    fun y(minutes: Number) = bottom - (bottom - top) * (minutes.toFloat() / yMax).coerceIn(0f, 1f)

    // A softly rounded plot: everything inside it is clipped to the corners; the line and its points are not, so
    // today's point on the right edge stays whole.
    val corner = PlotCornerDp * density
    val plot = Path().apply { addRoundRect(left, top, right, bottom, corner, corner, Path.Direction.CW) }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.style = Paint.Style.FILL
    paint.color = colors.plotBackground
    canvas.drawPath(plot, paint)
    canvas.save()
    canvas.clipPath(plot)

    // Graph paper: fine lines between the steps and between the days, then the steps and days a little bolder, then
    // the frame. Only as many fine lines as leave cells of MinCellDp, so a small widget stays calm.
    val rows = (((bottom - top) / Intervals / density / MinCellDp).toInt()).coerceIn(1, HorizontalSubdivisions)
    val columns = (((right - left) / spans / density / MinCellDp).toInt()).coerceIn(1, VerticalSubdivisions)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = MinorGridStrokeDp * density
    val gridAlpha = (255 * gridOpacity.coerceIn(0, 100) / 100f).roundToInt()
    paint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(colors.grid, colors.frame, MinorGridBlend), gridAlpha)
    for (line in 1 until Intervals * rows) {
        if (line % rows == 0) continue
        val ly = y(step * line / rows.toFloat())
        canvas.drawLine(left, ly, right, ly, paint)
    }
    for (line in 1 until spans * columns) {
        if (line % columns == 0) continue
        val lx = left + (right - left) * line / (spans * columns)
        canvas.drawLine(lx, top, lx, bottom, paint)
    }
    paint.strokeWidth = GridStrokeDp * density
    paint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(colors.grid, colors.frame, GridBlend), gridAlpha)
    for (interval in 1 until Intervals) canvas.drawLine(left, y(step * interval), right, y(step * interval), paint)
    for (span in 1 until spans) {
        val sx = left + (right - left) * span / spans
        canvas.drawLine(sx, top, sx, bottom, paint)
    }

    val last = ReadingWeek.DaysShown - 1
    val path = Path()
    paint.style = Paint.Style.FILL
    if (bars) {
        val half = (right - left) / spans * BarWidthFraction / 2
        val radius = BarRadiusDp * density
        week.minutesByDay.forEachIndexed { index, minutes ->
            if (minutes <= 0) return@forEachIndexed
            val barTop = y(minutes)
            paint.color = if (index == last) colors.line else ColorUtils.setAlphaComponent(colors.line, PastBarAlpha)
            // Rounded at the top, square on the baseline.
            canvas.drawRoundRect(x(index) - half, barTop, x(index) + half, bottom, radius, radius, paint)
            canvas.drawRect(x(index) - half, (barTop + radius).coerceAtMost(bottom), x(index) + half, bottom, paint)
        }
    } else {
        week.minutesByDay.forEachIndexed { index, minutes ->
            when {
                index == 0 -> path.moveTo(x(index), y(minutes))
                // Control points level with each end, half a day apart: smooth, and never past the days it joins.
                style == WidgetChartStyle.CURVE -> {
                    val mid = (x(index - 1) + x(index)) / 2
                    path.cubicTo(mid, y(week.minutesByDay[index - 1]), mid, y(minutes), x(index), y(minutes))
                }
                else -> path.lineTo(x(index), y(minutes))
            }
        }
        val area = Path(path).apply {
            lineTo(x(last), bottom)
            lineTo(x(0), bottom)
            close()
        }
        paint.color = ColorUtils.setAlphaComponent(colors.line, AreaAlpha)
        canvas.drawPath(area, paint)
    }

    if (week.hasHistory) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = AverageStrokeDp * density
        paint.color = colors.average
        if (colors.eInk) paint.pathEffect = DashPathEffect(floatArrayOf(DashDp * density, DashDp * density), 0f)
        canvas.drawLine(left, y(week.averageMinutes), right, y(week.averageMinutes), paint)
        paint.pathEffect = null
    }
    canvas.restore()

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = FrameStrokeDp * density
    paint.color = colors.frame
    canvas.drawPath(plot, paint)

    if (showLabels) {
        // Centred on its grid line by the font's own metrics.
        val centre = (axis.fontMetrics.ascent + axis.fontMetrics.descent) / 2
        axisLabels.forEachIndexed { interval, text -> canvas.drawText(text, 0f, y(step * interval) - centre, axis) }
    }

    if (!bars) drawLineAndPoints(canvas, path, week, colors, density, style, ::x, ::y)

    if (showLabels) {
        val day = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = DaySp * scaledDensity
            textAlign = Paint.Align.CENTER
        }
        labels.days.forEachIndexed { index, text ->
            val isToday = index == last
            day.color = if (isToday) colors.textPrimary else colors.textMuted
            day.isFakeBoldText = isToday
            canvas.drawText(text, x(index), bottom + DayBaselineDp * density, day)
        }
    }
    return bitmap
}

private fun drawLineAndPoints(
    canvas: Canvas,
    path: Path,
    week: ReadingWeek,
    colors: ReadingChartColors,
    density: Float,
    style: WidgetChartStyle,
    x: (Int) -> Float,
    y: (Number) -> Float,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = LineStrokeDp * density
    paint.color = colors.line
    val curve = style == WidgetChartStyle.CURVE
    paint.strokeJoin = if (curve) Paint.Join.ROUND else Paint.Join.MITER
    paint.strokeCap = if (curve) Paint.Cap.ROUND else Paint.Cap.SQUARE
    canvas.drawPath(path, paint)

    val last = ReadingWeek.DaysShown - 1
    val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    for (index in 0 until last) {
        ring.style = Paint.Style.FILL
        ring.color = colors.surface
        canvas.drawCircle(x(index), y(week.minutesByDay[index]), PastDotDp * density, ring)
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = DotStrokeDp * density
        ring.color = colors.line
        canvas.drawCircle(x(index), y(week.minutesByDay[index]), PastDotDp * density, ring)
    }
    ring.style = Paint.Style.FILL
    ring.color = colors.line
    canvas.drawCircle(x(last), y(week.todayMinutes), TodayDotDp * density, ring)
    ring.style = Paint.Style.STROKE
    ring.strokeWidth = DotStrokeDp * density
    ring.color = colors.surface
    canvas.drawCircle(x(last), y(week.todayMinutes), TodayDotDp * density, ring)
}

private fun ReadingWeek.scaleStep() = scaleStep(max(minutesByDay.max(), if (hasHistory) averageMinutes else 0))

private fun axisPaint(scaledDensity: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.DEFAULT
    textSize = AxisSp * scaledDensity
}

/** Whether the day and hour labels draw: asked for, and the chart tall enough to hold them. */
internal fun chartLabelsShown(showAxisLabels: Boolean, heightPx: Int, density: Float): Boolean =
    showAxisLabels && heightPx >= MinLabelledHeightDp * density

/**
 * How far the plot box sits in from the chart's left and right edges, in px. The left inset is the hour-label gutter
 * (the widest label plus a gap) or, without labels, room for the first point; the widget pads its header by the same
 * amounts so the header text lines up with the box.
 */
internal fun plotInsets(week: ReadingWeek, showLabels: Boolean, density: Float, scaledDensity: Float): Pair<Float, Float> {
    val left = if (showLabels) {
        val axis = axisPaint(scaledDensity)
        val step = week.scaleStep()
        (0..Intervals).maxOf { axis.measureText(axisLabel(step * it)) } + AxisGapDp * density
    } else {
        PointRadiusInsetDp * density
    }
    return left to RightInsetDp * density
}

/** The y-axis step in minutes: the smallest of a few round values whose three intervals hold [maxMinutes]. */
internal fun scaleStep(maxMinutes: Int): Int =
    ScaleSteps.firstOrNull { it * Intervals >= maxMinutes }
        ?: (((maxMinutes + Intervals - 1) / Intervals + LongStepRound - 1) / LongStepRound * LongStepRound)

/** A tick label: `0`, `30m`, `1h`, `1h30`. */
internal fun axisLabel(minutes: Int): String = when {
    minutes == 0 -> "0"
    minutes < MinutesPerHour -> "${minutes}m"
    minutes % MinutesPerHour == 0 -> "${minutes / MinutesPerHour}h"
    else -> "${minutes / MinutesPerHour}h${minutes % MinutesPerHour}"
}

private val ScaleSteps = listOf(10, 20, 30, 40, 60, 90, 120, 180)
private const val Intervals = 3
private const val MinCellDp = 10f
private const val HorizontalSubdivisions = 4
private const val VerticalSubdivisions = 4
private const val LongStepRound = 60
private const val MinutesPerHour = 60
private const val MinCountedSessionSeconds = 60L
private const val SecondsPerMinute = 60L
private const val MinLabelledHeightDp = 64f
private const val AxisGapDp = 6f
private const val LabelBandDp = 22f
private const val DayBaselineDp = 16f
private const val TopInsetDp = 5f
private const val RightInsetDp = 5f
private const val PointRadiusInsetDp = 5f
private const val MinorGridStrokeDp = 0.75f
private const val GridStrokeDp = 1.25f
private const val MinorGridBlend = 0.45f
private const val GridBlend = 0.75f
private const val FrameStrokeDp = 1.5f
private const val PlotCornerDp = 6f
private const val AverageStrokeDp = 1.5f
private const val LineStrokeDp = 2f
private const val PastDotDp = 3f
private const val TodayDotDp = 5f
private const val DotStrokeDp = 1.5f
private const val DashDp = 4f
private const val AxisSp = 10f
private const val DaySp = 10f
private const val AreaAlpha = 31
private const val BarWidthFraction = 0.56f
private const val BarRadiusDp = 3f
private const val PastBarAlpha = 150

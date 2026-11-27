package com.zenread.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zenread.data.stats.DayTotal
import com.zenread.data.stats.ReadingStats
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.ceil

/** Colors shared by the charts, all from the theme so both light and dark work. */
internal data class ChartColors(
    val read: Color,
    val listen: Color,
    val empty: Color,
    val grid: Color,
    val label: Color,
    val selection: Color,
) {
    /** Heat-map shade for level 0–4: from the empty cell color to full primary. */
    fun heat(level: Int): Color = when (level) {
        0 -> empty
        else -> lerp(empty, read, HEAT_STEPS[level.coerceIn(1, 4) - 1])
    }

    private companion object {
        val HEAT_STEPS = floatArrayOf(0.3f, 0.52f, 0.76f, 1f)
    }
}

@Composable
internal fun chartColors(): ChartColors {
    val scheme = MaterialTheme.colorScheme
    return ChartColors(
        read = scheme.primary,
        listen = scheme.tertiary,
        empty = scheme.surfaceContainerHighest,
        grid = scheme.outlineVariant,
        label = scheme.onSurfaceVariant,
        selection = scheme.onSurface,
    )
}

/**
 * Stacked daily bars (reading at the bottom, listening on top) for [days], oldest first.
 * Tapping a bar selects its day.
 */
@Composable
internal fun DailyBarChart(
    days: List<DayTotal>,
    selectedDay: Long?,
    onSelect: (Long) -> Unit,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.label)
    val resources = LocalResources.current
    val scaleMs = remember(days) { niceScale(days.maxOfOrNull { it.totalMs } ?: 0) }
    val scaleLabel = resources.formatDuration(scaleMs)
    val labels = remember(days, locale) { barLabels(days, locale) }
    val description = resources.getQuantityString(
        R.plurals.stats_chart_description,
        days.size,
        days.size,
        resources.formatDuration(days.sumOf { it.totalMs }),
    )

    Canvas(
        modifier
            .fillMaxWidth()
            .height(176.dp)
            .semantics { contentDescription = description }
            .pointerInput(days) {
                detectTapGestures { offset ->
                    if (days.isEmpty()) return@detectTapGestures
                    val index = (offset.x / (size.width.toFloat() / days.size)).toInt().coerceIn(0, days.lastIndex)
                    onSelect(days[index].day)
                }
            },
    ) {
        if (days.isEmpty()) return@Canvas
        val labelHeight = 20.dp.toPx()
        val top = 16.dp.toPx()
        val chartBottom = size.height - labelHeight
        val chartHeight = chartBottom - top
        val slot = size.width / days.size
        val barWidth = minOf(slot * if (days.size <= 7) 0.5f else 0.62f, 28.dp.toPx())
        val radius = minOf(barWidth / 2, 6.dp.toPx())

        // Scale line at the top, with its value; baseline at the bottom.
        drawLine(
            colors.grid,
            Offset(0f, top),
            Offset(size.width, top),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
        drawText(measurer, scaleLabel, Offset(0f, 0f), labelStyle)
        drawLine(colors.grid, Offset(0f, chartBottom), Offset(size.width, chartBottom), strokeWidth = 1.dp.toPx())

        days.forEachIndexed { index, day ->
            val centerX = slot * index + slot / 2
            val left = centerX - barWidth / 2
            val dim = selectedDay != null && day.day != selectedDay
            val alpha = if (dim) 0.5f else 1f
            if (day.totalMs <= 0) {
                val stub = 3.dp.toPx()
                drawRoundRect(colors.empty, Offset(left, chartBottom - stub), Size(barWidth, stub), CornerRadius(stub / 2))
            } else {
                val total = (day.totalMs.toFloat() / scaleMs * chartHeight).coerceAtLeast(3.dp.toPx())
                val readHeight = total * day.readMs / day.totalMs
                val barTop = chartBottom - total
                val shape = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left, barTop, left + barWidth, chartBottom,
                            topLeftCornerRadius = CornerRadius(radius),
                            topRightCornerRadius = CornerRadius(radius),
                        ),
                    )
                }
                clipPath(shape) {
                    drawRect(colors.listen.copy(alpha = alpha), Offset(left, barTop), Size(barWidth, total - readHeight))
                    drawRect(colors.read.copy(alpha = alpha), Offset(left, chartBottom - readHeight), Size(barWidth, readHeight))
                }
            }
            labels[index]?.let { text ->
                val layout = measurer.measure(text, labelStyle)
                val x = (centerX - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, chartBottom + 4.dp.toPx()))
            }
        }
    }
}

/** Axis labels: weekday names for a week, a date every seventh day (ending today) for a month. */
private fun barLabels(days: List<DayTotal>, locale: Locale): List<String?> {
    val dayFormat = datePattern(locale, "Md")
    return days.mapIndexed { index, day ->
        val date = LocalDate.ofEpochDay(day.day)
        when {
            days.size <= 7 -> date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, locale)
            (days.lastIndex - index) % 7 == 0 -> date.format(dayFormat)
            else -> null
        }
    }
}

/** Top of the bar scale: the longest day rounded up to 15 minutes (up to an hour) or to whole hours. */
internal fun niceScale(maxMs: Long): Long {
    val minutes = ceil(maxMs / 60_000.0).toLong()
    val rounded = when {
        minutes <= 15 -> 15
        minutes <= 60 -> (minutes + 14) / 15 * 15
        else -> (minutes + 59) / 60 * 60
    }
    return rounded * 60_000
}

private val CELL = 12.dp
private val GAP = 3.dp
private val MONTH_ROW = 18.dp
internal const val HEATMAP_WEEKS = 53

/**
 * GitHub-style grid of the last [HEATMAP_WEEKS] weeks: one column per week (starting on
 * [firstDayOfWeek]), one cell per day, shaded by reading time. Scrolls horizontally and
 * opens scrolled to the current week.
 */
@Composable
internal fun ReadingHeatmap(
    days: Map<Long, DayTotal>,
    today: Long,
    firstDayOfWeek: DayOfWeek,
    selectedDay: Long?,
    onSelect: (Long) -> Unit,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.label)
    val start = ReadingStats.weekStart(today, firstDayOfWeek) - (HEATMAP_WEEKS - 1) * 7L
    val resources = LocalResources.current
    val description = resources.getString(
        R.string.stats_heatmap_description,
        resources.formatDuration(days.values.filter { it.day in start..today }.sumOf { it.totalMs }),
    )
    // Weekday names for the 2nd, 4th and 6th rows, as on GitHub.
    val weekdayLabels = remember(firstDayOfWeek, locale) {
        listOf(1, 3, 5).associateWith { firstDayOfWeek.plus(it.toLong()).getDisplayName(DateTextStyle.SHORT, locale) }
    }
    val gridHeight = MONTH_ROW + (CELL + GAP) * 7

    Row(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Canvas(Modifier.width(32.dp).height(gridHeight)) {
            weekdayLabels.forEach { (row, text) ->
                val layout = measurer.measure(text, labelStyle)
                val y = MONTH_ROW.toPx() + row * (CELL + GAP).toPx() + (CELL.toPx() - layout.size.height) / 2
                drawText(layout, topLeft = Offset(0f, y))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true)) {
            Canvas(
                Modifier
                    .width((CELL + GAP) * HEATMAP_WEEKS)
                    .height(gridHeight)
                    .pointerInput(start, today) {
                        detectTapGestures { offset ->
                            val step = (CELL + GAP).toPx()
                            val column = (offset.x / step).toInt()
                            val row = ((offset.y - MONTH_ROW.toPx()) / step).toInt()
                            if (row !in 0..6 || column !in 0 until HEATMAP_WEEKS) return@detectTapGestures
                            val day = start + column * 7L + row
                            if (day <= today) onSelect(day)
                        }
                    },
            ) {
                drawHeatmap(days, start, today, selectedDay, colors, measurer, labelStyle, locale)
            }
        }
    }
}

private fun DrawScope.drawHeatmap(
    days: Map<Long, DayTotal>,
    start: Long,
    today: Long,
    selectedDay: Long?,
    colors: ChartColors,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    locale: Locale,
) {
    val cell = CELL.toPx()
    val step = (CELL + GAP).toPx()
    val top = MONTH_ROW.toPx()
    val corner = CornerRadius(3.dp.toPx())
    var lastLabelColumn = -10
    for (column in 0 until HEATMAP_WEEKS) {
        val weekStart = start + column * 7L
        // Month name above the first week that starts in a new month.
        val month = LocalDate.ofEpochDay(weekStart).month
        val previous = LocalDate.ofEpochDay(weekStart - 7).month
        if ((column == 0 || month != previous) && column - lastLabelColumn >= 3) {
            drawText(measurer, month.getDisplayName(DateTextStyle.SHORT, locale), Offset(column * step, 0f), labelStyle)
            lastLabelColumn = column
        }
        for (row in 0..6) {
            val day = weekStart + row
            if (day > today) break
            val topLeft = Offset(column * step, top + row * step)
            val level = ReadingStats.heatLevel(days[day]?.totalMs ?: 0)
            drawRoundRect(colors.heat(level), topLeft, Size(cell, cell), corner)
            if (day == selectedDay) {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    colors.selection,
                    topLeft + Offset(-stroke / 2, -stroke / 2),
                    Size(cell + stroke, cell + stroke),
                    corner,
                    style = Stroke(stroke),
                )
            }
        }
    }
}

/** "Less ■■■■■ More" key for the heat map. */
@Composable
internal fun HeatmapLegend(less: String, more: String, modifier: Modifier = Modifier) {
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.label)
    val lessLayout = remember(less, style) { measurer.measure(less, style) }
    val moreLayout = remember(more, style) { measurer.measure(more, style) }
    Canvas(modifier.height(CELL).fillMaxWidth()) {
        val cell = CELL.toPx()
        val gap = GAP.toPx()
        val spacing = 6.dp.toPx()
        val swatches = 5 * cell + 4 * gap
        var x = size.width - (lessLayout.size.width + spacing + swatches + spacing + moreLayout.size.width)
        drawText(lessLayout, topLeft = Offset(x, (cell - lessLayout.size.height) / 2))
        x += lessLayout.size.width + spacing
        for (level in 0..4) {
            drawRoundRect(colors.heat(level), Offset(x, 0f), Size(cell, cell), CornerRadius(3.dp.toPx()))
            x += cell + gap
        }
        x += spacing - gap
        drawText(moreLayout, topLeft = Offset(x, (cell - moreLayout.size.height) / 2))
    }
}

/** A small round key next to a series name. */
@Composable
internal fun LegendDot(color: Color, size: Dp = 8.dp) {
    Canvas(Modifier.width(size).height(size)) { drawCircle(color) }
}

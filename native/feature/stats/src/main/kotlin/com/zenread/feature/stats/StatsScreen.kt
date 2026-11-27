package com.zenread.feature.stats

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.zenread.core.designsystem.component.EmptyState
import com.zenread.core.designsystem.motion.ZenMotion
import com.zenread.data.stats.BookReading
import com.zenread.data.stats.DayTotal
import com.zenread.data.stats.ReadingStats
import java.io.File
import java.time.LocalDate
import java.util.Locale

private enum class ChartRange(val days: Int, val label: Int) {
    WEEK(7, R.string.stats_range_week),
    MONTH(30, R.string.stats_range_month),
}

/** Reading statistics: overview, daily chart, year heat map and time per book. */
@Composable
fun StatsScreen(
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val current = state
        when {
            current == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { LoadingIndicator() }
            current.isEmpty -> EmptyState(
                icon = Icons.Rounded.Insights,
                title = stringResource(R.string.stats_empty_title),
                body = stringResource(R.string.stats_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> StatsContent(current, padding, onOpenBook)
        }
    }
}

@Composable
private fun StatsContent(state: StatsUiState, padding: PaddingValues, onOpenBook: (Long) -> Unit) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "overview") { Overview(state) }
        item(key = "daily") { DailyCard(state, locale) }
        item(key = "heatmap") { HeatmapCard(state, locale) }
        if (state.books.isNotEmpty()) {
            item(key = "books-title") {
                Text(
                    stringResource(R.string.stats_books_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
            }
            items(state.books, key = { it.bookId }) { book ->
                BookRow(book, onClick = { onOpenBook(book.bookId) })
            }
        }
    }
}

@Composable
private fun Overview(state: StatsUiState) {
    val overview = state.overview
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.stats_today), style = MaterialTheme.typography.labelLarge)
                    Text(formatDuration(overview.todayMs), style = MaterialTheme.typography.displaySmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(MaterialShapes.Cookie7Sided.toShape())
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Text(
                        pluralStringResource(R.plurals.stats_streak_days, overview.streakDays, overview.streakDays),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(stringResource(R.string.stats_streak), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Icons.Rounded.DateRange, stringResource(R.string.stats_this_week), formatDuration(overview.weekMs), Modifier.weight(1f))
            StatTile(Icons.Rounded.Schedule, stringResource(R.string.stats_total), formatDuration(overview.totalMs), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                Icons.Rounded.TaskAlt,
                stringResource(R.string.stats_finished),
                pluralStringResource(R.plurals.stats_books_count, state.finishedBooks, state.finishedBooks),
                Modifier.weight(1f),
            )
            StatTile(Icons.Rounded.Headphones, stringResource(R.string.stats_listening), formatDuration(overview.listenMs), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChartCard(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun DailyCard(state: StatsUiState, locale: Locale) {
    var range by rememberSaveable { mutableStateOf(ChartRange.WEEK) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedDay = selected ?: state.today
    val motion = MaterialTheme.motionScheme
    ChartCard {
        Text(stringResource(R.string.stats_daily_title), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ChartRange.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = range == option,
                    onClick = { range = option },
                    shape = SegmentedButtonDefaults.itemShape(index, ChartRange.entries.size),
                ) { Text(stringResource(option.label)) }
            }
        }
        DaySummary(state.days[selectedDay] ?: DayTotal(selectedDay), locale)
        AnimatedContent(
            targetState = range,
            transitionSpec = { ZenMotion.fadeThrough(motion) },
            label = "stats-range",
        ) { shown ->
            DailyBarChart(
                days = ReadingStats.lastDays(state.days, state.today, shown.days),
                selectedDay = selectedDay,
                onSelect = { selected = it },
                locale = locale,
            )
        }
    }
}

/** Date, total and the reading / listening split of one day; also the chart's legend. */
@Composable
private fun DaySummary(day: DayTotal, locale: Locale) {
    val colors = chartColors()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                LocalDate.ofEpochDay(day.day).format(locale, "yMMMdEEE"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(formatDuration(day.totalMs), style = MaterialTheme.typography.titleMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendDot(colors.read)
            Text(
                "${stringResource(R.string.stats_reading)} ${formatDuration(day.readMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            LegendDot(colors.listen)
            Text(
                "${stringResource(R.string.stats_listening)} ${formatDuration(day.listenMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeatmapCard(state: StatsUiState, locale: Locale) {
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedDay = selected ?: state.today
    val day = state.days[selectedDay] ?: DayTotal(selectedDay)
    ChartCard {
        Text(stringResource(R.string.stats_heatmap_title), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                LocalDate.ofEpochDay(selectedDay).format(locale, "yMMMdEEE"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(formatDuration(day.totalMs), style = MaterialTheme.typography.titleMedium)
        }
        ReadingHeatmap(
            days = state.days,
            today = state.today,
            firstDayOfWeek = state.firstDayOfWeek,
            selectedDay = selectedDay,
            onSelect = { selected = it },
            locale = locale,
        )
        HeatmapLegend(stringResource(R.string.stats_heatmap_less), stringResource(R.string.stats_heatmap_more))
    }
}

@Composable
private fun BookRow(book: BookReading, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val lastRead = DateUtils.getRelativeTimeSpanString(book.lastReadAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Cover(book)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.stats_book_summary, formatDuration(book.totalMs), lastRead),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val extras = buildList {
                    if (book.listenMs > 0) add(stringResource(R.string.stats_book_listening, formatDuration(book.listenMs)))
                    if (book.finished) add(stringResource(R.string.stats_book_finished))
                }
                if (extras.isNotEmpty()) {
                    Text(
                        extras.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Cover(book: BookReading) {
    val modifier = Modifier.width(44.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small)
    val path = book.coverPath
    if (path != null) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(
                book.title.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

package com.zenread.data.stats

import java.time.DayOfWeek
import java.time.LocalDate

/** Reading time of one local day ([day] = `LocalDate.toEpochDay()`). */
data class DayTotal(
    val day: Long,
    val readMs: Long = 0,
    val listenMs: Long = 0,
    val pages: Int = 0,
) {
    val totalMs: Long get() = readMs + listenMs
}

/** Time spent in one mode on one day, as stored (one row per stretch, or summed per day). */
data class DayModeTotal(val day: Long, val mode: ReadingMode, val durationMs: Long, val pages: Int)

/** Headline numbers of the statistics page. */
data class ReadingOverview(
    val todayMs: Long,
    /** Consecutive days with reading, up to today (or up to yesterday while today is still empty). */
    val streakDays: Int,
    /** Since the start of the current week (locale's first day of week). */
    val weekMs: Long,
    val totalMs: Long,
    val listenMs: Long,
    val pages: Int,
)

/** Reading time per book. */
data class BookReading(
    val bookId: Long,
    val title: String,
    val author: String,
    val coverPath: String?,
    val totalMs: Long,
    val listenMs: Long,
    val lastReadAt: Long,
    val finished: Boolean,
)

/** Pure aggregation over per-day totals. */
object ReadingStats {
    /** Sums rows (one per stretch, or per day and mode) into one total per day. */
    fun aggregateByDay(rows: Iterable<DayModeTotal>): Map<Long, DayTotal> {
        val days = HashMap<Long, DayTotal>()
        for (row in rows) {
            val current = days[row.day] ?: DayTotal(row.day)
            days[row.day] = when (row.mode) {
                ReadingMode.READ -> current.copy(readMs = current.readMs + row.durationMs, pages = current.pages + row.pages)
                ReadingMode.LISTEN -> current.copy(listenMs = current.listenMs + row.durationMs, pages = current.pages + row.pages)
            }
        }
        return days
    }

    /** [ReadingSpan]s as rows for [aggregateByDay]. */
    fun rowsOf(spans: Iterable<ReadingSpan>): List<DayModeTotal> =
        spans.map { DayModeTotal(it.localDay, it.mode, it.durationMs, it.pages) }

    /**
     * Days in a row with any reading, ending today. A day without reading yet does not break
     * the streak until it is over, so the count ends at yesterday while today is empty.
     */
    fun streak(days: Map<Long, DayTotal>, today: Long): Int {
        fun read(day: Long) = (days[day]?.totalMs ?: 0) > 0
        var day = if (read(today)) today else today - 1
        var count = 0
        while (read(day)) {
            count++
            day--
        }
        return count
    }

    /** First day of the week containing [today], for a locale whose week starts on [firstDayOfWeek]. */
    fun weekStart(today: Long, firstDayOfWeek: DayOfWeek): Long {
        val date = LocalDate.ofEpochDay(today)
        val back = (date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        return today - back
    }

    /** Totals of the [count] days ending with [today], oldest first; days without reading are zero. */
    fun lastDays(days: Map<Long, DayTotal>, today: Long, count: Int): List<DayTotal> =
        (today - count + 1..today).map { days[it] ?: DayTotal(it) }

    fun overview(days: Map<Long, DayTotal>, today: Long, firstDayOfWeek: DayOfWeek): ReadingOverview {
        val weekStart = weekStart(today, firstDayOfWeek)
        return ReadingOverview(
            todayMs = days[today]?.totalMs ?: 0,
            streakDays = streak(days, today),
            weekMs = days.values.filter { it.day in weekStart..today }.sumOf { it.totalMs },
            totalMs = days.values.sumOf { it.totalMs },
            listenMs = days.values.sumOf { it.listenMs },
            pages = days.values.sumOf { it.pages },
        )
    }

    /** Heat-map shade 0–4 for a day's reading time. */
    fun heatLevel(totalMs: Long): Int = when {
        totalMs <= 0 -> 0
        totalMs < 15 * MINUTE -> 1
        totalMs < 30 * MINUTE -> 2
        totalMs < 60 * MINUTE -> 3
        else -> 4
    }

    private const val MINUTE = 60_000L
}

/** When a book counts as read to the end. */
object FinishRule {
    const val FINISHED_FRACTION = 0.99f

    /**
     * Finished at [FINISHED_FRACTION] of the book, or on the last page of the last section
     * (1-based [section]; for PDF and CBZ a section is one page). A single-section book only
     * finishes by fraction, otherwise opening it would count.
     */
    fun isFinished(fraction: Float, section: Int = 0, sections: Int = 0, chapterPage: Int = 0, chapterPages: Int = 0): Boolean {
        if (fraction >= FINISHED_FRACTION) return true
        if (sections < 2 || section < sections) return false
        return chapterPages <= 0 || chapterPage >= chapterPages
    }
}

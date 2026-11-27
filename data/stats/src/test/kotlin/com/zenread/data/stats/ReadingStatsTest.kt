package com.zenread.data.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStatsTest {
    private val today = LocalDate.of(2026, 11, 27).toEpochDay() // a Friday

    private fun days(vararg minutesAgo: Pair<Int, Long>): Map<Long, DayTotal> =
        minutesAgo.associate { (daysAgo, minutes) -> (today - daysAgo) to DayTotal(today - daysAgo, readMs = minutes * MINUTE) }

    @Test
    fun aggregatesRowsPerDayAndMode() {
        val rows = listOf(
            DayModeTotal(today, ReadingMode.READ, 10 * MINUTE, 4),
            DayModeTotal(today, ReadingMode.READ, 5 * MINUTE, 2),
            DayModeTotal(today, ReadingMode.LISTEN, 20 * MINUTE, 0),
            DayModeTotal(today - 1, ReadingMode.LISTEN, 7 * MINUTE, 1),
        )
        val days = ReadingStats.aggregateByDay(rows)
        assertEquals(DayTotal(today, readMs = 15 * MINUTE, listenMs = 20 * MINUTE, pages = 6), days[today])
        assertEquals(35 * MINUTE, days[today]!!.totalMs)
        assertEquals(DayTotal(today - 1, listenMs = 7 * MINUTE, pages = 1), days[today - 1])
    }

    @Test
    fun aggregatesTimerSpansAcrossMidnightIntoTwoDays() {
        val clock = MutableClock(LocalDateTime.of(2026, 11, 27, 23, 45))
        val timer = ReadingTimer(clock)
        timer.setForeground(true)
        repeat(30) {
            clock.advanceMinutes(1)
            timer.onPageTurn()
        }
        timer.setForeground(false)

        val days = ReadingStats.aggregateByDay(ReadingStats.rowsOf(timer.drain()))
        assertEquals(15 * MINUTE, days[today]!!.readMs)
        assertEquals(15 * MINUTE, days[today + 1]!!.readMs)
        assertEquals(30, days.values.sumOf { it.pages })
    }

    @Test
    fun streakCountsConsecutiveDaysEndingToday() {
        assertEquals(3, ReadingStats.streak(days(0 to 5L, 1 to 20L, 2 to 1L, 4 to 30L), today))
    }

    @Test
    fun streakSurvivesUntilTodayIsOver() {
        assertEquals("today not read yet", 2, ReadingStats.streak(days(1 to 5L, 2 to 5L), today))
        assertEquals("yesterday missed", 0, ReadingStats.streak(days(2 to 5L, 3 to 5L), today))
        assertEquals(0, ReadingStats.streak(emptyMap(), today))
    }

    @Test
    fun streakIgnoresDaysWithZeroTime() {
        val map = days(0 to 5L) + ((today - 1) to DayTotal(today - 1)) + days(2 to 5L)
        assertEquals(1, ReadingStats.streak(map, today))
    }

    @Test
    fun listeningKeepsTheStreak() {
        val map = mapOf(today to DayTotal(today, listenMs = MINUTE), today - 1 to DayTotal(today - 1, readMs = MINUTE))
        assertEquals(2, ReadingStats.streak(map, today))
    }

    @Test
    fun weekStartFollowsTheLocale() {
        assertEquals(LocalDate.of(2026, 11, 23).toEpochDay(), ReadingStats.weekStart(today, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 11, 22).toEpochDay(), ReadingStats.weekStart(today, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 11, 21).toEpochDay(), ReadingStats.weekStart(today, DayOfWeek.SATURDAY))
        assertEquals(today, ReadingStats.weekStart(today, DayOfWeek.FRIDAY))
    }

    @Test
    fun overviewSumsTodayWeekAndTotal() {
        val map = days(0 to 10L, 4 to 20L, 5 to 40L, 30 to 60L) +
            ((today - 1) to DayTotal(today - 1, listenMs = 15 * MINUTE, pages = 3))
        val overview = ReadingStats.overview(map, today, DayOfWeek.MONDAY)
        assertEquals(10 * MINUTE, overview.todayMs)
        assertEquals(2, overview.streakDays)
        // Monday (4 days ago) to today.
        assertEquals((10 + 20 + 15) * MINUTE, overview.weekMs)
        assertEquals((10 + 20 + 40 + 60 + 15) * MINUTE, overview.totalMs)
        assertEquals(15 * MINUTE, overview.listenMs)
        assertEquals(3, overview.pages)
    }

    @Test
    fun lastDaysFillsGapsOldestFirst() {
        val list = ReadingStats.lastDays(days(0 to 10L, 2 to 5L), today, 7)
        assertEquals(7, list.size)
        assertEquals(today - 6, list.first().day)
        assertEquals(today, list.last().day)
        assertEquals(listOf(0L, 0, 0, 0, 5, 0, 10).map { it * MINUTE }, list.map { it.totalMs })
    }

    @Test
    fun heatLevels() {
        assertEquals(0, ReadingStats.heatLevel(0))
        assertEquals(1, ReadingStats.heatLevel(MINUTE))
        assertEquals(2, ReadingStats.heatLevel(15 * MINUTE))
        assertEquals(3, ReadingStats.heatLevel(45 * MINUTE))
        assertEquals(4, ReadingStats.heatLevel(3 * 60 * MINUTE))
    }

    @Test
    fun finishRule() {
        assertTrue(FinishRule.isFinished(0.99f))
        assertFalse(FinishRule.isFinished(0.98f))
        // Last page of the last section.
        assertTrue(FinishRule.isFinished(0.9f, section = 12, sections = 12, chapterPage = 8, chapterPages = 8))
        assertFalse(FinishRule.isFinished(0.9f, section = 12, sections = 12, chapterPage = 3, chapterPages = 8))
        assertFalse(FinishRule.isFinished(0.9f, section = 11, sections = 12, chapterPage = 8, chapterPages = 8))
        // PDF: one section per page, no page count inside the section.
        assertTrue(FinishRule.isFinished(0.95f, section = 300, sections = 300))
        // A single-section book is not finished by opening it.
        assertFalse(FinishRule.isFinished(0.1f, section = 1, sections = 1, chapterPage = 1, chapterPages = 1))
    }
}

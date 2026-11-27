package com.zenread.data.stats

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTimerTest {
    private val clock = MutableClock(LocalDateTime.of(2026, 11, 27, 20, 0))
    private var ids = 0
    private val timer = ReadingTimer(clock, idleTimeoutMs = 3 * MINUTE, newId = { "s${++ids}" })

    /** Everything drained so far, merged by id (the open stretch is reported repeatedly). */
    private val written = LinkedHashMap<String, ReadingSpan>()

    private fun drain(): List<ReadingSpan> {
        timer.drain().forEach { written[it.id] = it }
        return written.values.toList()
    }

    private fun turnPagesEveryMinute(minutes: Int) = repeat(minutes) {
        clock.advanceMinutes(1)
        timer.onPageTurn()
    }

    @Test
    fun countsForegroundReadingWithPages() {
        timer.setForeground(true)
        turnPagesEveryMinute(10)
        timer.setForeground(false)

        val spans = drain()
        assertEquals(1, spans.size)
        assertEquals(10 * MINUTE, spans[0].durationMs)
        assertEquals(10, spans[0].pages)
        assertEquals(ReadingMode.READ, spans[0].mode)
        assertNull(timer.activeMode)
    }

    @Test
    fun stopsAfterIdleTimeoutAndResumesOnNextPageTurn() {
        timer.setForeground(true)
        turnPagesEveryMinute(5)
        // Staring at the screen for 20 minutes: only the 3-minute idle window counts.
        clock.advanceMinutes(10)
        timer.tick()
        assertNull("idle stops the timer", timer.activeMode)
        clock.advanceMinutes(10)
        timer.onPageTurn()
        turnPagesEveryMinute(2)
        timer.setForeground(false)

        val spans = drain()
        assertEquals(2, spans.size)
        assertEquals(8 * MINUTE, spans[0].durationMs)
        assertEquals(5, spans[0].pages)
        assertEquals(2 * MINUTE, spans[1].durationMs)
        assertEquals(3, spans[1].pages)
    }

    @Test
    fun idleGapIsNotBridgedWithoutTicks() {
        timer.setForeground(true)
        clock.advanceMinutes(30) // no tick in between
        timer.onPageTurn()
        clock.advanceMinutes(1)
        timer.setForeground(false)

        val spans = drain()
        assertEquals(listOf(3 * MINUTE, 1 * MINUTE), spans.map { it.durationMs })
    }

    @Test
    fun backgroundDoesNotCountReading() {
        timer.setForeground(true)
        clock.advanceMinutes(2)
        timer.setForeground(false)
        clock.advanceMinutes(30)
        timer.onPageTurn() // a stray event while hidden
        timer.tick()

        val spans = drain()
        assertEquals(listOf(2 * MINUTE), spans.map { it.durationMs })
    }

    @Test
    fun listeningCountsInBackgroundAndIsMarked() {
        timer.setForeground(true)
        clock.advanceMinutes(1)
        timer.setListening(true)
        timer.setForeground(false) // screen off
        repeat(40) {
            clock.advanceMinutes(1)
            timer.tick()
        }
        timer.setListening(false)

        val spans = drain()
        assertEquals(listOf(ReadingMode.READ, ReadingMode.LISTEN), spans.map { it.mode })
        assertEquals(1 * MINUTE, spans[0].durationMs)
        assertEquals(40 * MINUTE, spans[1].durationMs)
        assertNull(timer.activeMode)
    }

    @Test
    fun readingResumesAfterListeningInForeground() {
        timer.setForeground(true)
        timer.setListening(true)
        clock.advanceMinutes(5)
        timer.setListening(false)
        assertEquals(ReadingMode.READ, timer.activeMode)
        clock.advanceMinutes(10)
        timer.tick()

        val spans = drain()
        assertEquals(listOf(ReadingMode.LISTEN, ReadingMode.READ), spans.map { it.mode })
        assertEquals(listOf(5 * MINUTE, 3 * MINUTE), spans.map { it.durationMs })
    }

    @Test
    fun splitsAtLocalMidnight() {
        clock.millis = LocalDateTime.of(2026, 11, 27, 23, 50).atZone(clock.zone).toInstant().toEpochMilli()
        timer.setForeground(true)
        turnPagesEveryMinute(20) // 23:50 → 00:10

        timer.setForeground(false)
        val spans = drain()
        assertEquals(2, spans.size)
        val nov27 = LocalDate.of(2026, 11, 27).toEpochDay()
        assertEquals(nov27, spans[0].localDay)
        assertEquals(nov27 + 1, spans[1].localDay)
        assertEquals(10 * MINUTE, spans[0].durationMs)
        assertEquals(10 * MINUTE, spans[1].durationMs)
        assertEquals(spans[0].endedAt, spans[1].startedAt)
        assertEquals(20, spans.sumOf { it.pages })
        assertTrue(spans[0].id != spans[1].id)
    }

    @Test
    fun splitsListeningAcrossSeveralMidnights() {
        clock.millis = LocalDateTime.of(2026, 11, 27, 23, 0).atZone(clock.zone).toInstant().toEpochMilli()
        timer.setListening(true)
        clock.advanceMinutes(26 * 60) // 23:00 → 01:00 two days later, a tick-less stretch
        timer.setListening(false)

        val spans = drain()
        assertEquals(listOf(60 * MINUTE, 24 * 60 * MINUTE, 60 * MINUTE), spans.map { it.durationMs })
        val nov27 = LocalDate.of(2026, 11, 27).toEpochDay()
        assertEquals(listOf(nov27, nov27 + 1, nov27 + 2), spans.map { it.localDay })
    }

    @Test
    fun idleWindowCrossingMidnightEndsOnTheNextDay() {
        clock.millis = LocalDateTime.of(2026, 11, 27, 23, 58).atZone(clock.zone).toInstant().toEpochMilli()
        timer.setForeground(true)
        clock.advanceMinutes(30)
        timer.tick()

        val spans = drain()
        assertEquals(listOf(2 * MINUTE, 1 * MINUTE), spans.map { it.durationMs })
    }

    @Test
    fun localDayUsesTheZoneAtStart() {
        // 23:30 in Shanghai is 15:30 UTC: the stretch belongs to the local date.
        clock.millis = LocalDateTime.of(2026, 11, 27, 23, 30).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        timer.setForeground(true)
        clock.advanceMinutes(10)
        timer.setForeground(false)
        clock.moveTo(ZoneId.of("America/New_York"))
        timer.setForeground(true)
        clock.advanceMinutes(10)
        timer.setForeground(false)

        val spans = drain()
        assertEquals(LocalDate.of(2026, 11, 27).toEpochDay(), spans[0].localDay)
        assertEquals(480, spans[0].utcOffsetMinutes)
        // Same instant seen from New York: still the 27th there, 10:40.
        assertEquals(LocalDate.of(2026, 11, 27).toEpochDay(), spans[1].localDay)
        assertEquals(-300, spans[1].utcOffsetMinutes)
    }

    @Test
    fun drainReportsTheOpenStretchUntilItCloses() {
        timer.setForeground(true)
        clock.advanceMinutes(1)
        timer.tick()
        val first = timer.drain()
        assertEquals(1, first.size)
        clock.advanceSeconds(30)
        timer.tick()
        val second = timer.drain()
        assertEquals(first[0].id, second[0].id)
        assertEquals(90_000L, second[0].durationMs)
        timer.setForeground(false)
        assertEquals(90_000L, timer.drain().single().durationMs)
        assertTrue("nothing left once closed and drained", timer.drain().isEmpty())
    }

    @Test
    fun emptyStretchesAreDropped() {
        timer.setForeground(true)
        timer.setForeground(false)
        assertTrue(timer.drain().isEmpty())
    }

    @Test
    fun clockGoingBackwardsNeverShrinksAStretch() {
        timer.setForeground(true)
        clock.advanceMinutes(2)
        timer.tick()
        clock.advanceMinutes(-60)
        timer.onInteraction()
        clock.advanceMinutes(1)
        timer.setForeground(false)
        assertTrue(drain().all { it.durationMs >= 0 })
        assertEquals(2 * MINUTE, drain().first().durationMs)
    }
}

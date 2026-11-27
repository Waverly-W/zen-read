package com.zenread.data.stats

import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionTest {
    private val clock = MutableClock(LocalDateTime.of(2026, 11, 27, 9, 0))
    private val scope = TestScope()
    private val writes = mutableListOf<List<ReadingSpan>>()
    private val session = ReadingSession(ReadingTimer(clock), { writes += it }, scope, flushIntervalMs = 30_000)

    /** Moves the fake wall clock and the coroutine clock together. */
    private fun elapse(seconds: Long) {
        repeat(seconds.toInt()) {
            clock.advanceSeconds(1)
            scope.advanceTimeBy(1_000)
            scope.runCurrent()
        }
    }

    @Test
    fun flushesPeriodicallySoAKilledProcessLosesLittle() {
        session.setForeground(true)
        elapse(31)
        assertEquals(1, writes.size)
        assertEquals(30_000L, writes.last().single().durationMs)
        session.onPageTurn()
        elapse(30)
        assertEquals(2, writes.size)
        assertEquals("same row, extended", writes[0].single().id, writes[1].single().id)
        assertEquals(60_000L, writes.last().single().durationMs)
        assertEquals(1, writes.last().single().pages)
        session.close()
    }

    @Test
    fun skipsWritesWhileIdle() {
        session.setForeground(true)
        elapse(10 * 60)
        val count = writes.size
        elapse(10 * 60)
        assertEquals("nothing changes while idle", count, writes.size)
        assertEquals(3 * 60_000L, writes.flatten().last().durationMs)
        session.close()
    }

    @Test
    fun writesImmediatelyWhenAStretchEnds() {
        session.setForeground(true)
        elapse(5)
        session.setForeground(false)
        assertEquals(5_000L, writes.single().single().durationMs)
        session.close()
        assertEquals("close has nothing left to write", 1, writes.size)
    }

    @Test
    fun closeStopsTimingAndIgnoresLaterEvents() {
        session.setListening(true)
        elapse(12)
        session.close()
        val total = writes.flatten().associateBy { it.id }.values.sumOf { it.durationMs }
        assertEquals(12_000L, total)
        session.setListening(true)
        elapse(60)
        assertTrue(writes.flatten().all { it.mode == ReadingMode.LISTEN })
        assertEquals(total, writes.flatten().associateBy { it.id }.values.sumOf { it.durationMs })
    }
}

package com.zenread.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private val minute = 60_000L

    @Test
    fun `off by default`() {
        val timer = SleepTimer()
        assertFalse(timer.armed)
        assertNull(timer.remaining(0))
        assertFalse(timer.expired(Long.MAX_VALUE))
        assertFalse(timer.onChapter("2"))
    }

    @Test
    fun `counts down while playing`() {
        val timer = SleepTimer()
        timer.set(15, nowMillis = 1_000, playing = true, chapter = null)
        assertEquals(15 * minute, timer.remaining(1_000))
        assertEquals(10 * minute, timer.remaining(1_000 + 5 * minute))
        assertFalse(timer.expired(1_000 + 15 * minute - 1))
        assertTrue(timer.expired(1_000 + 15 * minute))
        assertEquals(0L, timer.remaining(1_000 + 20 * minute))
    }

    @Test
    fun `does not count while paused`() {
        val timer = SleepTimer()
        timer.set(30, nowMillis = 0, playing = false, chapter = null)
        assertEquals(30 * minute, timer.remaining(10 * minute))
        timer.onPlay(10 * minute)
        timer.onPause(20 * minute)
        assertEquals(20 * minute, timer.remaining(50 * minute))
        timer.onPlay(60 * minute)
        assertEquals(15 * minute, timer.remaining(65 * minute))
        // Repeated play/pause signals are harmless.
        timer.onPlay(66 * minute)
        assertEquals(14 * minute, timer.remaining(66 * minute))
    }

    @Test
    fun `end of chapter fires when the section changes`() {
        val timer = SleepTimer()
        timer.set(SleepTimerOption.END_OF_CHAPTER, 0, playing = true, chapter = "3")
        assertTrue(timer.armed)
        assertTrue(timer.endOfChapter)
        assertNull(timer.remaining(0))
        assertFalse(timer.onChapter("3"))
        assertTrue(timer.onChapter("4"))
    }

    @Test
    fun `end of chapter armed before the chapter is known adopts the first one`() {
        val timer = SleepTimer()
        timer.set(SleepTimerOption.END_OF_CHAPTER, 0, playing = false, chapter = null)
        assertFalse(timer.onChapter("7"))
        assertFalse(timer.onChapter("7"))
        assertTrue(timer.onChapter("8"))
    }

    @Test
    fun `cancel disarms`() {
        val timer = SleepTimer()
        timer.set(45, 0, playing = true, chapter = null)
        timer.cancel()
        assertFalse(timer.armed)
        assertNull(timer.remaining(minute))
        assertFalse(timer.expired(100 * minute))
    }
}

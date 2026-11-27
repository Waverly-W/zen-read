package com.zenread.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    private val minute = 60_000L

    @Test
    fun splitsIntoHoursAndMinutes() {
        assertEquals(DurationParts(1, 23), DurationParts.of(83 * minute + 59_000))
        assertEquals(DurationParts(0, 0), DurationParts.of(59_000))
        assertEquals(DurationParts(0, 0), DurationParts.of(-5))
        assertEquals(DurationParts(152, 0), DurationParts.of(152 * 60 * minute))
    }

    @Test
    fun chartScaleRoundsUp() {
        assertEquals(15 * minute, niceScale(0))
        assertEquals(15 * minute, niceScale(14 * minute))
        assertEquals(30 * minute, niceScale(16 * minute))
        assertEquals(60 * minute, niceScale(46 * minute))
        assertEquals(120 * minute, niceScale(61 * minute))
    }
}

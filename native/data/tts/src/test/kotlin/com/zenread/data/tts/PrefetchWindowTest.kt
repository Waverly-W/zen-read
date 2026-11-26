package com.zenread.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchWindowTest {
    @Test
    fun `keeps wanted entries and returns the rest for cancellation`() {
        val cache = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
        val dropped = PrefetchWindow.retain(cache, listOf("b", "c", "d"))
        assertEquals(listOf(1), dropped)
        assertEquals(mapOf("b" to 2, "c" to 3), cache)
        assertEquals(listOf(2, 3), PrefetchWindow.retain(cache, emptyList()))
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `retries transient failures only`() {
        fun http(code: Int?) = TtsException(TtsException.Reason.HTTP, "x", code)
        assertTrue(PrefetchWindow.retryable(TtsException(TtsException.Reason.NETWORK, "x")))
        assertTrue(PrefetchWindow.retryable(http(429)))
        assertTrue(PrefetchWindow.retryable(http(503)))
        assertTrue(PrefetchWindow.retryable(http(null)))
        assertFalse(PrefetchWindow.retryable(http(401)))
        assertFalse(PrefetchWindow.retryable(http(403)))
        assertFalse(PrefetchWindow.retryable(TtsException(TtsException.Reason.CONFIGURATION, "x")))
    }
}

package com.zenread.data.tts.edge

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors from upstream test/service/tts/edge_tts_test.dart (independently from edge-tts 7.3.0). */
class EdgeTtsTokenTest {
    private fun micros(iso: String): Long = Instant.parse(iso).let { it.epochSecond * 1_000_000 + it.nano / 1_000 }

    @Test
    fun `generates the current Edge time-bound token`() {
        assertEquals(
            "54404AFE67C9B44A-E72E51D8B4F5F75E9A96B7E20D7877227455BC310433BD30C817CD54A1D9313E",
            EdgeTtsProtocol.generateSecMsGec(micros("2025-01-01T00:00:00Z")),
        )
    }

    @Test
    fun `matches upstream vectors including dates after 2038`() {
        mapOf(
            "2026-10-15T00:00:00Z" to "F3FD83535256D08E-CF2FA3106FF93AB92502C05F6EF45B92187ED35987A62A77464E342750F61216",
            "2026-10-15T00:05:00Z" to "BC7569B4AA9A3A86-5299C23F1D621C77C10E9D7C28D5798AD7EBFFF02E6EC21899E3774660824207",
            "2040-01-01T00:00:00Z" to "2CFC54CBD2FE13D1-EE7F9D639CE5B91702482E38BC8A74B625D862C6F376E8BF33B9EF5E55D4FC8E",
        ).forEach { (time, token) -> assertEquals(time, token, EdgeTtsProtocol.generateSecMsGec(micros(time))) }
    }

    @Test
    fun `token is stable within a five-minute window and honours clock skew`() {
        val start = micros("2026-10-15T00:00:00Z")
        val token = EdgeTtsProtocol.generateSecMsGec(start)
        assertEquals(token, EdgeTtsProtocol.generateSecMsGec(start + 5 * 60_000_000L - 1))
        assertNotEquals(token, EdgeTtsProtocol.generateSecMsGec(start + 5 * 60_000_000L))
        assertEquals(token, EdgeTtsProtocol.generateSecMsGec(start + 3_600_000_000L, clockSkewSeconds = -3600.0))
        assertEquals(token, EdgeTtsProtocol.generateSecMsGec(start - 3_600_000_000L, clockSkewSeconds = 3600.0))
    }

    @Test
    fun `token has the expected shape`() {
        val token = EdgeTtsProtocol.generateSecMsGec(micros("2031-07-04T12:34:56Z"))
        assertTrue(token, Regex("^[A-F0-9]{16}-[A-F0-9]{64}$").matches(token))
    }
}

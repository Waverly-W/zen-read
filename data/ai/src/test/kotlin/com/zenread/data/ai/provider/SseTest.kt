package com.zenread.data.ai.provider

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class SseTest {
    private fun events(raw: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        readSse(Buffer().writeUtf8(raw)) { out += it; true }
        return out
    }

    @Test
    fun `joins multi-line data and keeps event names`() {
        val parsed = events("event: message_start\ndata: {\"a\":\ndata: 1}\n\n: keep-alive comment\n\ndata: x\n\n")
        assertEquals(listOf(SseEvent("message_start", "{\"a\":\n1}"), SseEvent(null, "x")), parsed)
    }

    @Test
    fun `strips one leading space only and accepts CRLF`() {
        assertEquals(listOf(SseEvent(null, " two spaces")), events("data:  two spaces\r\n\r\n"))
    }

    @Test
    fun `dispatches a trailing event without a blank line`() {
        assertEquals(listOf(SseEvent(null, "[DONE]")), events("data: [DONE]"))
    }

    @Test
    fun `ignores empty events and unknown fields`() {
        assertEquals(listOf(SseEvent(null, "ok")), events("id: 7\nretry: 1000\n\n\n\ndata: ok\n\n"))
    }

    @Test
    fun `stops when the consumer returns false`() {
        val out = mutableListOf<SseEvent>()
        readSse(Buffer().writeUtf8("data: 1\n\ndata: 2\n\n")) { out += it; false }
        assertEquals(1, out.size)
    }
}

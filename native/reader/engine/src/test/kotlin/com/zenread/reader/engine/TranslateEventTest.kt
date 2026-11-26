package com.zenread.reader.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslateEventTest {
    private fun args(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    @Test
    fun `parses translateText with generation and prefetch`() {
        val event = ReaderEvent.parse("translateText", args("""["Hello world", 3, 0]"""))
        assertEquals(ReaderEvent.TranslateRequest("Hello world", 3L, 0), event)
    }

    @Test
    fun `generation sent as a JS double is accepted`() {
        val event = ReaderEvent.parse("translateText", args("""["Hi", 7.0, 2]""")) as ReaderEvent.TranslateRequest
        assertEquals(7L, event.generation)
        assertEquals(2, event.prefetchPages)
    }

    @Test
    fun `missing or invalid generation is null`() {
        assertNull((ReaderEvent.parse("translateText", args("""["Hi"]""")) as ReaderEvent.TranslateRequest).generation)
        assertNull((ReaderEvent.parse("translateText", args("""["Hi", 1.5]""")) as ReaderEvent.TranslateRequest).generation)
        assertNull((ReaderEvent.parse("translateText", args("""["Hi", null]""")) as ReaderEvent.TranslateRequest).generation)
    }

    @Test
    fun `non-string text is ignored`() {
        assertNull(ReaderEvent.parse("translateText", args("""[42, 1]""")))
        assertNull(ReaderEvent.parse("translateText", args("""[]""")))
    }
}

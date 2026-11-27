package com.zenread.reader.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Read-aloud (phase 3) events and results from foliate-js. */
class ReaderTtsBridgeTest {
    private fun args(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    @Test
    fun `parses tts chapter`() {
        assertEquals(ReaderEvent.TtsChapter("Chapter 2"), ReaderEvent.parse("onTtsChapter", args("""[{"chapterTitle":"Chapter 2"}]""")))
        assertNull(ReaderEvent.parse("onTtsChapter", args("""[{}]""")))
    }

    @Test
    fun `parses tts progress and clamps the fraction`() {
        assertEquals(
            ReaderEvent.TtsProgress("epubcfi(/6/4!/4/2/1:0)", 0.42f),
            ReaderEvent.parse("onTtsProgress", args("""[{"cfi":"epubcfi(/6/4!/4/2/1:0)","percentage":0.42}]""")),
        )
        assertEquals(1f, (ReaderEvent.parse("onTtsProgress", args("""[{"cfi":"x","percentage":3}]""")) as ReaderEvent.TtsProgress).fraction)
        assertNull(ReaderEvent.parse("onTtsProgress", args("""[{"percentage":0.5}]""")))
        assertNull(ReaderEvent.parse("onTtsProgress", args("""[{"cfi":"x"}]""")))
    }

    @Test
    fun `parses speech details`() {
        val detail = ReaderController.parseTtsDetail(Json.parseToJsonElement("""{"text":"Hello.","cfi":"epubcfi(/6/2!/4,/1:0,/1:6)","section":3}"""))
        assertEquals(ReaderController.TtsDetail("Hello.", "epubcfi(/6/2!/4,/1:0,/1:6)", 3), detail)
        assertEquals(
            ReaderController.TtsDetail("No cfi", null, null),
            ReaderController.parseTtsDetail(Json.parseToJsonElement("""{"text":"No cfi","cfi":null}""")),
        )
        assertNull(ReaderController.parseTtsDetail(Json.parseToJsonElement("""{"text":"  "}""")))
        assertNull(ReaderController.parseTtsDetail(Json.parseToJsonElement("null")))
    }
}

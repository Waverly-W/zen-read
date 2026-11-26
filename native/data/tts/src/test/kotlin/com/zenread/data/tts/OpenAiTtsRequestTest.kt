package com.zenread.data.tts

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiTtsRequestTest {
    @Test
    fun `endpoint appends audio speech to a base URL`() {
        assertEquals("https://api.openai.com/v1/audio/speech", OpenAiTtsRequest.endpoint("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/audio/speech", OpenAiTtsRequest.endpoint(" https://api.openai.com/v1/ "))
        assertEquals("http://10.0.0.2:8000/v1/audio/speech", OpenAiTtsRequest.endpoint("http://10.0.0.2:8000/v1/audio/speech"))
        assertThrows(IllegalArgumentException::class.java) { OpenAiTtsRequest.endpoint("api.openai.com") }
    }

    @Test
    fun `neutral rate and pitch send no speed or instructions`() {
        val body = OpenAiTtsRequest.body("Hi", " gpt-4o-mini-tts ", "alloy", rate = 1f, pitch = 1f)
        assertEquals(JsonPrimitive("gpt-4o-mini-tts"), body["model"])
        assertEquals(JsonPrimitive("alloy"), body["voice"])
        assertEquals(JsonPrimitive("Hi"), body["input"])
        assertEquals(JsonPrimitive("mp3"), body["response_format"])
        assertFalse(body.containsKey("speed"))
        assertFalse(body.containsKey("instructions"))
    }

    @Test
    fun `rate becomes a clamped speed`() {
        assertEquals(1.5f, OpenAiTtsRequest.body("x", "m", "v", rate = 1.5f, pitch = 1f)["speed"]!!.jsonPrimitive.float)
        assertEquals(4f, OpenAiTtsRequest.body("x", "m", "v", rate = 9f, pitch = 1f)["speed"]!!.jsonPrimitive.float)
        assertEquals(0.25f, OpenAiTtsRequest.body("x", "m", "v", rate = 0.1f, pitch = 1f)["speed"]!!.jsonPrimitive.float)
    }

    @Test
    fun `pitch is described in instructions except for legacy models`() {
        val higher = OpenAiTtsRequest.body("x", "gpt-4o-mini-tts", "v", rate = 1f, pitch = 1.2f)["instructions"]!!.jsonPrimitive.content
        assertTrue(higher, higher.startsWith("Use a higher pitch, at 1.20 times"))
        val lower = OpenAiTtsRequest.body("x", "gpt-4o-mini-tts", "v", rate = 1f, pitch = 0.1f)["instructions"]!!.jsonPrimitive.content
        assertTrue(lower, lower.startsWith("Use a lower pitch, at 0.50 times"))
        assertFalse(OpenAiTtsRequest.body("x", "tts-1", "v", rate = 1f, pitch = 1.2f).containsKey("instructions"))
        assertTrue(OpenAiTtsRequest.isLegacyModel(" tts-1-hd "))
        assertFalse(TtsSettings.supportsPitch(TtsEngineType.OPENAI, "tts-1"))
        assertTrue(TtsSettings.supportsPitch(TtsEngineType.OPENAI, "gpt-4o-mini-tts"))
    }
}

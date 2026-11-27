package com.zenread.data.tts.edge

import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsProtocolTest {
    @Test
    fun `escapes markup like Dart HtmlEscape and replaces control characters`() {
        assertEquals(
            listOf("a &lt;b&gt; &amp; &quot;c&quot; &#39;d&#39; &#47; e"),
            EdgeTtsProtocol.escapeAndSplit("a <b> & \"c\" 'd' / e"),
        )
        assertEquals(listOf("x y"), EdgeTtsProtocol.escapeAndSplit("x\u0001y"))
        // Tab, line feed and carriage return are kept.
        assertEquals(listOf("x\ty\nz"), EdgeTtsProtocol.escapeAndSplit("x\ty\nz"))
        assertEquals(emptyList<String>(), EdgeTtsProtocol.escapeAndSplit("  \u0002 "))
    }

    @Test
    fun `splits by UTF-8 bytes without breaking escapes or code points`() {
        val chunks = EdgeTtsProtocol.escapeAndSplit("你好世界&", maxBytes = 7)
        // Each CJK character is 3 bytes; "&amp;" is 5 bytes.
        assertEquals(listOf("你好", "世界", "&amp;"), chunks)
        val emoji = EdgeTtsProtocol.escapeAndSplit("😀😀", maxBytes = 4)
        assertEquals(listOf("😀", "😀"), emoji)
        chunks.forEach { assertTrue(it.toByteArray().size <= 7) }
    }

    @Test
    fun `builds the SSML request frame`() {
        val message = EdgeTtsProtocol.ssmlMessage(
            escapedText = "Hello",
            voice = "en-US-AvaNeural",
            rate = 1.5f,
            pitch = 0.8f,
            requestId = "abc",
            timestamp = "Thu Jan 01 2025 00:00:00 GMT+0000 (Coordinated Universal Time)",
        )
        assertEquals(
            "X-RequestId:abc\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:Thu Jan 01 2025 00:00:00 GMT+0000 (Coordinated Universal Time)Z\r\n" +
                "Path:ssml\r\n\r\n" +
                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='en-US-AvaNeural'><prosody pitch='-20%' rate='+50%' volume='+0%'>Hello</prosody></voice></speak>",
            message,
        )
    }

    @Test
    fun `clamps prosody and escapes the voice name`() {
        val message = EdgeTtsProtocol.ssmlMessage("t", "a'b", rate = 9f, pitch = 0.1f, requestId = "r", timestamp = "ts")
        assertTrue(message, message.contains("<voice name='a&#39;b'>"))
        assertTrue(message, message.contains("pitch='-50%' rate='+200%'"))
        val neutral = EdgeTtsProtocol.ssmlMessage("t", "v", rate = 1f, pitch = 1f, requestId = "r", timestamp = "ts")
        assertTrue(neutral, neutral.contains("pitch='+0%' rate='+0%'"))
    }

    @Test
    fun `speech config asks for mp3 without boundaries`() {
        val config = EdgeTtsProtocol.speechConfigMessage("ts")
        assertTrue(config.startsWith("X-Timestamp:ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n"))
        assertTrue(config.contains("\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\""))
        assertTrue(config.endsWith("}}}}\r\n"))
    }

    @Test
    fun `extracts audio payloads from binary frames`() {
        fun frame(header: String, payload: ByteArray): ByteArray {
            val h = header.toByteArray()
            return byteArrayOf((h.size shr 8).toByte(), h.size.toByte()) + h + payload
        }
        val audio = byteArrayOf(1, 2, 3)
        assertArrayEquals(audio, EdgeTtsProtocol.audioPayload(frame("X-RequestId:a\r\nPath:audio\r\n", audio)))
        assertNull(EdgeTtsProtocol.audioPayload(frame("Path:turn.start\r\n", audio)))
        assertNull(EdgeTtsProtocol.audioPayload(byteArrayOf(0)))
        assertThrows(IllegalArgumentException::class.java) { EdgeTtsProtocol.audioPayload(byteArrayOf(0, 50, 1)) }
    }

    @Test
    fun `reads the path of text frames`() {
        assertEquals("turn.end", EdgeTtsProtocol.messagePath("X-RequestId:x\r\nPath: Turn.End \r\n\r\n{}"))
        assertEquals("audio.metadata", EdgeTtsProtocol.messagePath("Path:audio.metadata\r\n\r\n{\"Path\":\"no\"}"))
        assertNull(EdgeTtsProtocol.messagePath("X-RequestId:x\r\n\r\nPath:body"))
    }

    @Test
    fun `formats timestamps like JavaScript Date toString in UTC`() {
        assertEquals(
            "Wed Jan 01 2025 09:05:03 GMT+0000 (Coordinated Universal Time)",
            EdgeTtsProtocol.timestamp(Instant.parse("2025-01-01T09:05:03Z")),
        )
    }

    @Test
    fun `builds URLs with the v2 token parameters`() {
        val list = EdgeTtsProtocol.voiceListUrl("TOKEN")
        assertTrue(list, list.contains("trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"))
        assertTrue(list, list.contains("Sec-MS-GEC=TOKEN&Sec-MS-GEC-Version=2-143.0.3650.75"))
        val ws = EdgeTtsProtocol.webSocketUrl("TOKEN", "cid")
        assertTrue(ws, ws.startsWith("wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?"))
        assertTrue(ws, ws.contains("ConnectionId=cid"))
    }

    @Test
    fun `ids are 32 hex characters`() {
        assertTrue(Regex("^[0-9a-f]{32}$").matches(EdgeTtsProtocol.connectionId()))
        assertTrue(Regex("^[0-9A-F]{32}$").matches(EdgeTtsProtocol.muid()))
    }

    @Test
    fun `parses the voice list and skips entries without a short name`() {
        val voices = EdgeTtsClient.parseVoices(
            """[{"ShortName":"zh-CN-XiaoxiaoNeural","LocalName":"晓晓","Locale":"zh-CN","Gender":"Female"},
               {"FriendlyName":"No id"},
               {"ShortName":"en-US-AvaNeural","FriendlyName":"Ava","Locale":"en-US"}]""",
        )
        assertEquals(listOf("zh-CN-XiaoxiaoNeural", "en-US-AvaNeural"), voices.map { it.id })
        assertEquals("晓晓", voices[0].name)
        assertEquals("Female", voices[0].gender)
        assertEquals("Ava", voices[1].name)
    }
}

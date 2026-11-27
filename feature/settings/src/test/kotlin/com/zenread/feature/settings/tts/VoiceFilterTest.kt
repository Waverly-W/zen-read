package com.zenread.feature.settings.tts

import com.zenread.data.tts.TtsVoice
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceFilterTest {
    private val voices = listOf(
        TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓", "zh-CN"),
        TtsVoice("en-US-AvaNeural", "Ava", "en-US"),
        TtsVoice("en-GB-SoniaNeural", "Sonia", "en-GB"),
    )

    @Test
    fun `blank query keeps every voice`() {
        assertEquals(voices, filterVoices(voices, "  "))
    }

    @Test
    fun `matches name, id and locale ignoring case`() {
        assertEquals(listOf(voices[0]), filterVoices(voices, "晓"))
        assertEquals(listOf(voices[1]), filterVoices(voices, "ava"))
        assertEquals(listOf(voices[1], voices[2]), filterVoices(voices, "EN-"))
    }
}

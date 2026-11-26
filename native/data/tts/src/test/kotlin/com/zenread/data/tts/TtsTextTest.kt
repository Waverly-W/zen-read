package com.zenread.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextTest {
    @Test
    fun `separators are punctuation and whitespace only`() {
        listOf("", " ", "……", "* * *", "——", "⋯⋯", "。", " ", "​", "「」").forEach {
            assertTrue("'$it'", TtsText.isSeparator(it))
            assertFalse("'$it'", TtsText.isSpeakable(it))
        }
    }

    @Test
    fun `text in any script, numbers and symbols is speakable`() {
        listOf("a", "你好", "1", "+", "α = β", "Привет", "مرحبا", "……好").forEach {
            assertFalse("'$it'", TtsText.isSeparator(it))
            assertTrue("'$it'", TtsText.isSpeakable(it))
        }
    }

    @Test
    fun `normalize collapses whitespace`() {
        assertEquals("a b c", TtsText.normalize("  a\n  b\t\tc "))
    }

    @Test
    fun `split keeps short text whole`() {
        assertEquals(listOf("short"), TtsText.split("short", 10))
    }

    @Test
    fun `split prefers sentence ends then spaces`() {
        assertEquals(listOf("Sentence one.", "Then two more."), TtsText.split("Sentence one. Then two more.", 20))
        assertEquals(listOf("第一句。", "第二句话。"), TtsText.split("第一句。第二句话。", 6))
        // A sentence end in the first half would make a tiny piece; a later space wins.
        assertEquals(listOf("One two. Three", "four five."), TtsText.split("One two. Three four five.", 16))
        assertEquals(listOf("aaaa bbbb", "cccc dddd"), TtsText.split("aaaa bbbb cccc dddd", 10))
    }

    @Test
    fun `split never exceeds the limit or breaks surrogate pairs`() {
        val text = "😀".repeat(10)
        val parts = TtsText.split(text, 5)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { part ->
            assertTrue(part.length <= 5)
            assertFalse(Character.isHighSurrogate(part.last()))
        }
        val unbroken = "x".repeat(25)
        assertEquals(listOf("x".repeat(10), "x".repeat(10), "x".repeat(5)), TtsText.split(unbroken, 10))
    }
}

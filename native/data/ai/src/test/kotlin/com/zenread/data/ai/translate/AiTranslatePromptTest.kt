package com.zenread.data.ai.translate

import com.zenread.data.translate.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranslatePromptTest {
    @Test
    fun `batch prompt sends a json array and names the target`() {
        val messages = AiTranslatePrompt.batch(listOf("Hello", "He said \"hi\""), null, Language.SIMPLIFIED_CHINESE)
        assertTrue(messages[0].content.contains("简体中文 (zh-CN)"))
        assertEquals("""["Hello","He said \"hi\""]""", messages[1].content)
    }

    @Test
    fun `parses a fenced array of the right size`() {
        val answer = "```json\n[\"你好\", \"他说“嗨”\"]\n```"
        assertEquals(listOf("你好", "他说“嗨”"), AiTranslatePrompt.parseBatch(answer, 2))
    }

    @Test
    fun `rejects wrong length, non-strings and prose`() {
        assertNull(AiTranslatePrompt.parseBatch("[\"a\"]", 2))
        assertNull(AiTranslatePrompt.parseBatch("[\"a\", 3]", 2))
        assertNull(AiTranslatePrompt.parseBatch("Sorry, I cannot do that.", 2))
    }

    @Test
    fun `cleans quotes and fences around a single answer`() {
        assertEquals("你好，世界", AiTranslatePrompt.cleanSingle("“你好，世界”"))
        assertEquals("你好", AiTranslatePrompt.cleanSingle("```\n你好\n```"))
        assertEquals("\"引号\"在中间", AiTranslatePrompt.cleanSingle("\"引号\"在中间"))
    }
}

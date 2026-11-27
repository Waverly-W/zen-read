package com.zenread.reader.engine

import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.ReadingTheme
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderStyleTest {
    private fun style(settings: ReaderSettings) = ReaderStyle.build(settings, ReadingTheme.PAPER, "zh-CN")

    @Test
    fun `line height, paragraph spacing and indent are passed separately`() {
        val s = style(ReaderSettings(lineHeight = 2.0f, paragraphSpacing = 1.5f, textIndent = 2f))
        assertEquals(2.0f, s["spacing"]!!.jsonPrimitive.float)
        assertEquals(1.5f, s["paragraphSpacing"]!!.jsonPrimitive.float)
        assertEquals(2f, s["textIndent"]!!.jsonPrimitive.float)
    }

    @Test
    fun `default indent keeps the book's own`() {
        val s = style(ReaderSettings())
        // foliate-js skips its text-indent rule for negative values.
        assertEquals(ReaderSettings.TEXT_INDENT_BOOK, s["textIndent"]!!.jsonPrimitive.float)
        assertEquals(1.0f, s["paragraphSpacing"]!!.jsonPrimitive.float)
    }
}

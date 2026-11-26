package com.zenread.feature.reader.translate

import com.zenread.core.model.ReadingTheme
import com.zenread.data.translate.TranslationColor
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationStyleTest {
    @Test
    fun blendMixesChannels() {
        assertEquals("#808080", blend("#000000", "#FFFFFF", 0.5f))
        assertEquals("#000000", blend("#000000", "#FFFFFF", 0f))
        assertEquals("#FFFFFF", blend("#000000", "#FFFFFF", 1f))
    }

    @Test
    fun styleUsesSixDigitHexColors() {
        // foliate-js ignores anything that is not #RRGGBB.
        TranslationColor.entries.forEach { color ->
            listOf(ReadingTheme.PAPER, ReadingTheme.NIGHT, ReadingTheme.SEPIA).forEach { theme ->
                val value = translationTextStyle(color, italic = false, theme)["color"]!!.jsonPrimitive.content
                assertTrue(value, Regex("^#[0-9A-Fa-f]{6}$").matches(value))
            }
        }
    }

    @Test
    fun textColorFollowsTheme() {
        val style = translationTextStyle(TranslationColor.TEXT, italic = true, ReadingTheme.SEPIA)
        assertEquals(ReadingTheme.SEPIA.text, style["color"]!!.jsonPrimitive.content)
        assertTrue(style["italic"]!!.jsonPrimitive.boolean)
    }
}

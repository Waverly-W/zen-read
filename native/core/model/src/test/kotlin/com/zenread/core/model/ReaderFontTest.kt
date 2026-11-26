package com.zenread.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFontTest {
    @Test
    fun `ids round-trip`() {
        val fonts = listOf(
            ReaderFont.Book,
            ReaderFont.System(SystemFontFamily.SERIF),
            ReaderFont.System(SystemFontFamily.SANS_SERIF),
            ReaderFont.System(SystemFontFamily.MONOSPACE),
            ReaderFont.Bundled("AtkinsonHyperlegibleNext-Regular.ttf"),
            ReaderFont.Installed("0123456789abcdef.woff2"),
        )
        for (font in fonts) assertEquals(font, ReaderFont.parse(font.id))
        assertEquals("system:sans-serif", ReaderFont.System(SystemFontFamily.SANS_SERIF).id)
        assertEquals("file:0123456789abcdef.woff2", ReaderFont.Installed("0123456789abcdef.woff2").id)
    }

    @Test
    fun `unknown or unsafe ids are rejected`() {
        listOf(
            null, "", "Book", "system:", "system:cursive", "system:Serif",
            "file:", "file:../x.ttf", "file:a/b.ttf", "file:x.exe", "bundled:..",
            "file:.hidden.ttf", "file:x..ttf", "http://example.com/x.ttf",
        ).forEach { assertNull(it.toString(), ReaderFont.parse(it)) }
    }

    @Test
    fun `font file names`() {
        assertTrue(ReaderFont.isFontFileName("a.ttf"))
        assertTrue(ReaderFont.isFontFileName("Source_Han-Serif.v2.otf"))
        assertFalse(ReaderFont.isFontFileName("a.TTF"))
        assertFalse(ReaderFont.isFontFileName("-a.ttf"))
        assertFalse(ReaderFont.isFontFileName("a b.ttf"))
        assertFalse(ReaderFont.isFontFileName("思源.ttf"))
        assertFalse(ReaderFont.isFontFileName("a.ttf\n"))
    }
}

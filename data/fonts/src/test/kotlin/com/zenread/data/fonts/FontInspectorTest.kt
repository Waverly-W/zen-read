package com.zenread.data.fonts

import com.zenread.data.fonts.TestFonts.windows
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FontInspectorTest {
    private fun inspect(bytes: ByteArray, locale: Locale = Locale.ENGLISH) =
        FontInspector.inspect(ByteArrayFontBytes(bytes), locale)

    private val bilingual = TestFonts.nameTable(
        listOf(
            windows(1, "LXGW WenKai"),
            windows(2, "Regular"),
            windows(1, "霞鹜文楷", language = 0x0804),
            windows(2, "Regular", language = 0x0804),
        ),
    )

    @Test
    fun `sniffs container formats by magic bytes`() {
        assertEquals(FontFormat.TTF, FontFormat.sniff(byteArrayOf(0, 1, 0, 0)))
        assertEquals(FontFormat.TTF, FontFormat.sniff("true".toByteArray()))
        assertEquals(FontFormat.OTF, FontFormat.sniff("OTTO".toByteArray()))
        assertEquals(FontFormat.WOFF, FontFormat.sniff("wOFF".toByteArray()))
        assertEquals(FontFormat.WOFF2, FontFormat.sniff("wOF2".toByteArray()))
        // Collections, archives, short input and look-alikes are rejected.
        assertNull(FontFormat.sniff("ttcf".toByteArray()))
        assertNull(FontFormat.sniff("PK\u0003\u0004".toByteArray()))
        assertNull(FontFormat.sniff(byteArrayOf(0, 1, 0)))
        assertNull(FontFormat.sniff(byteArrayOf(0, 1, 0, 1)))
    }

    @Test
    fun `reads the family name in the user's language`() {
        val font = TestFonts.sfnt(mapOf("name" to bilingual))
        assertEquals("霞鹜文楷", inspect(font, Locale.SIMPLIFIED_CHINESE).displayName)
        assertEquals("LXGW WenKai", inspect(font, Locale.ENGLISH).displayName)
        // No match for the user's language: English Windows names win.
        assertEquals("LXGW WenKai", inspect(font, Locale.GERMAN).displayName)
    }

    @Test
    fun `prefers typographic family and appends a non-plain style`() {
        val names = TestFonts.nameTable(
            listOf(
                windows(1, "Source Han Serif CN SemiBold"),
                windows(2, "Regular"),
                windows(16, "Source Han Serif CN"),
                windows(17, "SemiBold"),
            ),
        )
        val info = inspect(TestFonts.sfnt(mapOf("name" to names), magic = "OTTO".toByteArray()))
        assertEquals(FontFormat.OTF, info.format)
        assertEquals("Source Han Serif CN", info.family)
        assertEquals("Source Han Serif CN SemiBold", info.displayName)
    }

    @Test
    fun `falls back to Mac Roman names`() {
        val names = TestFonts.nameTable(listOf(TestFonts.Name(1, 0, 0, 1, "Old Mac Font")))
        assertEquals("Old Mac Font", inspect(TestFonts.sfnt(mapOf("name" to names))).displayName)
    }

    @Test
    fun `a font without a name table is valid but unnamed`() {
        val info = inspect(TestFonts.sfnt(emptyMap()))
        assertEquals(FontFormat.TTF, info.format)
        assertNull(info.displayName)
    }

    @Test
    fun `reads names from WOFF with a compressed name table`() {
        val big = TestFonts.nameTable(List(20) { windows(1, "Repeated Family") } + windows(2, "Bold"))
        val info = inspect(TestFonts.woff(mapOf("name" to big)))
        assertEquals(FontFormat.WOFF, info.format)
        assertEquals("Repeated Family Bold", info.displayName)
    }

    @Test
    fun `accepts a WOFF2 header without names`() {
        val info = inspect(TestFonts.woff2())
        assertEquals(FontFormat.WOFF2, info.format)
        assertNull(info.displayName)
    }

    @Test
    fun `rejects broken files`() {
        val good = TestFonts.sfnt(mapOf("name" to bilingual))
        listOf(
            ByteArray(0),
            "<html>".toByteArray(),
            good.copyOf(40), // truncated table directory
            good.copyOf(good.size - 8), // table data cut off
            TestFonts.woff2().let { it.copyOf(it.size - 1) }, // length field disagrees
            TestFonts.woff(emptyMap()).let { it.copyOf(it.size + 4) },
        ).forEach { bytes ->
            assertThrows(MalformedFontException::class.java) { inspect(bytes) }
        }
    }

    @Test
    fun `rejects sfnt without cmap`() {
        val noCmap = TestFonts.sfnt(emptyMap()).also {
            // Rename the first table record's tag ("cmap" sorts first).
            "xmap".toByteArray().copyInto(it, destinationOffset = 12)
        }
        assertThrows(MalformedFontException::class.java) { inspect(noCmap) }
    }

    @Test
    fun `reads the bundled font`() {
        val file = File("src/main/assets/fonts/AtkinsonHyperlegibleNext-Regular.ttf")
        val info = FontInspector.inspect(file, Locale.ENGLISH)
        assertEquals(FontFormat.TTF, info.format)
        assertEquals("Atkinson Hyperlegible Next", info.displayName)
        assertEquals(FontCatalog.bundled.single().label, info.displayName)
    }

    @Test
    fun `cleans labels`() {
        assertEquals("A B", FontLabels.clean("  A\u0000\n  B\uFEFF "))
        assertNull(FontLabels.clean(" \u0001 "))
        assertEquals(81, FontLabels.clean("x".repeat(200))!!.length)
        assertEquals("My Font Bold", FontLabels.fromFileName("My_Font_Bold.TTF"))
        assertEquals("evil", FontLabels.fromFileName("../../evil.woff2"))
        assertNull(FontLabels.fromFileName(null))
    }
}

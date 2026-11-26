package com.zenread.reader.engine

import com.zenread.core.model.ReaderFont
import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.ReadingTheme
import com.zenread.core.model.SystemFontFamily
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderFontFilesTest {
    private val dir: File = Files.createTempDirectory("fonts").toFile()

    @Test
    fun `serves plain font names from the fonts directory`() {
        val target = ReaderFontFiles.resolve("0123456789abcdef.ttf", dir)
        assertEquals(ReaderFontFiles.Target.Installed(File(dir.canonicalFile, "0123456789abcdef.ttf")), target)
        assertEquals(
            ReaderFontFiles.Target.Bundled("fonts/AtkinsonHyperlegibleNext-Regular.ttf"),
            ReaderFontFiles.resolve("bundled/AtkinsonHyperlegibleNext-Regular.ttf", dir),
        )
    }

    @Test
    fun `rejects traversal, nesting and non-font names`() {
        listOf(
            "../secret.ttf",
            "..",
            "a/b.ttf",
            "bundled/../x.ttf",
            "bundled/sub/x.ttf",
            "bundled/",
            "/etc/passwd",
            ".hidden.ttf",
            "x..ttf",
            "x.ttf/",
            "x.html",
            "x.ttf.exe",
            "fonts-index.json",
            "x\u0000.ttf",
            "中文.ttf",
            "",
        ).forEach { assertNull(it, ReaderFontFiles.resolve(it, dir)) }
    }

    @Test
    fun `rejects a symlink that leaves the directory`() {
        val outside = Files.createTempFile("outside", ".ttf")
        val link = File(dir, "link.ttf").toPath()
        val linked = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        if (linked) assertNull(ReaderFontFiles.resolve("link.ttf", dir))
    }

    @Test
    fun `font MIME types`() {
        assertEquals("font/ttf", ReaderFontFiles.mimeType("a.ttf"))
        assertEquals("font/otf", ReaderFontFiles.mimeType("a.otf"))
        assertEquals("font/woff", ReaderFontFiles.mimeType("a.woff"))
        assertEquals("font/woff2", ReaderFontFiles.mimeType("a.woff2"))
        assertNull(ReaderFontFiles.mimeType("a.ttc"))
        assertNull(ReaderFontFiles.mimeType("a.js"))
    }

    @Test
    fun `maps body fonts to foliate-js name and path`() {
        assertEquals(ReaderFontStyle.Face("book", "book"), ReaderFontStyle.body(ReaderFont.Book))
        assertEquals(ReaderFontStyle.Face("serif", ""), ReaderFontStyle.body(ReaderFont.System(SystemFontFamily.SERIF)))
        assertEquals(
            ReaderFontStyle.Face("zenfont-file-0123456789abcdef_ttf", "https://appassets.androidplatform.net/fonts/0123456789abcdef.ttf"),
            ReaderFontStyle.body(ReaderFont.Installed("0123456789abcdef.ttf")),
        )
        assertEquals(
            ReaderFontStyle.Face("zenfont-bundled-Atkinson-Regular_ttf", "https://appassets.androidplatform.net/fonts/bundled/Atkinson-Regular.ttf"),
            ReaderFontStyle.body(ReaderFont.Bundled("Atkinson-Regular.ttf")),
        )
    }

    @Test
    fun `maps Latin fonts`() {
        assertNull(ReaderFontStyle.latin(null))
        assertNull(ReaderFontStyle.latin(ReaderFont.Book))
        // Upstream's "system" source list covers sans-serif.
        assertEquals(ReaderFontStyle.Face("system", ""), ReaderFontStyle.latin(ReaderFont.System(SystemFontFamily.SANS_SERIF)))
        assertEquals(ReaderFontStyle.Face("serif", ""), ReaderFontStyle.latin(ReaderFont.System(SystemFontFamily.SERIF)))
        assertEquals(
            ReaderFontStyle.body(ReaderFont.Installed("0123456789abcdef.woff2")),
            ReaderFontStyle.latin(ReaderFont.Installed("0123456789abcdef.woff2")),
        )
    }

    @Test
    fun `style object carries font settings`() {
        val settings = ReaderSettings(
            font = ReaderFont.Installed("0123456789abcdef.otf"),
            latinFont = ReaderFont.Bundled("Atkinson-Regular.ttf"),
            fontWeight = 500,
        )
        val style = ReaderStyle.build(settings, ReadingTheme.PAPER, "zh")
        assertEquals(JsonPrimitive("zenfont-file-0123456789abcdef_otf"), style["fontName"])
        assertEquals(JsonPrimitive("https://appassets.androidplatform.net/fonts/0123456789abcdef.otf"), style["fontPath"])
        assertEquals(JsonPrimitive("zenfont-bundled-Atkinson-Regular_ttf"), style["englishFontName"])
        assertEquals(JsonPrimitive(500), style["fontWeight"])

        val defaults = ReaderStyle.build(ReaderSettings(), ReadingTheme.PAPER, "zh")
        assertEquals(JsonPrimitive("book"), defaults["fontName"])
        assertEquals(JsonPrimitive("book"), defaults["fontPath"])
        assertEquals(JsonNull, defaults["englishFontName"])
        assertEquals(JsonNull, defaults["englishFontPath"])
        assertEquals(JsonPrimitive(400), defaults["fontWeight"])
    }
}

package com.zenread.data.fonts

import com.zenread.core.model.ReaderFont
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCatalogTest {
    private val sha = "3754ea669c530e2473354f8f6d9f79680a44d7e26ec7d00eeabee4a7e0753c5d"

    @Test
    fun `installed names are content hashes`() {
        assertEquals("3754ea669c530e24.otf", FontFileNames.installedName(sha, FontFormat.OTF))
        assertEquals("3754ea669c530e24.woff2", FontFileNames.installedName(sha.uppercase(), FontFormat.WOFF2))
        assertThrows(IllegalArgumentException::class.java) { FontFileNames.installedName("abc", FontFormat.TTF) }
        assertThrows(IllegalArgumentException::class.java) { FontFileNames.installedName("../" + sha.drop(3), FontFormat.TTF) }
    }

    @Test
    fun `only hash names count as installed fonts`() {
        assertTrue(FontFileNames.isInstalledName("3754ea669c530e24.otf"))
        assertTrue(FontFileNames.isInstalledName("3754ea669c530e24.woff2"))
        listOf(
            "3754EA669C530E24.otf",
            "3754ea669c530e24.OTF",
            "3754ea669c530e24.exe",
            "3754ea669c530e24.otf.tmp",
            "3754ea669c530e2.otf",
            ".import-1234",
            "../3754ea669c530e24.otf",
            "fonts-index.json",
        ).forEach { assertFalse(it, FontFileNames.isInstalledName(it)) }
    }

    @Test
    fun `catalog entries are consistent`() {
        val ids = FontCatalog.downloadable.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (entry in FontCatalog.downloadable) {
            assertTrue(entry.id, entry.urls.isNotEmpty() && entry.urls.all { it.startsWith("https://") })
            assertTrue(entry.id, FontFileNames.isInstalledName(entry.fileName))
            assertTrue(entry.id, ReaderFont.isFontFileName(entry.fileName))
            assertTrue(entry.id, entry.sizeBytes in 1..(64L shl 20))
            assertEquals(entry, FontCatalog.downloadable(entry.id))
            assertEquals(entry.font, ReaderFont.parse(entry.font.id))
        }
    }

    @Test
    fun `bundled fonts exist as assets`() {
        for (font in FontCatalog.bundled) {
            assertTrue(font.fileName, File("src/main/assets", font.assetPath).isFile)
            assertTrue(font.fileName, ReaderFont.isFontFileName(font.fileName))
            assertEquals(font.font, ReaderFont.parse(font.font.id))
        }
    }
}

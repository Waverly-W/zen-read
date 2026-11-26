package com.zenread.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookFormatTest {
    @Test
    fun `detects formats by extension, case-insensitively`() {
        assertEquals(BookFormat.EPUB, BookFormat.fromFileName("Dune.EPUB"))
        assertEquals(BookFormat.MOBI, BookFormat.fromFileName("a.b.azw3"))
        assertEquals(BookFormat.FB2, BookFormat.fromFileName("book.fbz"))
        assertEquals(BookFormat.PDF, BookFormat.fromFileName("scan.pdf"))
        assertEquals(BookFormat.CBZ, BookFormat.fromFileName("comic.cbz"))
    }

    @Test
    fun `rejects unknown or missing extensions`() {
        assertNull(BookFormat.fromFileName("notes.txt"))
        assertNull(BookFormat.fromFileName("README"))
        assertNull(BookFormat.fromFileName(".epub.bak"))
    }

    @Test
    fun `toc flattens depth first`() {
        val toc = listOf(
            TocItem("1", "a", 1, 0f, listOf(TocItem("1.1", "b", 2, 0f, listOf(TocItem("1.1.1", "c", 3, 0f, emptyList()))))),
            TocItem("2", "d", 1, 0.5f, emptyList()),
        )
        assertEquals(listOf("1", "1.1", "1.1.1", "2"), toc.flattenToc().map { it.label })
    }
}

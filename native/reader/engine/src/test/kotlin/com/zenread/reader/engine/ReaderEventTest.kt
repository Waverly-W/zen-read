package com.zenread.reader.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderEventTest {
    private fun args(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    @Test
    fun `parses relocated payload from book_js`() {
        val event = ReaderEvent.parse(
            "onRelocated",
            args(
                """[{"cfi":"epubcfi(/6/4!/4/2)","percentage":0.25,"chapterTitle":"One","chapterHref":"c1.xhtml",
                   "chapterCurrentPage":3,"chapterTotalPages":10,"currentChapter":2,"totalChapters":7,
                   "readingAction":true,"bookmark":null}]""",
            ),
        ) as ReaderEvent.Relocated
        assertEquals("epubcfi(/6/4!/4/2)", event.cfi)
        assertEquals(0.25f, event.fraction)
        assertEquals("One", event.chapterTitle)
        assertEquals(3, event.chapterPage)
        assertEquals(10, event.chapterPages)
        assertEquals(2, event.section)
        assertEquals(7, event.sections)
        assertTrue(event.userAction)
    }

    @Test
    fun `relocated without cfi is ignored and fraction is clamped`() {
        assertNull(ReaderEvent.parse("onRelocated", args("""[{"percentage":0.5}]""")))
        val event = ReaderEvent.parse("onRelocated", args("""[{"cfi":"x","percentage":1.7}]""")) as ReaderEvent.Relocated
        assertEquals(1f, event.fraction)
    }

    @Test
    fun `parses nested toc`() {
        val event = ReaderEvent.parse(
            "onSetToc",
            args(
                """[[{"label":" Part I ","href":"p1.xhtml","level":1,"startPercentage":0,
                     "subitems":[{"label":"Ch 1","href":"c1.xhtml#a","level":2,"startPercentage":0.1,"subitems":[]}]},
                    {"label":"broken"}]]""",
            ),
        ) as ReaderEvent.TocLoaded
        assertEquals(1, event.items.size)
        assertEquals("Part I", event.items[0].label)
        assertEquals("c1.xhtml#a", event.items[0].children[0].href)
    }

    @Test
    fun `parses tap and lifecycle events, ignores unknown`() {
        assertEquals(ReaderEvent.Tap(0.1f, 0.9f), ReaderEvent.parse("onClick", args("""[{"x":0.1,"y":0.9}]""")))
        assertEquals(ReaderEvent.LoadEnd, ReaderEvent.parse("onLoadEnd", args("[]")))
        assertEquals(ReaderEvent.LoadError("TypeError"), ReaderEvent.parse("onReaderLoadError", args("""[{"name":"TypeError"}]""")))
        assertNull(ReaderEvent.parse("onQuickMark", args("[]")))
    }

    @Test
    fun `metadata handles language maps and contributor lists`() {
        val meta = BookMetadata.parse(
            Json.parseToJsonElement(
                """{"title":{"zh":"三体","en":"The Three-Body Problem"},
                    "author":[{"name":"刘慈欣"},{"name":{"en":"Ken Liu"}},"刘慈欣"],
                    "cover":"data:image/jpeg;base64,AAAA"}""",
            ).jsonObject,
        )
        assertEquals("三体", meta.title)
        assertEquals("刘慈欣, Ken Liu", meta.author)
        assertEquals("data:image/jpeg;base64,AAAA", meta.coverDataUrl)
    }

    @Test
    fun `metadata keeps untyped base64 covers from mobi`() {
        val meta = BookMetadata.parse(
            Json.parseToJsonElement("""{"title":"T","cover":"data:application/octet-stream;base64,/9j/4A=="}""").jsonObject,
        )
        assertEquals("data:application/octet-stream;base64,/9j/4A==", meta.coverDataUrl)
    }

    @Test
    fun `metadata drops blank values and non-data covers`() {
        val meta = BookMetadata.parse(Json.parseToJsonElement("""{"title":"  ","author":"Ann","cover":"blob:x"}""").jsonObject)
        assertNull(meta.title)
        assertEquals("Ann", meta.author)
        assertNull(meta.coverDataUrl)
    }
}

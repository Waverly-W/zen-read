package com.zenread.data.library

import com.zenread.core.model.Annotation
import com.zenread.core.model.AnnotationType
import com.zenread.core.model.Book
import com.zenread.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationMarkdownTest {
    private val book = Book(1, "三体: 地球往事", "刘慈欣", BookFormat.EPUB, "/b", null, "h", null, 0f, 0, null)

    private fun mark(
        id: Long, chapter: String, text: String, position: Float,
        note: String? = null, type: AnnotationType = AnnotationType.HIGHLIGHT,
    ) = Annotation(id, 1, type, "cfi$id", text, chapter, "F6C945", note, position, createdAt = id, updatedAt = id)

    @Test
    fun `groups by chapter in reading order and skips bookmarks`() {
        val md = AnnotationMarkdown.export(
            book,
            listOf(
                mark(3, "第二章", "后面的句子", 0.6f, note = "  我的想法  "),
                mark(1, "第一章", "第一句\n第二行", 0.1f),
                mark(2, "第一章", "页面摘录", 0.2f, type = AnnotationType.BOOKMARK),
                mark(4, "第一章", "同章第二条", 0.15f),
            ),
        )
        val expected = """
            |# 三体: 地球往事
            |
            |刘慈欣
            |
            |## 第一章
            |
            |> 第一句
            |> 第二行
            |
            |> 同章第二条
            |
            |## 第二章
            |
            |> 后面的句子
            |
            |我的想法
            |
        """.trimMargin()
        assertEquals(expected, md)
    }

    @Test
    fun `file name drops characters file systems reject`() {
        assertEquals("三体  地球往事.md", AnnotationMarkdown.fileName(book))
        assertEquals("notes.md", AnnotationMarkdown.fileName(book.copy(title = "///")))
    }
}

package com.zenread.data.library

import com.zenread.core.model.Annotation
import com.zenread.core.model.AnnotationType
import com.zenread.core.model.Book

/** Markdown export of one book's highlights and notes, in reading order, grouped by chapter. */
object AnnotationMarkdown {
    fun export(book: Book, annotations: List<Annotation>): String = buildString {
        appendLine("# ${book.title.oneLine()}")
        if (book.author.isNotBlank()) {
            appendLine()
            appendLine(book.author.oneLine())
        }
        val marks = annotations
            .filter { it.type != AnnotationType.BOOKMARK }
            .sortedWith(compareBy({ it.position }, { it.createdAt }))
        var chapter: String? = null
        for (mark in marks) {
            if (mark.chapter != chapter) {
                chapter = mark.chapter
                appendLine()
                appendLine("## ${chapter.ifBlank { "—" }.oneLine()}")
            }
            appendLine()
            mark.text.trim().lines().forEach { line -> appendLine("> $line".trimEnd()) }
            if (mark.hasNote) {
                appendLine()
                appendLine(mark.note!!.trim())
            }
        }
    }

    /** File name for the export: the title without characters file systems reject. */
    fun fileName(book: Book): String =
        book.title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().take(80).ifBlank { "notes" } + ".md"

    private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()
}

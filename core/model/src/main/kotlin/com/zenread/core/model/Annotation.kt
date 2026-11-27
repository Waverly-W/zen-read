package com.zenread.core.model

enum class AnnotationType(val jsName: String) {
    HIGHLIGHT("highlight"),
    UNDERLINE("underline"),
    BOOKMARK("bookmark"),
    ;

    companion object {
        fun fromJs(name: String?): AnnotationType? = entries.firstOrNull { it.jsName == name }
    }
}

/** Highlight colors as RRGGBB (no `#`), in menu order. */
enum class HighlightColor(val hex: String) {
    YELLOW("F6C945"),
    GREEN("7CCB8B"),
    BLUE("62B0F0"),
    PINK("F28CB1"),
    PURPLE("B094EC"),
    ;

    companion object {
        val DEFAULT = YELLOW
    }
}

data class Annotation(
    val id: Long,
    val bookId: Long,
    val type: AnnotationType,
    /** EPUB CFI of the marked range (a page range for bookmarks). */
    val cfi: String,
    /** The marked text, or a page excerpt for bookmarks. */
    val text: String,
    val chapter: String,
    /** RRGGBB without `#`. */
    val color: String,
    /** The reader's own note; null or blank when there is none. */
    val note: String?,
    /** Book fraction (0–1) when created; orders annotations by reading position. */
    val position: Float,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val hasNote: Boolean get() = !note.isNullOrBlank()
}

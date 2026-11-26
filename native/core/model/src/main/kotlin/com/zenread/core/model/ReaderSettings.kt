package com.zenread.core.model

enum class PageTurnStyle(val jsName: String) {
    SLIDE("slide"),
    SCROLL("scroll"),
    NONE("noAnimation"),
}

/** Page colors as CSS hex. [AUTO] follows the system dark mode. */
enum class ReadingTheme(val background: String, val text: String) {
    AUTO("", ""),
    PAPER("#FBFBF3", "#2B2B2B"),
    SEPIA("#F4ECD8", "#5B4636"),
    GREEN("#CCE8CF", "#2B3A2E"),
    NIGHT("#121212", "#C8C8C8"),
    ;

    fun resolve(systemDark: Boolean): ReadingTheme = when (this) {
        AUTO -> if (systemDark) NIGHT else PAPER
        else -> this
    }
}

data class ReaderSettings(
    /** Font size in em, relative to the book's base size. */
    val fontScale: Float = 1.2f,
    val lineHeight: Float = 1.8f,
    /** Horizontal page gap in percent of page width. */
    val sideMargin: Float = 6f,
    val pageTurn: PageTurnStyle = PageTurnStyle.SLIDE,
    val theme: ReadingTheme = ReadingTheme.AUTO,
) {
    companion object {
        const val FONT_SCALE_MIN = 0.8f
        const val FONT_SCALE_MAX = 2.4f
        const val LINE_HEIGHT_MIN = 1.2f
        const val LINE_HEIGHT_MAX = 2.6f
        const val SIDE_MARGIN_MIN = 2f
        const val SIDE_MARGIN_MAX = 16f
    }
}

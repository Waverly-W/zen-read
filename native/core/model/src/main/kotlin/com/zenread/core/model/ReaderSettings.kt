package com.zenread.core.model

enum class PageTurnStyle(val jsName: String) {
    SLIDE("slide"),
    SCROLL("scroll"),
    NONE("noAnimation"),
}

/** Page colors as CSS hex. [AUTO] follows the app's dark mode (Settings › Appearance). */
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
    /** Space between paragraphs in em (split above and below each paragraph). */
    val paragraphSpacing: Float = 1.0f,
    /** First-line indent in em (≈ CJK characters); [TEXT_INDENT_BOOK] keeps the book's own. */
    val textIndent: Float = TEXT_INDENT_BOOK,
    /** Horizontal page gap in percent of page width. */
    val sideMargin: Float = 6f,
    val pageTurn: PageTurnStyle = PageTurnStyle.SLIDE,
    val theme: ReadingTheme = ReadingTheme.AUTO,
    /** Body font. */
    val font: ReaderFont = ReaderFont.Book,
    /** Separate face for Latin letters and digits; null uses [font] for everything. */
    val latinFont: ReaderFont? = null,
    /** CSS weight for body text, [FONT_WEIGHT_MIN]..[FONT_WEIGHT_MAX] in steps of 100. */
    val fontWeight: Int = 400,
) {
    companion object {
        const val FONT_SCALE_MIN = 0.8f
        const val FONT_SCALE_MAX = 2.4f
        const val LINE_HEIGHT_MIN = 1.2f
        const val LINE_HEIGHT_MAX = 2.6f
        const val PARAGRAPH_SPACING_MIN = 0f
        const val PARAGRAPH_SPACING_MAX = 3f
        /** foliate-js leaves the publisher's indent alone for negative values. */
        const val TEXT_INDENT_BOOK = -1f
        val TEXT_INDENT_OPTIONS = listOf(TEXT_INDENT_BOOK, 0f, 1f, 2f, 3f)
        const val SIDE_MARGIN_MIN = 2f
        const val SIDE_MARGIN_MAX = 16f
        const val FONT_WEIGHT_MIN = 300
        const val FONT_WEIGHT_MAX = 700
    }
}

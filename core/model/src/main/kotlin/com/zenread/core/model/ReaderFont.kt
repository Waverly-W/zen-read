package com.zenread.core.model

/** CSS generic families that resolve to real faces in Android WebView (Roboto, Noto Serif, Droid Sans Mono…). */
enum class SystemFontFamily(val css: String) {
    SERIF("serif"),
    SANS_SERIF("sans-serif"),
    MONOSPACE("monospace"),
}

/**
 * A reading font choice, persisted as its [id].
 *
 * - `book` — keep the book's own fonts (default).
 * - `system:<family>` — a [SystemFontFamily].
 * - `bundled:<file>` — a font shipped in the APK assets.
 * - `file:<file>` — an imported or downloaded font in the app's fonts directory.
 */
sealed interface ReaderFont {
    val id: String

    data object Book : ReaderFont {
        override val id = "book"
    }

    data class System(val family: SystemFontFamily) : ReaderFont {
        override val id = "$SYSTEM_PREFIX${family.css}"
    }

    data class Bundled(val fileName: String) : ReaderFont {
        override val id = "$BUNDLED_PREFIX$fileName"
    }

    data class Installed(val fileName: String) : ReaderFont {
        override val id = "$FILE_PREFIX$fileName"
    }

    companion object {
        private const val SYSTEM_PREFIX = "system:"
        private const val BUNDLED_PREFIX = "bundled:"
        private const val FILE_PREFIX = "file:"

        /** Parses a stored id. Unknown or malformed ids return null so callers fall back to a default. */
        fun parse(id: String?): ReaderFont? = when {
            id == null -> null
            id == Book.id -> Book
            id.startsWith(SYSTEM_PREFIX) ->
                SystemFontFamily.entries.firstOrNull { it.css == id.removePrefix(SYSTEM_PREFIX) }?.let(::System)
            id.startsWith(BUNDLED_PREFIX) -> id.removePrefix(BUNDLED_PREFIX).takeIf(::isFontFileName)?.let(::Bundled)
            id.startsWith(FILE_PREFIX) -> id.removePrefix(FILE_PREFIX).takeIf(::isFontFileName)?.let(::Installed)
            else -> null
        }

        private val FONT_FILE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9_-]{0,127}(?:\.[A-Za-z0-9_-]{1,32})*\.(?:ttf|otf|woff2?)""")

        /**
         * True for a plain file name the reader may serve as a font: ASCII letters, digits, `_`, `-`
         * and inner dots, a font extension, and no path separators or `..` segments.
         */
        fun isFontFileName(name: String): Boolean = FONT_FILE_NAME.matches(name)
    }
}

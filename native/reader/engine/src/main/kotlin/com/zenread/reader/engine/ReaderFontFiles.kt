package com.zenread.reader.engine

import android.content.Context
import com.zenread.core.model.ReaderFont
import com.zenread.core.model.SystemFontFamily
import java.io.File

/**
 * Where reading fonts live and how the reader page addresses them.
 *
 * - Installed fonts (imported or downloaded): `filesDir/fonts/<file>`, served at `/fonts/<file>`.
 * - Bundled fonts: APK assets `fonts/<file>`, served at `/fonts/bundled/<file>`.
 *
 * Installed files are named after a hash of their bytes, so a URL always means the same bytes
 * and the WebView may cache it.
 */
object ReaderFontFiles {
    const val PATH = "/fonts/"
    const val BUNDLED_SEGMENT = "bundled"
    const val ASSET_DIRECTORY = "fonts"
    private const val DIRECTORY = "fonts"

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    fun installedUrl(fileName: String): String = "${ReaderOrigin.ORIGIN}$PATH$fileName"

    fun bundledUrl(fileName: String): String = "${ReaderOrigin.ORIGIN}$PATH$BUNDLED_SEGMENT/$fileName"

    /** The font MIME type by extension, or null when the name is not a servable font file. */
    fun mimeType(fileName: String): String? = when {
        !ReaderFont.isFontFileName(fileName) -> null
        else -> when (fileName.substringAfterLast('.').lowercase()) {
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> null
        }
    }

    /**
     * Maps a request path below [PATH] to a file inside [installedDir] or a bundled asset name.
     * Returns null for anything else: nested paths, `..`, encoded separators, non-font names.
     */
    fun resolve(path: String, installedDir: File): Target? {
        val segments = path.split('/')
        return when {
            segments.size == 1 && mimeType(segments[0]) != null -> {
                val dir = installedDir.canonicalFile
                val file = File(dir, segments[0]).canonicalFile
                if (file.parentFile == dir) Target.Installed(file) else null
            }
            segments.size == 2 && segments[0] == BUNDLED_SEGMENT && mimeType(segments[1]) != null ->
                Target.Bundled("$ASSET_DIRECTORY/${segments[1]}")
            else -> null
        }
    }

    sealed interface Target {
        data class Installed(val file: File) : Target
        data class Bundled(val assetPath: String) : Target
    }
}

/** The `fontName` / `fontPath` pairs foliate-js's `readerFontCSS` takes for a [ReaderFont]. */
object ReaderFontStyle {
    /** foliate-js's sentinel for "keep the book's fonts". */
    private const val BOOK = "book"

    data class Face(val name: String, val path: String)

    /** Body font. `book` keeps publisher fonts; generic families are emitted unquoted. */
    fun body(font: ReaderFont): Face = when (font) {
        ReaderFont.Book -> Face(BOOK, BOOK)
        is ReaderFont.System -> Face(font.family.css, "")
        is ReaderFont.Bundled -> Face(familyName("bundled", font.fileName), ReaderFontFiles.bundledUrl(font.fileName))
        is ReaderFont.Installed -> Face(familyName("file", font.fileName), ReaderFontFiles.installedUrl(font.fileName))
    }

    /**
     * Latin-only override, or null to use the body font for every script. System families map
     * to `local()` faces inside a Latin `unicode-range`, so CJK punctuation stays in the body face.
     */
    fun latin(font: ReaderFont?): Face? = when (font) {
        null, ReaderFont.Book -> null
        is ReaderFont.System -> Face(
            if (font.family == SystemFontFamily.SANS_SERIF) "system" else font.family.css,
            "",
        )
        else -> body(font)
    }

    /** A CSS family name unique to the file, so a changed font never reuses a cached face. */
    internal fun familyName(kind: String, fileName: String): String =
        "zenfont-$kind-" + fileName.replace(Regex("[^A-Za-z0-9_-]"), "_")
}

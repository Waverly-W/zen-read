package com.zenread.feature.fonts

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.zenread.core.model.ReaderFont
import com.zenread.core.model.SystemFontFamily
import com.zenread.data.fonts.FontCatalog
import com.zenread.data.fonts.FontFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

/** Typefaces loaded for previews. Android memory-maps font files, so a few entries are cheap. */
private object PreviewTypefaces {
    private val cache = LruCache<String, Typeface>(16)

    fun peek(key: String): Typeface? = cache.get(key)

    fun load(key: String, loader: () -> Typeface): Typeface? = cache.get(key) ?: try {
        loader().also { cache.put(key, it) }
    } catch (e: RuntimeException) {
        // A file Android's parser rejects still works in the reader if Chromium accepts it.
        Log.w("ZenFonts", "No preview for $key: ${e.message}")
        null
    }
}

/**
 * The family to draw a font's name in, or null for the app default (the book's own font,
 * WOFF/WOFF2 files that Android cannot load, or a preview that is still loading).
 */
@Composable
internal fun rememberPreviewFamily(font: ReaderFont?, installedFile: (String) -> File?): FontFamily? {
    if (font is ReaderFont.System) {
        return when (font.family) {
            SystemFontFamily.SERIF -> FontFamily.Serif
            SystemFontFamily.SANS_SERIF -> FontFamily.SansSerif
            SystemFontFamily.MONOSPACE -> FontFamily.Monospace
        }
    }
    val context = LocalContext.current.applicationContext
    val key = font?.id
    val family by produceState(PreviewTypefaces.peek(key.orEmpty())?.toFamily(), key) {
        value = withContext(Dispatchers.IO) { loadTypeface(context, font, installedFile)?.toFamily() }
    }
    return family
}

private fun loadTypeface(context: Context, font: ReaderFont?, installedFile: (String) -> File?): Typeface? = when (font) {
    is ReaderFont.Bundled -> FontCatalog.bundled.firstOrNull { it.fileName == font.fileName }?.let { bundled ->
        PreviewTypefaces.load(font.id) { Typeface.createFromAsset(context.assets, bundled.assetPath) }
    }
    is ReaderFont.Installed -> {
        val previewable = FontFormat.fromExtension(font.fileName.substringAfterLast('.'))?.previewable == true
        val file = if (previewable) installedFile(font.fileName) else null
        file?.let { PreviewTypefaces.load(font.id) { Typeface.createFromFile(it) } }
    }
    else -> null
}

private fun Typeface.toFamily(): FontFamily = FontFamily(ComposeTypeface(this))

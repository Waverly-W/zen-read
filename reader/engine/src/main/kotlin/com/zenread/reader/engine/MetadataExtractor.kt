package com.zenread.reader.engine

import android.content.Context
import android.view.View
import com.zenread.core.model.BookFormat
import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.ReadingTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class BookMetadata(
    val title: String?,
    val author: String?,
    /**
     * Base64 `data:` URL, or null. Not always `data:image/…`: foliate-js builds MOBI/AZW3
     * covers as untyped Blobs (`data:application/octet-stream`), so callers must check
     * the bytes themselves.
     */
    val coverDataUrl: String?,
) {
    companion object {
        internal fun parse(obj: JsonObject) = BookMetadata(
            title = obj["title"].displayText(),
            author = obj["author"].displayText(),
            coverDataUrl = obj.string("cover")?.takeIf { it.startsWith("data:") && ";base64," in it },
        )
    }
}

class BookParseException(message: String) : Exception(message)

/**
 * Opens a book in an offscreen WebView with `importing=true`; foliate-js replies
 * with `onMetadata` (title, author, cover) or `onImportError`.
 */
@Singleton
class MetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: ReaderWebViewFactory,
    private val files: BookFileRegistry,
) {
    suspend fun extract(file: File, format: BookFormat): BookMetadata = withContext(Dispatchers.Main) {
        if (!factory.isSupported) throw BookParseException("System WebView is too old")
        val result = CompletableDeferred<BookMetadata>()
        val scope = MainScope()
        val registration = files.register(file, format.primaryExtension)
        val webView = factory.create(context) {
            result.completeExceptionally(BookParseException("Reader process crashed"))
        }
        try {
            ReaderBridge(scope) { name, args ->
                val payload = args.firstOrNull()
                when (name) {
                    "onMetadata" -> (payload as? JsonObject)?.let { result.complete(BookMetadata.parse(it)) }
                    "onImportError" -> {
                        val error = (payload as? JsonObject)?.string("name")
                        result.completeExceptionally(
                            BookParseException(
                                if (error == "PasswordException") "Encrypted PDFs are not supported" else "Unreadable book ($error)",
                            ),
                        )
                    }
                }
                JsonPrimitive(true)
            }.install(webView)

            // Never attached to a window: give it a size so layout-dependent code runs.
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, 1080, 1920)
            webView.loadUrl(
                ReaderOrigin.readerUrl(
                    bookUrl = registration.url,
                    initialCfi = null,
                    style = ReaderStyle.build(ReaderSettings(), ReadingTheme.PAPER, locale = "en"),
                    importing = true,
                ),
            )
            withTimeout(45_000) { result.await() }
        } finally {
            scope.cancel()
            webView.destroy()
            files.unregister(registration)
        }
    }
}

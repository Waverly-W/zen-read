package com.zenread.data.ai.rag

import android.content.Context
import android.view.View
import android.webkit.WebView
import com.zenread.core.model.BookFormat
import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.ReadingTheme
import com.zenread.reader.engine.BookFileRegistry
import com.zenread.reader.engine.ReaderBridge
import com.zenread.reader.engine.ReaderOrigin
import com.zenread.reader.engine.ReaderStyle
import com.zenread.reader.engine.ReaderWebViewFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A paragraph-like block of text and the CFI of its start. */
data class TextBlock(val text: String, val cfi: String)

/** One spine section (EPUB chapter file, PDF page…) with the TOC title that covers it. */
data class ExtractedSection(val index: Int, val title: String, val blocks: List<TextBlock>)

class BookExtractionException(message: String) : Exception(message)

/**
 * Extracts the text of a whole book with foliate-js in an offscreen WebView, section by
 * section, keeping a CFI per block so retrieved passages can jump back into the reader.
 * CFIs are computed exactly like foliate-js search results (`view.getCFI(index, range)`
 * on `section.createDocument()`), so they resolve in the normal reader.
 *
 * Ported in spirit from upstream `BookContentSearchRepository.extractChaptersForIndex`
 * (Modu / ReadAny, GPL-3.0), which also drives a headless reader page.
 */
@Singleton
class BookTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: ReaderWebViewFactory,
    private val files: BookFileRegistry,
) {
    /**
     * Calls [onSection] for every section in spine order; [onProgress] gets (done, total).
     * Must not run concurrently with itself for the same book (the caller serialises).
     */
    suspend fun extract(
        file: File,
        format: BookFormat,
        onProgress: suspend (done: Int, total: Int) -> Unit,
        onSection: suspend (ExtractedSection) -> Unit,
    ) = withContext(Dispatchers.Main) {
        if (!factory.isSupported) throw BookExtractionException("System WebView is too old")
        if (!file.exists()) throw BookExtractionException("The book file is missing")
        val scope = MainScope()
        var loadError: String? = null
        val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        val registration = files.register(file, format.primaryExtension)
        val webView = factory.create(context) { loadError = "Reader process crashed" }
        try {
            ReaderBridge(scope) { name, args ->
                val payload = args.firstOrNull() as? JsonObject
                when (name) {
                    "onReaderLoadError" -> loadError = "Unreadable book"
                    RESULT_HANDLER -> payload?.get("key")?.jsonPrimitive?.contentOrNull?.let { key ->
                        pending.remove(key)?.complete(payload)
                    }
                }
                null
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
                    importing = false,
                ),
            )

            // Only the parsed book is needed, not a rendered page: poll for it.
            withTimeout(LOAD_TIMEOUT_MS) {
                while (webView.evaluate("!!(globalThis.reader && reader.view && reader.view.book && reader.view.book.sections)") != "true") {
                    loadError?.let { throw BookExtractionException(it) }
                    delay(250)
                }
            }
            webView.evaluate(HELPERS)

            suspend fun call(key: String, script: String): JsonObject {
                val deferred = CompletableDeferred<JsonObject>()
                pending[key] = deferred
                webView.evaluate(script)
                return withTimeout(SECTION_TIMEOUT_MS) { deferred.await() }
            }

            val meta = call("meta", "zenIndexMeta('meta')")
            val titles = (meta["titles"] as? JsonArray).orEmpty().map { it.jsonPrimitive.contentOrNull.orEmpty() }
            val total = meta["count"]?.jsonPrimitive?.intOrNull ?: 0
            if (total == 0) throw BookExtractionException("No readable sections were found")
            val sectionOnly = format == BookFormat.PDF || format == BookFormat.CBZ
            onProgress(0, total)
            for (index in 0 until total) {
                ensureActive()
                val result = call("s$index", "zenIndexSection('s$index', $index, $sectionOnly)")
                val sectionCfi = result["sectionCfi"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val blocks = (result["blocks"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val text = obj["t"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (text.isEmpty()) return@mapNotNull null
                    val cfi = obj["c"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: sectionCfi
                    TextBlock(text, cfi)
                }
                if (blocks.isNotEmpty()) onSection(ExtractedSection(index, titles.getOrNull(index).orEmpty(), blocks))
                onProgress(index + 1, total)
            }
        } finally {
            pending.values.forEach { it.cancel() }
            scope.cancel()
            webView.destroy()
            files.unregister(registration)
        }
    }

    /** Runs [script] and returns its JSON-encoded result (`"null"` for promises/undefined). */
    private suspend fun WebView.evaluate(script: String): String = suspendCancellableCoroutine { cont ->
        evaluateJavascript("(function(){ try { return ($script) } catch (e) { return null } })()") { value ->
            if (cont.isActive) cont.resume(value ?: "null")
        }
    }

    private companion object {
        const val RESULT_HANDLER = "zenIndexResult"
        const val LOAD_TIMEOUT_MS = 90_000L
        const val SECTION_TIMEOUT_MS = 60_000L

        /**
         * Host-only helpers (not part of upstream foliate-js). Results return through the
         * bridge because `evaluateJavascript` cannot await promises.
         */
        val HELPERS = """
            (function () {
              const send = (key, data) => window.flutter_inappwebview.callHandler('$RESULT_HANDLER', Object.assign({ key }, data))
              const norm = s => (s || '').replace(/\s+/g, ' ').trim()
              window.zenIndexMeta = async key => {
                const view = reader.view, book = view.book, count = (book.sections || []).length
                const titles = new Array(count).fill(null)
                const visit = async items => {
                  for (const item of items || []) {
                    try {
                      const r = await view.resolveNavigation(item.href)
                      if (r && r.index != null && titles[r.index] == null) titles[r.index] = norm(item.label)
                    } catch (e) {}
                    await visit(item.subitems)
                  }
                }
                try { await visit(book.toc) } catch (e) {}
                let last = ''
                await send(key, { count, titles: titles.map(t => (last = t || last)) })
                return true
              }
              const BLOCK = 'p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,dt,dd,td,th,figcaption,div,section,article,aside'
              window.zenIndexSection = async (key, index, sectionOnly) => {
                const view = reader.view, section = view.book.sections[index]
                const out = { blocks: [], sectionCfi: '' }
                try {
                  out.sectionCfi = view.getCFI(index)
                  if (!section || !section.createDocument || section.linear === 'no') return send(key, out)
                  const doc = await section.createDocument()
                  const body = doc && doc.body
                  if (!body) return send(key, out)
                  const bodyText = norm(body.textContent)
                  if (!sectionOnly) {
                    let covered = 0
                    for (const el of body.querySelectorAll(BLOCK)) {
                      if (el.querySelector(BLOCK)) continue
                      const t = norm(el.textContent)
                      if (!t) continue
                      let c = null
                      try {
                        const range = doc.createRange()
                        range.selectNodeContents(el)
                        range.collapse(true)
                        c = view.getCFI(index, range)
                      } catch (e) {}
                      out.blocks.push({ t, c })
                      covered += t.length
                    }
                    // Text outside leaf blocks (bare <body> text, odd markup): keep it whole.
                    if (covered < bodyText.length * 0.5) out.blocks = []
                  }
                  if (!out.blocks.length && bodyText) out.blocks = [{ t: bodyText, c: null }]
                } catch (e) {
                  console.warn('zenIndexSection', index, e)
                }
                return send(key, out)
              }
              return true
            })()
        """.trimIndent()
    }
}

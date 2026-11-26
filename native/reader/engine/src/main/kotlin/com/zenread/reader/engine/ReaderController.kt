package com.zenread.reader.engine

import android.webkit.WebView
import com.zenread.core.model.Annotation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Commands into the page (functions `book.js` exposes on `window`). */
class ReaderController(private val webView: WebView) {
    fun nextPage() = call("nextPage()")
    fun prevPage() = call("prevPage()")
    fun nextSection() = call("nextSection()")
    fun prevSection() = call("prevSection()")
    fun goToHref(href: String) = call("goToHref(${JsonPrimitive(href)})")
    fun goToCfi(cfi: String) = call("goToCfi(${JsonPrimitive(cfi)})")
    fun goToPercent(fraction: Float) = call("goToPercent(${fraction.coerceIn(0f, 1f)})")
    fun changeStyle(style: JsonObject) = call("changeStyle($style)")

    fun clearSelection() = call("clearSelection()")

    /**
     * Replaces every drawn mark (highlights, underlines, bookmarks) with [annotations], then
     * reports the current page's bookmark as a `BookmarkState` event. foliate-js only reports
     * bookmarks on relocation, and the first relocation happens before marks are loaded.
     */
    fun replaceAnnotations(annotations: List<Annotation>) = call(
        """
        replaceReadingAnnotations(${JsonArray(annotations.map(::toJs))}).then(async () => {
          const cfi = reader.view.lastLocation?.cfi
          if (!cfi) return
          const { collapse, compare } = await import(location.origin + '/reader/src/epubcfi.js')
          const start = collapse(cfi), end = collapse(cfi, true)
          const mark = [...reader.annotationsByValue.values()].find(a => a.type === 'bookmark' &&
            compare(start, collapse(a.value)) <= 0 && compare(end, collapse(a.value)) > 0)
          window.flutter_inappwebview.callHandler('$BOOKMARK_STATE', { cfi: mark?.value ?? null })
        })
        """.trimIndent(),
    )

    /** Draws or redraws one mark; a mark on the same range is replaced. */
    fun showAnnotation(annotation: Annotation) = call("addAnnotation(${toJs(annotation)})")

    /** Removes the mark on [cfi]. For a bookmark, foliate-js answers with a `handleBookmark` remove. */
    fun hideAnnotation(cfi: String) = call("removeAnnotation(${JsonPrimitive(cfi)})")

    /** Asks foliate-js to bookmark the current page; it replies with a `BookmarkRequest`. */
    fun bookmarkCurrentPage() = call("addBookmarkHere()")

    /**
     * Turns paragraph translation on or off (upstream `translateCurrentChapter`). foliate-js then
     * sends a [ReaderEvent.TranslateRequest] per visible paragraph, tagged with [generation].
     *
     * @param mode `off`, `bilingual` or `translation-only` (foliate-js `TranslationMode`).
     * @param textStyle `{color, italic, bold, size, font}` for translated paragraphs, or null.
     * @param clear drop translations already in the page (the service or language changed).
     * @param prefetchPages pages ahead to pre-translate, 0–5.
     */
    fun setTranslation(mode: String, generation: Long, textStyle: JsonObject?, clear: Boolean, prefetchPages: Int) = call(
        """
        (async () => {
          const view = reader.view
          if (!view?.setTranslationMode) return
          await view.setTranslationMode('off')
          if (${textStyle != null}) view.setTranslationTextStyle?.(${textStyle ?: "null"})
          if ($clear) view.clearTranslations?.()
          await view.setTranslationMode(${JsonPrimitive(mode)}, $generation)
          view.setTranslationPrefetchPages?.(${prefetchPages.coerceIn(0, 5)})
        })().catch(e => console.warn(e))
        """.trimIndent(),
    )

    private fun call(script: String) {
        // Before onLoadEnd `reader` is undefined; swallow instead of surfacing JS errors.
        webView.evaluateJavascript("try { $script } catch (e) { console.warn(e) }", null)
    }

    // --- Async evaluation (phase 3) ------------------------------------------------------------

    /**
     * Runs [body] as the body of an async JS function and returns its (JSON) result.
     * `evaluateJavascript` cannot await promises, so the result comes back through the bridge
     * as an [ASYNC_RESULT] call, which [ReaderBridge] routes to [deliverAsyncResult].
     * Throws [ScriptException] when the script throws or no answer arrives within [timeoutMs].
     */
    suspend fun evaluateAsync(body: String, timeoutMs: Long = 35_000): JsonElement? {
        val id = asyncIds.incrementAndGet()
        val result = CompletableDeferred<JsonElement?>()
        asyncResults[id] = result
        val script = """
            (async () => { $body })().then(
              v => window.flutter_inappwebview.callHandler('$ASYNC_RESULT', $id, v === undefined ? null : v),
              e => window.flutter_inappwebview.callHandler('$ASYNC_RESULT', $id, null, String(e && e.message || e)))
        """.trimIndent()
        try {
            webView.evaluateJavascript(script, null)
            return withTimeoutOrNull(timeoutMs) { result.await() }
                ?: if (result.isCompleted) null else throw ScriptException("Timed out")
        } finally {
            asyncResults.remove(id)
        }
    }

    class ScriptException(message: String) : Exception(message)

    // --- Read aloud (phase 3) ------------------------------------------------------------------

    /**
     * Starts the speech cursor at [fromCfi] or at the visible page, highlighting the first
     * sentence. [paragraphMode] groups sentences into passages of up to [maxCharacters].
     */
    suspend fun ttsStart(fromCfi: String?, paragraphMode: Boolean, maxCharacters: Int = 240): TtsDetail? = parseTtsDetail(
        evaluateAsync(
            """
            window.ttsSetParagraphMode($paragraphMode, $maxCharacters)
            const text = ${if (fromCfi != null) "await window.ttsFromCfi(${JsonPrimitive(fromCfi)}, false)" else "await window.ttsHere()"}
            $TTS_DETAIL
            """.trimIndent(),
        ),
    )

    /** Advances the speech cursor (crossing chapters) and highlights the new sentence. */
    suspend fun ttsNext(): TtsDetail? = parseTtsDetail(evaluateAsync("const text = await window.ttsNext()\n$TTS_DETAIL"))

    suspend fun ttsPrev(): TtsDetail? = parseTtsDetail(evaluateAsync("const text = await window.ttsPrev()\n$TTS_DETAIL"))

    /** The current sentence and the next ones (across loaded chapters) without moving the cursor. */
    suspend fun ttsCollect(count: Int): List<TtsDetail> =
        (evaluateAsync("return window.ttsCollectDetails($count, true, 1)") as? JsonArray)
            ?.mapNotNull { parseTtsDetail(it) }.orEmpty()

    /** Moves the in-sentence highlight/page to the spoken position. */
    fun ttsFollowProgress(cfi: String?, text: String, offset: Int?, fraction: Float?) = call(
        "window.ttsFollowProgress?.(${
            buildJsonObject {
                cfi?.let { put("cfi", it) }
                put("text", text)
                offset?.let { put("offset", it) }
                fraction?.let { put("fraction", it) }
            }
        })",
    )

    /** While true the page stops drawing the highlight and turning pages (app in background). */
    fun ttsSetBackground(background: Boolean) = call("window.ttsSetBackground?.($background)")

    /** Drops the speech cursor and its highlight. */
    fun ttsStop() = call("window.ttsStop?.()")

    /**
     * Keeps the renderer process at the app's priority while hidden, so the system does not
     * reclaim it while read-aloud is driving it with the screen off.
     */
    fun keepRendererAlive(enabled: Boolean) {
        if (enabled) {
            if (savedRendererPolicy == null) {
                savedRendererPolicy = webView.rendererRequestedPriority to webView.rendererPriorityWaivedWhenNotVisible
            }
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        } else {
            val (priority, waived) = savedRendererPolicy ?: return
            savedRendererPolicy = null
            webView.setRendererPriorityPolicy(priority, waived)
        }
    }

    private var savedRendererPolicy: Pair<Int, Boolean>? = null

    /** A speech unit reported by foliate-js. */
    data class TtsDetail(val text: String, val cfi: String?, val section: Int?)

    internal companion object {
        /** Host-only handler name; not part of upstream foliate-js. */
        const val BOOKMARK_STATE = "zenBookmarkState"

        /** Host-only handler carrying [evaluateAsync] results: `(id, value, error?)`. */
        const val ASYNC_RESULT = "zenAsyncResult"

        private val asyncIds = AtomicInteger()
        private val asyncResults = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()

        /** Called by [ReaderBridge] on the main thread. */
        fun deliverAsyncResult(args: JsonArray) {
            val id = (args.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return
            val result = asyncResults[id] ?: return
            val error = (args.getOrNull(2) as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (error != null) {
                result.completeExceptionally(ScriptException(error))
            } else {
                result.complete(args.getOrNull(1)?.takeUnless { it is JsonNull })
            }
        }

        /** Result of a cursor move: `text` (empty at the end of the book) plus cfi and section. */
        private const val TTS_DETAIL = """
            if (!text || !String(text).trim()) return null
            const detail = window.ttsCurrentDetail() ?? {}
            return { text: String(text), cfi: detail.cfi ?? null, section: reader.view.tts?.sectionIndex ?? null }
        """

        fun parseTtsDetail(element: JsonElement?): TtsDetail? {
            val detail = element as? JsonObject ?: return null
            val text = detail.string("text")?.takeIf { it.isNotBlank() } ?: return null
            return TtsDetail(text = text, cfi = detail.string("cfi"), section = detail.int("section"))
        }

        /** The shape `book.js` expects (see upstream `BookNote.toJson`). */
        fun toJs(annotation: Annotation): JsonObject = buildJsonObject {
            put("id", annotation.id)
            put("value", annotation.cfi)
            put("type", annotation.type.jsName)
            put("color", "#${annotation.color}")
            put("note", annotation.text)
            put("hasReaderNote", annotation.hasNote)
        }
    }
}

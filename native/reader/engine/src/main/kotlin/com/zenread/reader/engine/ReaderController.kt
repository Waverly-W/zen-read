package com.zenread.reader.engine

import android.webkit.WebView
import com.zenread.core.model.Annotation
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
     * Plain text of the chapter on screen (whitespace collapsed), or null before the page is
     * ready. Call on the main thread.
     */
    suspend fun chapterText(): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript("(function(){ try { return theChapterContent() } catch (e) { return null } })()") { raw ->
            val text = raw?.let { runCatching { Json.parseToJsonElement(it).jsonPrimitive.contentOrNull }.getOrNull() }
            cont.resume(
                text?.replace(Regex("[ \\t\\u00A0]+"), " ")
                    ?.replace(Regex("\\s*\\n\\s*(\\n\\s*)+"), "\n\n")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun call(script: String) {
        // Before onLoadEnd `reader` is undefined; swallow instead of surfacing JS errors.
        webView.evaluateJavascript("try { $script } catch (e) { console.warn(e) }", null)
    }

    internal companion object {
        /** Host-only handler name; not part of upstream foliate-js. */
        const val BOOKMARK_STATE = "zenBookmarkState"

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

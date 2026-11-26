package com.zenread.reader.engine

import android.webkit.WebView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Commands into the page (functions `book.js` exposes on `window`). */
class ReaderController(private val webView: WebView) {
    fun nextPage() = call("nextPage()")
    fun prevPage() = call("prevPage()")
    fun nextSection() = call("nextSection()")
    fun prevSection() = call("prevSection()")
    fun goToHref(href: String) = call("goToHref(${JsonPrimitive(href)})")
    fun goToPercent(fraction: Float) = call("goToPercent(${fraction.coerceIn(0f, 1f)})")
    fun changeStyle(style: JsonObject) = call("changeStyle($style)")

    private fun call(script: String) {
        // Before onLoadEnd `reader` is undefined; swallow instead of surfacing JS errors.
        webView.evaluateJavascript("try { $script } catch (e) { console.warn(e) }", null)
    }
}

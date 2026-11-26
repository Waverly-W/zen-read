package com.zenread.reader.engine

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JS ↔ Kotlin bridge that impersonates flutter_inappwebview's `callHandler`, so the
 * vendored foliate-js runs unmodified.
 *
 * JS side: `window.flutter_inappwebview.callHandler(name, ...args)` → Promise.
 * Transport: an origin-restricted WebMessageListener (no addJavascriptInterface).
 */
class ReaderBridge(
    private val scope: CoroutineScope,
    private val handler: suspend (name: String, args: JsonArray) -> JsonElement?,
) {
    /** Must run before the first `loadUrl`. Requires [ReaderWebViewFactory.isSupported]. */
    fun install(webView: WebView) {
        check(
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        ) { "System WebView lacks WEB_MESSAGE_LISTENER / DOCUMENT_START_SCRIPT" }
        val origins = setOf(ReaderOrigin.ORIGIN)
        WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, origins) { _, message, _, isMainFrame, reply ->
            if (!isMainFrame) return@addWebMessageListener
            val call = runCatching { Json.parseToJsonElement(message.data.orEmpty()).jsonObject }.getOrNull()
                ?: return@addWebMessageListener
            val id = call["id"]?.jsonPrimitive?.int ?: return@addWebMessageListener
            val name = call["name"]?.jsonPrimitive?.content ?: return@addWebMessageListener
            val args = call["args"]?.jsonArray ?: JsonArray(emptyList())
            Log.v(TAG, "call $name")
            scope.launch {
                val response = try {
                    val result = handler(name, args) ?: JsonNull
                    buildJsonObject { put("id", id); put("result", result) }
                } catch (e: Exception) {
                    Log.w(TAG, "Handler $name failed", e)
                    buildJsonObject { put("id", id); put("error", e.message ?: e.javaClass.simpleName) }
                }
                reply.postMessage(response.toString())
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, SHIM, origins)
    }

    private companion object {
        const val TAG = "ZenReaderBridge"
        const val OBJECT_NAME = "zenBridge"

        // ES5 so it parses on every WebView the bootstrap is willing to report errors on.
        val SHIM = """
            (function () {
              if (window.top !== window || window.flutter_inappwebview || !window.$OBJECT_NAME) return;
              var bridge = window.$OBJECT_NAME, seq = 0, pending = {};
              bridge.addEventListener('message', function (event) {
                var msg = JSON.parse(event.data), p = pending[msg.id];
                if (!p) return;
                delete pending[msg.id];
                if (msg.error !== undefined) p.reject(new Error(msg.error)); else p.resolve(msg.result);
              });
              window.flutter_inappwebview = {
                callHandler: function (name) {
                  var args = Array.prototype.slice.call(arguments, 1), id = ++seq;
                  return new Promise(function (resolve, reject) {
                    pending[id] = { resolve: resolve, reject: reject };
                    bridge.postMessage(JSON.stringify({ id: id, name: name, args: args }));
                  });
                }
              };
              window.dispatchEvent(new Event('flutterInAppWebViewPlatformReady'));
            })();
        """.trimIndent()
    }
}

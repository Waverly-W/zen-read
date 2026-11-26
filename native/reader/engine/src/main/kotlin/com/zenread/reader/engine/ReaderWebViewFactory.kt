package com.zenread.reader.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ZenReader"

@Singleton
class ReaderWebViewFactory @Inject constructor(
    private val files: BookFileRegistry,
) {
    /** True when the system WebView supports what [ReaderBridge] needs. */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, onRendererGone: () -> Unit): WebView {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(ReaderOrigin.HOST)
            .addPathHandler(ReaderOrigin.READER_PATH, ReaderAssetHandler(context.applicationContext))
            .addPathHandler(ReaderOrigin.BOOK_PATH, BookFileHandler(files))
            .build()

        return ReaderWebView(context).apply {
            // Must be MATCH_PARENT: a WRAP_CONTENT WebView (Compose's AndroidView default)
            // reports a 0px viewport height, so foliate-js's `100vh` layout collapses.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
            }
            // Avoid a white flash before the page paints its own background.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                // Chapters render in blob:/about: iframes; only keep the top page on our origin.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && request.url.host != ReaderOrigin.HOST

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.w(TAG, "Renderer gone (crash=${detail.didCrash()})")
                    onRendererGone()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    val text = "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                    when (message.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, text)
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, text)
                        else -> if (debuggable) Log.d(TAG, text)
                    }
                    return true
                }
            }
        }
    }
}

/** Serves files under the `reader/` asset directory. */
private class ReaderAssetHandler(private val context: Context) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        if (path.split('/').any { it == ".." }) return null
        return try {
            WebResourceResponse(mimeTypeOf(path), null, context.assets.open("reader/$path"))
        } catch (_: IOException) {
            null
        }
    }
}

/** Serves files registered in [BookFileRegistry]. */
private class BookFileHandler(private val files: BookFileRegistry) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val file = files.resolve(path) ?: return null
        return WebResourceResponse(
            if (path.endsWith(".pdf")) "application/pdf" else "application/octet-stream",
            null,
            200,
            "OK",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            FileInputStream(file),
        )
    }
}

internal fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
    "html", "htm" -> "text/html"
    "js", "mjs" -> "text/javascript"
    "css" -> "text/css"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "wasm" -> "application/wasm"
    "woff2" -> "font/woff2"
    "woff" -> "font/woff"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    else -> "application/octet-stream"
}

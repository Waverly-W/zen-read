package com.zenread.reader.engine

import android.net.Uri
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Everything the reader page loads is served from one virtual HTTPS origin via
 * WebViewAssetLoader. No local port is opened, and the page never sees file:// URLs.
 */
object ReaderOrigin {
    const val HOST = "appassets.androidplatform.net"
    const val ORIGIN = "https://$HOST"
    const val READER_PATH = "/reader/"
    const val BOOK_PATH = "/book/"

    /**
     * Builds the foliate-js entry URL. Each parameter is JSON-encoded, matching
     * what `book.js` parses with `JSON.parse(urlParams.get(...))`.
     */
    fun readerUrl(
        bookUrl: String,
        initialCfi: String?,
        style: JsonObject,
        importing: Boolean,
    ): String {
        val params: List<Pair<String, JsonElement>> = buildList {
            add("importing" to JsonPrimitive(importing))
            if (importing) add("inspectDocumentOnImport" to JsonPrimitive(false))
            add("url" to JsonPrimitive(bookUrl))
            add("initialCfi" to JsonPrimitive(initialCfi.orEmpty()))
            add("style" to style)
            add("readingRules" to buildJsonObject {
                put("convertChineseMode", "none")
                put("bionicReadingMode", false)
            })
        }
        val query = params.joinToString("&") { (key, value) -> "$key=${Uri.encode(value.toString())}" }
        return "$ORIGIN${READER_PATH}index.html?$query"
    }
}

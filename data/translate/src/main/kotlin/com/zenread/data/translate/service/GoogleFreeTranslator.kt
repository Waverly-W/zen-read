package com.zenread.data.translate.service

import com.zenread.data.translate.Credentials
import com.zenread.data.translate.Language
import com.zenread.data.translate.LanguageCodes
import com.zenread.data.translate.R
import com.zenread.data.translate.Translation
import com.zenread.data.translate.TranslatorDescriptor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Google's key-free web endpoint (`client=gtx`). Port of upstream `microsoft_free.dart`, which
 * despite its name has called this Google endpoint since ReadAny. Microsoft's own key-free
 * route (Edge's `edge.microsoft.com/translate/auth` token) returns 404 since late 2026 and is
 * not offered.
 *
 * Unofficial: Google may throttle or block it at any time (HTTP 429 / connection reset).
 */
@Singleton
class GoogleFreeTranslator(
    private val client: OkHttpClient,
    private val json: Json,
    private val endpoint: HttpUrl,
) : BatchTranslator() {
    @Inject constructor(client: OkHttpClient, json: Json) : this(client, json, ENDPOINT.toHttpUrl())

    override val descriptor = TranslatorDescriptor(
        id = ID,
        label = R.string.translate_service_google_free,
        order = 0,
        detectsLanguage = true,
        maxBatchItems = 1,
        maxBatchChars = 4_500,
        minRequestIntervalMillis = 150,
    )

    override suspend fun translateBatch(
        texts: List<String>,
        from: Language?,
        to: Language,
        credentials: Credentials,
    ): List<Translation> = texts.map { text ->
        val url = endpoint.newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", from?.let(LanguageCodes::google) ?: "auto")
            .addQueryParameter("tl", LanguageCodes.google(to))
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        parse(client.fetchJson(json, ID, Request.Builder().url(url).get().build()))
    }

    companion object {
        const val ID = "google_free"
        private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

        /** `[[["译文","source",…],…], null, "en", …]`: concatenate the segments of the first array. */
        internal fun parse(body: JsonElement): Translation {
            val segments = (body as? JsonArray)?.firstOrNull() as? JsonArray ?: throw badResponse(ID)
            val text = buildString {
                segments.forEach { segment ->
                    val part = (segment as? JsonArray)?.firstOrNull() as? JsonPrimitive
                    if (part != null && part.isString) append(part.content)
                }
            }
            if (text.isBlank()) throw badResponse(ID)
            val detected = (body.getOrNull(2) as? JsonPrimitive)?.takeIf { it.isString }?.content
            return Translation(text, Language.fromCode(detected))
        }
    }
}

package com.zenread.data.translate.service

import com.zenread.data.translate.CredentialField
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Cloud Translation v2 (Basic) with the user's API key. Port of upstream
 * `google_api.dart`. The key goes in the `X-Goog-Api-Key` header rather than the URL so it
 * never shows up in URL-based logs.
 */
@Singleton
class GoogleCloudTranslator(
    private val client: OkHttpClient,
    private val json: Json,
    private val endpoint: HttpUrl,
) : BatchTranslator() {
    @Inject constructor(client: OkHttpClient, json: Json) : this(client, json, ENDPOINT.toHttpUrl())

    override val descriptor = TranslatorDescriptor(
        id = ID,
        label = R.string.translate_service_google_cloud,
        order = 4,
        credentialFields = listOf(CredentialField(KEY, R.string.translate_field_api_key)),
        detectsLanguage = true,
        maxBatchItems = 100,
        maxBatchChars = 5_000,
        minRequestIntervalMillis = 100,
    )

    override suspend fun translateBatch(
        texts: List<String>,
        from: Language?,
        to: Language,
        credentials: Credentials,
    ): List<Translation> {
        val key = credentials.require(KEY)
        val body = buildJsonObject {
            putJsonArray("q") { texts.forEach { add(it) } }
            put("target", LanguageCodes.google(to))
            put("format", "text")
            if (from != null) put("source", LanguageCodes.google(from))
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("X-Goog-Api-Key", key)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return parse(client.fetchJson(json, ID, request), texts.size)
    }

    companion object {
        const val ID = "google_cloud"
        const val KEY = "api_key"
        private const val ENDPOINT = "https://translation.googleapis.com/language/translate/v2"

        /** `{"data": {"translations": [{"translatedText": "…", "detectedSourceLanguage": "en"}]}}` */
        internal fun parse(body: JsonElement, expected: Int): List<Translation> {
            val data = (body as? JsonObject)?.get("data") as? JsonObject
            val items = data?.get("translations") as? JsonArray ?: throw badResponse(ID)
            if (items.size != expected) throw badResponse(ID)
            return items.map { item ->
                val obj = item as? JsonObject ?: throw badResponse(ID)
                val text = (obj["translatedText"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw badResponse(ID)
                Translation(text, Language.fromCode((obj["detectedSourceLanguage"] as? JsonPrimitive)?.content))
            }
        }
    }
}

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepL API v2. Port of upstream `deepl.dart` (official branch). Free-plan keys end in `:fx`
 * and must use `api-free.deepl.com`; Pro keys use `api.deepl.com`. The DeepLX branch of
 * upstream (self-hosted proxies) is not ported.
 */
@Singleton
class DeepLTranslator(
    private val client: OkHttpClient,
    private val json: Json,
    private val freeEndpoint: HttpUrl,
    private val proEndpoint: HttpUrl,
) : BatchTranslator() {
    @Inject constructor(client: OkHttpClient, json: Json) :
        this(client, json, FREE_ENDPOINT.toHttpUrl(), PRO_ENDPOINT.toHttpUrl())

    override val descriptor = TranslatorDescriptor(
        id = ID,
        label = R.string.translate_service_deepl,
        order = 3,
        credentialFields = listOf(CredentialField(KEY, R.string.translate_field_api_key, hint = R.string.translate_field_deepl_hint)),
        detectsLanguage = true,
        maxBatchItems = 50,
        maxBatchChars = 30_000,
        minRequestIntervalMillis = 200,
    )

    override suspend fun translateBatch(
        texts: List<String>,
        from: Language?,
        to: Language,
        credentials: Credentials,
    ): List<Translation> {
        val key = credentials.require(KEY)
        val body = buildJsonObject {
            putJsonArray("text") { texts.forEach { add(it) } }
            put("target_lang", LanguageCodes.deepLTarget(to))
            if (from != null) put("source_lang", LanguageCodes.deepLSource(from))
        }
        val request = Request.Builder()
            .url(endpointFor(key))
            .header("Authorization", "DeepL-Auth-Key $key")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return parse(client.fetchJson(json, ID, request), texts.size)
    }

    internal fun endpointFor(key: String): HttpUrl = if (key.endsWith(":fx")) freeEndpoint else proEndpoint

    companion object {
        const val ID = "deepl"
        const val KEY = "api_key"
        private const val FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate"
        private const val PRO_ENDPOINT = "https://api.deepl.com/v2/translate"

        /** `{"translations": [{"detected_source_language": "EN", "text": "…"}, …]}` */
        internal fun parse(body: JsonElement, expected: Int): List<Translation> {
            val items = (body as? JsonObject)?.get("translations") as? JsonArray ?: throw badResponse(ID)
            if (items.size != expected) throw badResponse(ID)
            return items.map { item ->
                val obj = item as? JsonObject ?: throw badResponse(ID)
                val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw badResponse(ID)
                Translation(text, Language.fromCode((obj["detected_source_language"] as? JsonPrimitive)?.content))
            }
        }
    }
}

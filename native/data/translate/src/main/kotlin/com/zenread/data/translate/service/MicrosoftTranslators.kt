package com.zenread.data.translate.service

import com.zenread.data.translate.CredentialField
import com.zenread.data.translate.Credentials
import com.zenread.data.translate.Language
import com.zenread.data.translate.LanguageCodes
import com.zenread.data.translate.R
import com.zenread.data.translate.TranslateException
import com.zenread.data.translate.Translation
import com.zenread.data.translate.TranslatorDescriptor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Request and response shape of the Translator v3 API. */
internal object MicrosoftApi {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun url(base: HttpUrl, from: Language?, to: Language): HttpUrl = base.newBuilder()
        .addQueryParameter("api-version", "3.0")
        .apply { if (from != null) addQueryParameter("from", LanguageCodes.microsoft(from)) }
        .addQueryParameter("to", LanguageCodes.microsoft(to))
        .build()

    /** `[{"Text": "…"}, …]` */
    fun body(texts: List<String>) = buildJsonArray {
        texts.forEach { text -> addJsonObject { put("Text", text) } }
    }.toString().toRequestBody(JSON)

    /** `[{"detectedLanguage": {"language": "en"}, "translations": [{"text": "…"}]}, …]` */
    fun parse(service: String, body: JsonElement, expected: Int): List<Translation> {
        val items = body as? JsonArray ?: throw badResponse(service)
        if (items.size != expected) throw badResponse(service)
        return items.map { item ->
            val obj = item as? JsonObject ?: throw badResponse(service)
            val first = (obj["translations"] as? JsonArray)?.firstOrNull() as? JsonObject
            val text = (first?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw badResponse(service)
            val detected = ((obj["detectedLanguage"] as? JsonObject)?.get("language") as? JsonPrimitive)?.content
            Translation(text, Language.fromCode(detected))
        }
    }

    const val MAX_ITEMS = 50
    const val MAX_CHARS = 10_000
}

/**
 * Microsoft Translator with the user's Azure key. Port of upstream `official_api.dart` /
 * `translation_api_client.dart` (Microsoft branch).
 */
@Singleton
class MicrosoftTranslator(
    private val client: OkHttpClient,
    private val json: Json,
    private val endpoint: HttpUrl,
) : BatchTranslator() {
    @Inject constructor(client: OkHttpClient, json: Json) : this(client, json, ENDPOINT.toHttpUrl())

    override val descriptor = TranslatorDescriptor(
        id = ID,
        label = R.string.translate_service_microsoft,
        order = 2,
        credentialFields = listOf(
            CredentialField(KEY, R.string.translate_field_api_key),
            CredentialField(REGION, R.string.translate_field_region, required = false, secret = false, hint = R.string.translate_field_region_hint),
        ),
        detectsLanguage = true,
        maxBatchItems = MicrosoftApi.MAX_ITEMS,
        maxBatchChars = MicrosoftApi.MAX_CHARS,
        minRequestIntervalMillis = 100,
    )

    override suspend fun translateBatch(
        texts: List<String>,
        from: Language?,
        to: Language,
        credentials: Credentials,
    ): List<Translation> {
        val key = credentials.require(KEY)
        val region = credentials[REGION]
        val request = Request.Builder()
            .url(MicrosoftApi.url(endpoint, from, to))
            .header("Ocp-Apim-Subscription-Key", key)
            .apply {
                if (region != null && !region.equals("global", ignoreCase = true)) {
                    header("Ocp-Apim-Subscription-Region", region)
                }
            }
            .post(MicrosoftApi.body(texts))
            .build()
        return MicrosoftApi.parse(ID, client.fetchJson(json, ID, request), texts.size)
    }

    companion object {
        const val ID = "microsoft"
        const val KEY = "api_key"
        const val REGION = "region"
        private const val ENDPOINT = "https://api.cognitive.microsofttranslator.com/translate"
    }
}

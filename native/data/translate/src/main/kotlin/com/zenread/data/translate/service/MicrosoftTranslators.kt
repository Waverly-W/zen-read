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

/** Request and response shape shared by the official and the Edge endpoints (Translator v3). */
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

/**
 * Microsoft Translator through the token Microsoft Edge uses for its built-in page
 * translation: `GET edge.microsoft.com/translate/auth` returns a short-lived JWT that the
 * Translator v3 API accepts as a bearer token. No key needed.
 *
 * Unofficial: Microsoft may change or restrict the token endpoint.
 */
@Singleton
class MicrosoftEdgeTranslator(
    private val client: OkHttpClient,
    private val json: Json,
    private val authUrl: HttpUrl,
    private val endpoint: HttpUrl,
    private val now: () -> Long = System::currentTimeMillis,
) : BatchTranslator() {
    @Inject constructor(client: OkHttpClient, json: Json) :
        this(client, json, AUTH_URL.toHttpUrl(), ENDPOINT.toHttpUrl())

    override val descriptor = TranslatorDescriptor(
        id = ID,
        label = R.string.translate_service_microsoft_free,
        order = 1,
        detectsLanguage = true,
        maxBatchItems = MicrosoftApi.MAX_ITEMS,
        maxBatchChars = MicrosoftApi.MAX_CHARS,
        minRequestIntervalMillis = 200,
    )

    private val tokenLock = Mutex()
    private var token: String? = null
    private var tokenExpiresAt = 0L

    override suspend fun translateBatch(
        texts: List<String>,
        from: Language?,
        to: Language,
        credentials: Credentials,
    ): List<Translation> = try {
        request(texts, from, to, token(refresh = false))
    } catch (e: TranslateException) {
        // The token may have been revoked before its nominal expiry: refresh once.
        if (e.reason != TranslateException.Reason.UNAUTHORIZED) throw e
        request(texts, from, to, token(refresh = true))
    }

    private suspend fun request(texts: List<String>, from: Language?, to: Language, bearer: String): List<Translation> {
        val request = Request.Builder()
            .url(MicrosoftApi.url(endpoint, from, to))
            .header("Authorization", "Bearer $bearer")
            .post(MicrosoftApi.body(texts))
            .build()
        return MicrosoftApi.parse(ID, client.fetchJson(json, ID, request), texts.size)
    }

    private suspend fun token(refresh: Boolean): String = tokenLock.withLock {
        val cached = token
        if (!refresh && cached != null && now() < tokenExpiresAt) return cached
        val fresh = client.fetchText(ID, Request.Builder().url(authUrl).get().build()).trim()
        // A JWT has three dot-separated parts; anything else is an error page.
        if (fresh.count { it == '.' } != 2 || fresh.any { it.isWhitespace() }) throw badResponse(ID)
        token = fresh
        tokenExpiresAt = now() + TOKEN_LIFETIME_MILLIS
        fresh
    }

    companion object {
        const val ID = "microsoft_free"
        private const val AUTH_URL = "https://edge.microsoft.com/translate/auth"
        private const val ENDPOINT = "https://api-edge.cognitive.microsofttranslator.com/translate"

        /** Tokens last 10 minutes; renew a little early. */
        private const val TOKEN_LIFETIME_MILLIS = 8 * 60 * 1000L
    }
}

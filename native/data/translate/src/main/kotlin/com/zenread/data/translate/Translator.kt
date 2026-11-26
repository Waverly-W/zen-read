package com.zenread.data.translate

import androidx.annotation.StringRes
import java.io.IOException

/**
 * One translation backend. Port of upstream `TranslateServiceProvider`
 * (`lib/service/translate/index.dart`), reshaped around batches: page translation sends
 * several paragraphs per request when the service allows it.
 *
 * Implementations are contributed to [TranslatorRegistry] with Hilt `@Binds @IntoSet`, so a
 * module outside `data:translate` (e.g. an LLM-backed translator in `data:ai`) can add one
 * without changing this API. See [TranslatorRegistry] for the AI TODO.
 */
interface Translator {
    val descriptor: TranslatorDescriptor

    /**
     * Translates every text in [texts] (same size and order in the result).
     *
     * @param from source language, or null to let the service detect it.
     * @param credentials values for [TranslatorDescriptor.credentialFields], read from `SecretStore`.
     * @throws TranslateException on any failure; messages never contain keys or request bodies.
     */
    suspend fun translate(
        texts: List<String>,
        from: Language?,
        to: Language,
        credentials: Credentials,
    ): List<Translation>
}

data class Translation(
    val text: String,
    /** Source language the service detected, when it reports one. */
    val detected: Language? = null,
)

data class TranslatorDescriptor(
    /** Persisted id (settings and cache keys). Never rename. */
    val id: String,
    @param:StringRes val label: Int,
    /** Lower sorts first in pickers. */
    val order: Int,
    val credentialFields: List<CredentialField> = emptyList(),
    /** Whether results carry [Translation.detected]. */
    val detectsLanguage: Boolean = false,
    /** Most texts one request may carry. */
    val maxBatchItems: Int = 1,
    /** Most characters one request may carry; longer single texts are split by the translator. */
    val maxBatchChars: Int = 4_500,
    /** Minimum gap between request starts, to stay under the service's rate limit. */
    val minRequestIntervalMillis: Long = 0,
) {
    val requiresCredentials: Boolean get() = credentialFields.any { it.required }
}

data class CredentialField(
    /** Field name; stored in `SecretStore` as `translate.<translatorId>.<name>`. */
    val name: String,
    @param:StringRes val label: Int,
    val required: Boolean = true,
    /** Masked input. False for non-secret settings such as a region. */
    val secret: Boolean = true,
    @param:StringRes val hint: Int? = null,
)

/** Credential values for one translator. `toString` never prints them. */
class Credentials(private val values: Map<String, String>) {
    operator fun get(name: String): String? = values[name]?.trim()?.takeIf { it.isNotEmpty() }

    fun require(name: String): String =
        get(name) ?: throw TranslateException(TranslateException.Reason.MISSING_CREDENTIALS, "Missing $name")

    fun isComplete(fields: List<CredentialField>): Boolean = fields.filter { it.required }.all { get(it.name) != null }

    override fun toString(): String = "Credentials(${values.keys})"

    companion object {
        val NONE = Credentials(emptyMap())
    }
}

class TranslateException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason {
        /** No API key configured. */
        MISSING_CREDENTIALS,

        /** The service rejected the key (HTTP 401/403). */
        UNAUTHORIZED,

        /** Rate limit or quota exhausted (HTTP 429, DeepL 456). */
        QUOTA,

        /** Offline, blocked host, timeout or 5xx: retrying immediately will not help. */
        UNAVAILABLE,

        /** The service rejected the request (HTTP 400), usually an unsupported language. */
        REJECTED,

        /** The service answered with something we could not parse. */
        BAD_RESPONSE,
    }

    companion object {
        fun forStatus(service: String, code: Int): TranslateException = TranslateException(
            reason = when (code) {
                401, 403 -> Reason.UNAUTHORIZED
                429, 456 -> Reason.QUOTA
                400, 413, 414 -> Reason.REJECTED
                else -> Reason.UNAVAILABLE
            },
            message = "$service HTTP $code",
        )
    }
}

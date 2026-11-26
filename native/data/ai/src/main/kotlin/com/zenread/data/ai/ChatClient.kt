package com.zenread.data.ai

import com.zenread.data.ai.chat.ChatEvent
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.chat.ChatMessage
import com.zenread.data.ai.chat.ChatOptions
import com.zenread.data.ai.chat.FinishReason
import com.zenread.data.ai.chat.ModelRef
import com.zenread.data.ai.provider.ChatProvider
import com.zenread.data.ai.provider.Endpoint
import com.zenread.data.ai.provider.ModelList
import com.zenread.data.ai.provider.ProviderConfig
import com.zenread.data.ai.provider.ProviderKind
import com.zenread.data.ai.provider.ProviderRequest
import com.zenread.data.ai.settings.AiSettingsRepository
import com.zenread.data.ai.provider.AnthropicProvider
import com.zenread.data.ai.provider.GeminiProvider
import com.zenread.data.ai.provider.OpenAiCompatibleProvider
import com.zenread.data.ai.provider.ProviderHttp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * The app's single entry point to LLMs. Stable API for features (AI panel, AI translation…):
 *
 * ```
 * chatClient.stream(listOf(ChatMessage.system("…"), ChatMessage.user("…")))
 *     .collect { event -> when (event) { is ChatEvent.Delta -> append(event.text); … } }
 * val text = chatClient.complete(messages, ChatOptions(temperature = 0.2))
 * ```
 *
 * Calls go to the model picked in Settings › AI unless [ChatOptions.model] names another.
 * Failures are thrown as [ChatException] (check [ChatException.kind]); a successful stream
 * ends with one [ChatEvent.Done]. Flows are cold, one request per collection; cancel the
 * collecting coroutine to stop generation.
 */
interface ChatClient {
    fun stream(messages: List<ChatMessage>, options: ChatOptions = ChatOptions()): Flow<ChatEvent>

    /** Whole answer as text (reasoning dropped). */
    suspend fun complete(messages: List<ChatMessage>, options: ChatOptions = ChatOptions()): String

    /** Emits whether a default chat model is configured. */
    val isConfigured: Flow<Boolean>

    /** Vectors for [texts] from the default embedding model (or [model]). */
    suspend fun embed(texts: List<String>, model: ModelRef? = null): List<FloatArray>
}

/** Settings-page operations on a possibly unsaved provider. */
interface ProviderAdmin {
    /** Models the provider reports, using the first key in [keyField]. */
    suspend fun listModels(config: ProviderConfig, keyField: String?): ModelList

    /** Sends a tiny real request to [model] with every key in [keyField]; throws [ChatException]. */
    suspend fun test(config: ProviderConfig, keyField: String?, model: String)
}

/** One shared instance of each wire protocol. */
@Singleton
class ProviderRegistry internal constructor(private val http: ProviderHttp) {
    @Inject
    constructor(client: OkHttpClient, json: Json) : this(
        // Reasoning models can think for minutes before the first token.
        ProviderHttp(client.newBuilder().readTimeout(5, TimeUnit.MINUTES).build(), json),
    )

    private val openAi by lazy { OpenAiCompatibleProvider(http) }
    private val anthropic by lazy { AnthropicProvider(http) }
    private val gemini by lazy { GeminiProvider(http) }

    operator fun get(kind: ProviderKind): ChatProvider = when (kind) {
        ProviderKind.OPENAI_COMPATIBLE -> openAi
        ProviderKind.ANTHROPIC -> anthropic
        ProviderKind.GEMINI -> gemini
    }
}

/** Splits a key field into keys and hands them out round-robin per provider. */
@Singleton
class ApiKeyRotator @Inject constructor() {
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    /** Keys in the order to try them for this call: the next key first, then the rest. */
    fun order(providerId: String, field: String?): List<String> {
        val keys = parseKeys(field)
        if (keys.size <= 1) return keys.ifEmpty { listOf("") }
        val start = Math.floorMod(cursors.getOrPut(providerId) { AtomicInteger() }.getAndIncrement(), keys.size)
        return keys.drop(start) + keys.take(start)
    }

    companion object {
        fun parseKeys(field: String?): List<String> =
            field.orEmpty().split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

@Singleton
internal class DefaultChatClient @Inject constructor(
    private val settings: AiSettingsRepository,
    private val providers: ProviderRegistry,
    private val rotator: ApiKeyRotator,
) : ChatClient, ProviderAdmin {

    override val isConfigured: Flow<Boolean> =
        settings.settings.map { it.resolvedChatModel != null }.distinctUntilChanged()

    override fun stream(messages: List<ChatMessage>, options: ChatOptions): Flow<ChatEvent> = flow {
        val current = settings.current()
        val ref = options.model ?: current.resolvedChatModel
            ?: throw ChatException(ChatException.Kind.NOT_CONFIGURED, "No chat model is configured")
        val config = current.provider(ref.providerId)?.takeIf { it.enabled }
            ?: throw ChatException(ChatException.Kind.NOT_CONFIGURED, "The selected provider no longer exists")
        val params = config.paramsFor(ref.model)
        val request = ProviderRequest(
            model = ref.model,
            messages = messages,
            temperature = options.temperature ?: params.temperature,
            maxTokens = options.maxTokens ?: params.maxTokens,
        )
        withKeyRotation(config) { endpoint ->
            var emitted = false
            try {
                provider(config).stream(endpoint, request).collect { event ->
                    emitted = true
                    emit(event)
                }
                RotationResult.Ok
            } catch (e: ChatException) {
                // Only retry while nothing has reached the caller.
                if (!emitted && e.isKeyProblem()) RotationResult.Retry(e) else throw e
            }
        }
    }

    override suspend fun complete(messages: List<ChatMessage>, options: ChatOptions): String {
        val text = StringBuilder()
        var finish: FinishReason? = null
        stream(messages, options).collect { event ->
            when (event) {
                is ChatEvent.Delta -> text.append(event.text)
                is ChatEvent.Done -> finish = event.finishReason
                is ChatEvent.Reasoning -> Unit
            }
        }
        if (finish == FinishReason.FILTERED && text.isEmpty()) {
            throw ChatException(ChatException.Kind.BAD_REQUEST, "The provider refused to answer")
        }
        return text.toString()
    }

    override suspend fun embed(texts: List<String>, model: ModelRef?): List<FloatArray> {
        val current = settings.current()
        val ref = model ?: current.resolvedEmbeddingModel
            ?: throw ChatException(ChatException.Kind.NOT_CONFIGURED, "No embedding model is configured")
        val config = current.provider(ref.providerId)?.takeIf { it.enabled }
            ?: throw ChatException(ChatException.Kind.NOT_CONFIGURED, "The embedding provider no longer exists")
        var result: List<FloatArray> = emptyList()
        withKeyRotation(config) { endpoint ->
            try {
                result = provider(config).embed(endpoint, ref.model, texts)
                RotationResult.Ok
            } catch (e: ChatException) {
                if (e.isKeyProblem()) RotationResult.Retry(e) else throw e
            }
        }
        return result
    }

    override suspend fun listModels(config: ProviderConfig, keyField: String?): ModelList {
        val key = ApiKeyRotator.parseKeys(keyField).firstOrNull().orEmpty()
        return provider(config).listModels(Endpoint(config.baseUrl, key))
    }

    override suspend fun test(config: ProviderConfig, keyField: String?, model: String) {
        val keys = ApiKeyRotator.parseKeys(keyField).ifEmpty { listOf("") }
        val request = ProviderRequest(model, listOf(ChatMessage.user("Reply with the single word: ok")), maxTokens = 16)
        keys.forEach { key ->
            provider(config).stream(Endpoint(config.baseUrl, key), request).collect { }
        }
    }

    private fun provider(config: ProviderConfig): ChatProvider = providers[config.kind]

    private sealed interface RotationResult {
        data object Ok : RotationResult
        data class Retry(val error: ChatException) : RotationResult
    }

    private suspend fun withKeyRotation(config: ProviderConfig, attempt: suspend (Endpoint) -> RotationResult) {
        val keys = rotator.order(config.id, settings.apiKeyField(config.id))
        var last: ChatException? = null
        for (key in keys) {
            when (val result = attempt(Endpoint(config.baseUrl, key))) {
                RotationResult.Ok -> return
                is RotationResult.Retry -> last = result.error
            }
        }
        throw last ?: ChatException(ChatException.Kind.AUTH, "No API key")
    }

    private fun ChatException.isKeyProblem() = kind == ChatException.Kind.AUTH || kind == ChatException.Kind.RATE_LIMIT
}

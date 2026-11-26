package com.zenread.data.ai.provider

import com.zenread.data.ai.chat.ChatEvent
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.chat.ChatMessage
import com.zenread.data.ai.chat.ChatRole
import com.zenread.data.ai.chat.FinishReason
import com.zenread.data.ai.chat.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic Messages API (`POST /v1/messages`, `stream: true`). [Endpoint.baseUrl] is the
 * host root, e.g. `https://api.anthropic.com`; a trailing `/v1` is tolerated.
 */
internal class AnthropicProvider(private val http: ProviderHttp) : ChatProvider {
    override val kind = ProviderKind.ANTHROPIC

    override fun stream(endpoint: Endpoint, request: ProviderRequest): Flow<ChatEvent> {
        val url = http.url(normalizeBase(endpoint.baseUrl), "v1/messages")
        return http.streamSse(http.post(url, headers(endpoint), buildBody(request)), Parser(http))
    }

    override suspend fun listModels(endpoint: Endpoint): ModelList {
        val url = http.url(normalizeBase(endpoint.baseUrl), "v1/models", query = mapOf("limit" to "1000"))
        val root = http.executeJson(http.get(url, headers(endpoint)))
        val ids = (root.obj()?.get("data") as? JsonArray).orEmpty()
            .mapNotNull { it.obj()?.get("id")?.primitiveContent() }
        return ModelList(chat = ids, embedding = emptyList())
    }

    override suspend fun embed(endpoint: Endpoint, model: String, inputs: List<String>): List<FloatArray> =
        throw ChatException(ChatException.Kind.UNSUPPORTED, "Anthropic does not offer an embeddings API")

    private fun headers(endpoint: Endpoint) = mapOf(
        "x-api-key" to endpoint.apiKey,
        "anthropic-version" to API_VERSION,
    )

    companion object {
        const val API_VERSION = "2023-06-01"

        /** The Messages API requires max_tokens. */
        const val DEFAULT_MAX_TOKENS = 4096

        fun normalizeBase(base: String): String = base.trim().trimEnd('/')
            .removeSuffix("/messages")
            .removeSuffix("/v1")

        /**
         * System messages are hoisted into `system`; the rest must alternate user/assistant
         * starting with user, so consecutive same-role turns are merged.
         */
        fun buildBody(request: ProviderRequest): JsonObject {
            val system = request.messages.filter { it.role == ChatRole.SYSTEM }.joinToString("\n\n") { it.content }
            val turns = mergeTurns(request.messages.filter { it.role != ChatRole.SYSTEM })
            return buildJsonObject {
                put("model", request.model)
                put("max_tokens", request.maxTokens ?: DEFAULT_MAX_TOKENS)
                if (system.isNotBlank()) put("system", system)
                put(
                    "messages",
                    buildJsonArray {
                        turns.forEach { turn ->
                            add(
                                buildJsonObject {
                                    put("role", if (turn.role == ChatRole.ASSISTANT) "assistant" else "user")
                                    put("content", turn.content)
                                },
                            )
                        }
                    },
                )
                put("stream", true)
                request.temperature?.let { put("temperature", it.coerceIn(0.0, 1.0)) }
            }
        }

        internal fun mergeTurns(messages: List<ChatMessage>): List<ChatMessage> {
            val merged = mutableListOf<ChatMessage>()
            for (message in messages) {
                val last = merged.lastOrNull()
                if (last != null && last.role == message.role) {
                    merged[merged.lastIndex] = last.copy(content = last.content + "\n\n" + message.content)
                } else {
                    merged += message
                }
            }
            if (merged.firstOrNull()?.role == ChatRole.ASSISTANT) merged.add(0, ChatMessage.user("…"))
            return merged
        }
    }

    /** Typed SSE events: message_start, content_block_delta, message_delta, message_stop, error. */
    internal class Parser(private val http: ProviderHttp) : StreamParser {
        private var inputTokens: Int? = null
        private var outputTokens: Int? = null
        private var finish: FinishReason? = null
        override var finished = false
            private set

        override fun onEvent(event: SseEvent): List<ChatEvent> {
            val data = http.json.parseToJsonElement(event.data).obj() ?: return emptyList()
            return when (event.event ?: data["type"]?.primitiveContent()) {
                "message_start" -> {
                    data["message"].obj()?.get("usage").obj()?.let { usage ->
                        inputTokens = usage["input_tokens"]?.primitiveContent()?.toIntOrNull()
                    }
                    emptyList()
                }
                "content_block_delta" -> {
                    val delta = data["delta"].obj() ?: return emptyList()
                    when (delta["type"]?.primitiveContent()) {
                        "text_delta" -> delta["text"]?.primitiveContent()?.takeIf { it.isNotEmpty() }
                            ?.let { listOf(ChatEvent.Delta(it)) }.orEmpty()
                        "thinking_delta" -> delta["thinking"]?.primitiveContent()?.takeIf { it.isNotEmpty() }
                            ?.let { listOf(ChatEvent.Reasoning(it)) }.orEmpty()
                        else -> emptyList()
                    }
                }
                "message_delta" -> {
                    data["delta"].obj()?.get("stop_reason")?.primitiveContent()?.let { finish = mapStop(it) }
                    data["usage"].obj()?.get("output_tokens")?.primitiveContent()?.toIntOrNull()?.let { outputTokens = it }
                    emptyList()
                }
                "message_stop" -> {
                    finished = true
                    listOf(ChatEvent.Done(finish ?: FinishReason.STOP, Usage(inputTokens, outputTokens)))
                }
                "error" -> {
                    val error = data["error"].obj()
                    val message = error?.get("message")?.primitiveContent() ?: "Provider error"
                    val kind = when (error?.get("type")?.primitiveContent()) {
                        "authentication_error", "permission_error" -> ChatException.Kind.AUTH
                        "rate_limit_error" -> ChatException.Kind.RATE_LIMIT
                        "invalid_request_error", "not_found_error", "request_too_large" -> ChatException.Kind.BAD_REQUEST
                        else -> ChatException.Kind.SERVER
                    }
                    throw ChatException(kind, message)
                }
                else -> emptyList() // ping, content_block_start/stop
            }
        }

        override fun onEnd(): List<ChatEvent> {
            if (finished) return emptyList()
            throw ChatException(ChatException.Kind.NETWORK, "The connection closed before the answer finished")
        }

        private fun mapStop(reason: String) = when (reason) {
            "end_turn", "stop_sequence" -> FinishReason.STOP
            "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH
            "refusal" -> FinishReason.FILTERED
            else -> FinishReason.OTHER
        }
    }
}

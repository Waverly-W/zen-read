package com.zenread.data.ai.provider

import com.zenread.data.ai.chat.ChatEvent
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.chat.ChatRole
import com.zenread.data.ai.chat.FinishReason
import com.zenread.data.ai.chat.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI Chat Completions protocol, also spoken by DeepSeek, OpenRouter, SiliconFlow,
 * Ollama (`/v1`), vLLM, LM Studio and most proxies. [Endpoint.baseUrl] is the API root
 * that `/chat/completions` hangs off, e.g. `https://api.openai.com/v1`.
 */
internal class OpenAiCompatibleProvider(private val http: ProviderHttp) : ChatProvider {
    override val kind = ProviderKind.OPENAI_COMPATIBLE

    override fun stream(endpoint: Endpoint, request: ProviderRequest): Flow<ChatEvent> {
        val url = http.url(normalizeBase(endpoint.baseUrl), "chat/completions")
        return http.streamSse(http.post(url, headers(endpoint), buildChatBody(url.host, request)), Parser(http))
    }

    override suspend fun listModels(endpoint: Endpoint): ModelList {
        val root = http.executeJson(http.get(http.url(normalizeBase(endpoint.baseUrl), "models"), headers(endpoint)))
        val ids = (root.obj()?.get("data") as? JsonArray).orEmpty()
            .mapNotNull { it.obj()?.get("id")?.primitiveContent() }
            .distinct()
            .sorted()
        return ModelList(chat = ids.filterNot(::looksLikeEmbeddingModel), embedding = ids.filter(::looksLikeEmbeddingModel))
    }

    override suspend fun embed(endpoint: Endpoint, model: String, inputs: List<String>): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        val body = buildJsonObject {
            put("model", model)
            put("input", buildJsonArray { inputs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("encoding_format", "float")
        }
        val root = http.executeJson(http.post(http.url(normalizeBase(endpoint.baseUrl), "embeddings"), headers(endpoint), body))
        return parseEmbeddings(root.obj(), inputs.size)
    }

    private fun headers(endpoint: Endpoint): Map<String, String> = buildMap {
        if (endpoint.apiKey.isNotBlank()) put("Authorization", "Bearer ${endpoint.apiKey}")
    }

    companion object {
        /** Accepts a pasted full endpoint (`…/v1/chat/completions`) as well as the API root. */
        fun normalizeBase(base: String): String = base.trim().trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/embeddings")
            .removeSuffix("/models")

        fun buildChatBody(host: String, request: ProviderRequest): JsonObject = buildJsonObject {
            put("model", request.model)
            put(
                "messages",
                buildJsonArray {
                    request.messages.forEach { message ->
                        add(
                            buildJsonObject {
                                put(
                                    "role",
                                    when (message.role) {
                                        ChatRole.SYSTEM -> "system"
                                        ChatRole.USER -> "user"
                                        ChatRole.ASSISTANT -> "assistant"
                                    },
                                )
                                put("content", message.content)
                            },
                        )
                    }
                },
            )
            put("stream", true)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let {
                // api.openai.com deprecated max_tokens (rejected by reasoning models); most
                // compatible servers only understand max_tokens.
                put(if (host == "api.openai.com") "max_completion_tokens" else "max_tokens", it)
            }
        }

        fun parseEmbeddings(root: JsonObject?, expected: Int): List<FloatArray> {
            val data = (root?.get("data") as? JsonArray)
                ?: throw ChatException(ChatException.Kind.PROTOCOL, "Embedding response has no data")
            val vectors = data.mapIndexed { position, item ->
                val obj = item.obj() ?: throw ChatException(ChatException.Kind.PROTOCOL, "Bad embedding item")
                val index = obj["index"]?.jsonPrimitive?.intOrNull ?: position
                val values = obj["embedding"]?.jsonArray
                    ?: throw ChatException(ChatException.Kind.PROTOCOL, "Embedding item has no vector")
                index to FloatArray(values.size) { values[it].jsonPrimitive.content.toFloat() }
            }.sortedBy { it.first }.map { it.second }
            if (vectors.size != expected) {
                throw ChatException(ChatException.Kind.PROTOCOL, "Expected $expected embeddings, got ${vectors.size}")
            }
            return vectors
        }
    }

    /** Chat Completions chunks: `choices[0].delta.content`, terminated by `data: [DONE]`. */
    internal class Parser(private val http: ProviderHttp) : StreamParser {
        private var finish: FinishReason? = null
        private var usage: Usage? = null
        private var sawContent = false
        override var finished = false
            private set

        override fun onEvent(event: SseEvent): List<ChatEvent> {
            val data = event.data.trim()
            if (data.isEmpty()) return emptyList()
            if (data == "[DONE]") {
                finished = true
                return listOf(done())
            }
            val chunk = http.json.parseToJsonElement(data).obj() ?: return emptyList()
            chunk["error"]?.let { error ->
                val message = error.obj()?.get("message")?.primitiveContent() ?: error.primitiveContent() ?: "Provider error"
                throw ChatException(ChatException.Kind.SERVER, message)
            }
            chunk["usage"].obj()?.let { u ->
                usage = Usage(
                    inputTokens = u["prompt_tokens"]?.primitiveContent()?.toIntOrNull(),
                    outputTokens = u["completion_tokens"]?.primitiveContent()?.toIntOrNull(),
                )
            }
            val out = mutableListOf<ChatEvent>()
            (chunk["choices"] as? JsonArray)?.firstOrNull()?.obj()?.let { choice ->
                choice["delta"].obj()?.let { delta ->
                    val reasoning = delta["reasoning_content"]?.primitiveContent() ?: delta["reasoning"]?.primitiveContent()
                    if (!reasoning.isNullOrEmpty()) out += ChatEvent.Reasoning(reasoning)
                    val content = delta["content"]?.primitiveContent()
                    if (!content.isNullOrEmpty()) {
                        sawContent = true
                        out += ChatEvent.Delta(content)
                    }
                }
                choice["finish_reason"]?.primitiveContent()?.let { finish = mapFinish(it) }
            }
            return out
        }

        override fun onEnd(): List<ChatEvent> {
            if (finished) return emptyList()
            // Some servers close without [DONE]; accept it when the answer clearly ended.
            if (finish != null || sawContent) return listOf(done())
            throw ChatException(ChatException.Kind.PROTOCOL, "The stream ended without an answer")
        }

        private fun done() = ChatEvent.Done(finish ?: FinishReason.STOP, usage)

        private fun mapFinish(reason: String) = when (reason) {
            "stop" -> FinishReason.STOP
            "length" -> FinishReason.LENGTH
            "content_filter" -> FinishReason.FILTERED
            else -> FinishReason.OTHER
        }
    }
}

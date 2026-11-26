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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Google Gemini API (`v1beta`, `streamGenerateContent?alt=sse`). [Endpoint.baseUrl] is
 * `https://generativelanguage.googleapis.com`; a trailing `/v1beta` is tolerated.
 */
internal class GeminiProvider(private val http: ProviderHttp) : ChatProvider {
    override val kind = ProviderKind.GEMINI

    override fun stream(endpoint: Endpoint, request: ProviderRequest): Flow<ChatEvent> {
        val url = http.url(
            normalizeBase(endpoint.baseUrl),
            "v1beta/models/${modelId(request.model)}:streamGenerateContent",
            query = mapOf("alt" to "sse"),
        )
        return http.streamSse(http.post(url, headers(endpoint), buildBody(request)), Parser(http))
    }

    override suspend fun listModels(endpoint: Endpoint): ModelList {
        val chat = mutableListOf<String>()
        val embedding = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val query = buildMap {
                put("pageSize", "1000")
                pageToken?.let { put("pageToken", it) }
            }
            val root = http.executeJson(http.get(http.url(normalizeBase(endpoint.baseUrl), "v1beta/models", query = query), headers(endpoint))).obj()
            (root?.get("models") as? JsonArray).orEmpty().forEach { item ->
                val model = item.obj() ?: return@forEach
                val id = model["name"]?.primitiveContent()?.let(::modelId) ?: return@forEach
                val methods = (model["supportedGenerationMethods"] as? JsonArray).orEmpty().mapNotNull { it.primitiveContent() }
                if ("generateContent" in methods) chat += id
                if ("embedContent" in methods || "batchEmbedContents" in methods) embedding += id
            }
            pageToken = root?.get("nextPageToken")?.primitiveContent()?.takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return ModelList(chat = chat, embedding = embedding)
    }

    override suspend fun embed(endpoint: Endpoint, model: String, inputs: List<String>): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        val id = modelId(model)
        val body = buildJsonObject {
            putJsonArray("requests") {
                inputs.forEach { text ->
                    add(
                        buildJsonObject {
                            put("model", "models/$id")
                            putJsonObject("content") {
                                putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                            }
                        },
                    )
                }
            }
        }
        val url = http.url(normalizeBase(endpoint.baseUrl), "v1beta/models/$id:batchEmbedContents")
        val root = http.executeJson(http.post(url, headers(endpoint), body)).obj()
        val embeddings = (root?.get("embeddings") as? JsonArray)
            ?: throw ChatException(ChatException.Kind.PROTOCOL, "Embedding response has no embeddings")
        if (embeddings.size != inputs.size) {
            throw ChatException(ChatException.Kind.PROTOCOL, "Expected ${inputs.size} embeddings, got ${embeddings.size}")
        }
        return embeddings.map { item ->
            val values = item.obj()?.get("values")?.jsonArray
                ?: throw ChatException(ChatException.Kind.PROTOCOL, "Embedding has no values")
            FloatArray(values.size) { values[it].jsonPrimitive.content.toFloat() }
        }
    }

    private fun headers(endpoint: Endpoint) = mapOf("x-goog-api-key" to endpoint.apiKey)

    companion object {
        fun normalizeBase(base: String): String = base.trim().trimEnd('/')
            .removeSuffix("/models")
            .removeSuffix("/v1beta")
            .removeSuffix("/v1")

        /** `models/gemini-x` and `gemini-x` both work in pickers. */
        fun modelId(model: String) = model.trim().removePrefix("models/")

        fun buildBody(request: ProviderRequest): JsonObject {
            val system = request.messages.filter { it.role == ChatRole.SYSTEM }.joinToString("\n\n") { it.content }
            val turns = AnthropicProvider.mergeTurns(request.messages.filter { it.role != ChatRole.SYSTEM })
            return buildJsonObject {
                putJsonArray("contents") {
                    turns.forEach { turn ->
                        add(
                            buildJsonObject {
                                put("role", if (turn.role == ChatRole.ASSISTANT) "model" else "user")
                                putJsonArray("parts") { add(buildJsonObject { put("text", turn.content) }) }
                            },
                        )
                    }
                }
                if (system.isNotBlank()) {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
                    }
                }
                if (request.temperature != null || request.maxTokens != null) {
                    putJsonObject("generationConfig") {
                        request.temperature?.let { put("temperature", it) }
                        request.maxTokens?.let { put("maxOutputTokens", it) }
                    }
                }
            }
        }
    }

    /** Each SSE `data:` is a full GenerateContentResponse; the stream ends by closing. */
    internal class Parser(private val http: ProviderHttp) : StreamParser {
        private var finish: FinishReason? = null
        private var usage: Usage? = null
        private var sawContent = false
        override val finished = false

        override fun onEvent(event: SseEvent): List<ChatEvent> {
            val chunk = http.json.parseToJsonElement(event.data).obj() ?: return emptyList()
            chunk["error"].obj()?.let { error ->
                throw ChatException(ChatException.Kind.SERVER, error["message"]?.primitiveContent() ?: "Provider error")
            }
            chunk["usageMetadata"].obj()?.let { u ->
                usage = Usage(
                    inputTokens = u["promptTokenCount"]?.primitiveContent()?.toIntOrNull(),
                    outputTokens = u["candidatesTokenCount"]?.primitiveContent()?.toIntOrNull(),
                )
            }
            if (chunk["promptFeedback"].obj()?.get("blockReason") != null) finish = FinishReason.FILTERED
            val out = mutableListOf<ChatEvent>()
            val candidate = (chunk["candidates"] as? JsonArray)?.firstOrNull()?.obj() ?: return out
            (candidate["content"].obj()?.get("parts") as? JsonArray).orEmpty().forEach { part ->
                val obj = part.obj() ?: return@forEach
                val text = obj["text"]?.primitiveContent()
                if (text.isNullOrEmpty()) return@forEach
                if (obj["thought"]?.primitiveContent() == "true") {
                    out += ChatEvent.Reasoning(text)
                } else {
                    sawContent = true
                    out += ChatEvent.Delta(text)
                }
            }
            candidate["finishReason"]?.primitiveContent()?.let { finish = mapFinish(it) }
            return out
        }

        override fun onEnd(): List<ChatEvent> {
            if (finish == null && !sawContent) {
                throw ChatException(ChatException.Kind.PROTOCOL, "The stream ended without an answer")
            }
            return listOf(ChatEvent.Done(finish ?: FinishReason.STOP, usage))
        }

        private fun mapFinish(reason: String) = when (reason) {
            "STOP" -> FinishReason.STOP
            "MAX_TOKENS" -> FinishReason.LENGTH
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> FinishReason.FILTERED
            "FINISH_REASON_UNSPECIFIED" -> FinishReason.OTHER
            else -> FinishReason.OTHER
        }
    }
}

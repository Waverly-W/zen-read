package com.zenread.data.ai.provider

import com.zenread.data.ai.chat.ChatEvent
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.chat.ChatMessage
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where and how to call a provider. [apiKey] may be blank for keyless local servers. */
data class Endpoint(val baseUrl: String, val apiKey: String)

/** A fully resolved chat call. [messages] may contain system messages anywhere. */
data class ProviderRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/** Models a provider reports. Embedding models are a best-effort split. */
data class ModelList(val chat: List<String>, val embedding: List<String>)

/** One wire protocol. Implementations are stateless and safe to share. */
interface ChatProvider {
    val kind: ProviderKind

    fun stream(endpoint: Endpoint, request: ProviderRequest): Flow<ChatEvent>

    suspend fun listModels(endpoint: Endpoint): ModelList

    /** One vector per input, in input order. */
    suspend fun embed(endpoint: Endpoint, model: String, inputs: List<String>): List<FloatArray>
}

/** Stateful translator from one stream's SSE events to [ChatEvent]s. */
internal interface StreamParser {
    /** May throw [ChatException] for in-band errors. */
    fun onEvent(event: SseEvent): List<ChatEvent>

    /** True once the provider signalled the end; reading stops. */
    val finished: Boolean

    /** Called once after the last event; returns the closing [ChatEvent.Done] or throws. */
    fun onEnd(): List<ChatEvent>
}

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** HTTP plumbing shared by the providers. Never logs requests: they carry keys and book text. */
internal class ProviderHttp(private val client: OkHttpClient, val json: Json) {

    fun url(base: String, vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val root = base.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw ChatException(ChatException.Kind.BAD_REQUEST, "Invalid base URL")
        return root.newBuilder().apply {
            segments.forEach { addPathSegments(it.trim('/')) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    fun post(url: HttpUrl, headers: Map<String, String>, body: JsonElement): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

    fun get(url: HttpUrl, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()

    /** Streams an SSE response through [parser]. Cancelling the collector cancels the call. */
    fun streamSse(request: Request, parser: StreamParser): Flow<ChatEvent> = callbackFlow {
        val call = client.newCall(request.newBuilder().header("Accept", "text/event-stream").build())
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    close(if (call.isCanceled()) null else networkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            close(httpError(it))
                            return
                        }
                        try {
                            var open = true
                            readSse(it.body.source()) { event ->
                                parser.onEvent(event).forEach { out ->
                                    if (trySendBlocking(out).isClosed) open = false
                                }
                                open && !parser.finished
                            }
                            if (open) parser.onEnd().forEach { out -> trySendBlocking(out) }
                            close()
                        } catch (e: ChatException) {
                            close(e)
                        } catch (e: IOException) {
                            close(if (call.isCanceled()) null else networkError(e))
                        } catch (e: SerializationException) {
                            close(protocolError(e))
                        } catch (e: IllegalArgumentException) {
                            close(protocolError(e))
                        }
                    }
                }
            },
        )
        awaitClose { call.cancel() }
    }

    /** Executes [request] and parses the JSON body; maps HTTP failures to [ChatException]. */
    suspend fun executeJson(request: Request): JsonElement {
        val response = client.newCall(request).await()
        return response.use {
            if (!it.isSuccessful) throw httpError(it)
            try {
                json.parseToJsonElement(it.body.string())
            } catch (e: SerializationException) {
                throw protocolError(e)
            } catch (e: IllegalArgumentException) {
                throw protocolError(e)
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(networkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            },
        )
    }

    fun httpError(response: Response): ChatException {
        val status = response.code
        // Bounded read: error pages can be large. The body is the provider's message, never our request.
        val body = runCatching { response.peekBody(8 * 1024).string() }.getOrDefault("")
        val detail = errorMessage(body) ?: response.message.ifBlank { "HTTP $status" }
        val kind = when (status) {
            401, 403 -> ChatException.Kind.AUTH
            408, 429 -> ChatException.Kind.RATE_LIMIT
            in 500..599 -> ChatException.Kind.SERVER
            else -> ChatException.Kind.BAD_REQUEST
        }
        return ChatException(kind, "HTTP $status: $detail", httpStatus = status)
    }

    /** `{"error":{"message":…}}` (OpenAI, Anthropic, Gemini) or `{"error":"…"}` / `{"message":…}`. */
    fun errorMessage(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val error = root["error"]
        return (error as? JsonObject)?.get("message")?.primitiveContent()
            ?: error?.primitiveContent()
            ?: root["message"]?.primitiveContent()
    }

    private fun networkError(e: IOException) =
        e as? ChatException ?: ChatException(ChatException.Kind.NETWORK, e.message ?: "Network error", cause = e)

    private fun protocolError(e: Exception) =
        ChatException(ChatException.Kind.PROTOCOL, "Unexpected response from the provider", cause = e)
}

internal fun JsonElement.primitiveContent(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()

internal fun JsonElement?.obj(): JsonObject? = runCatching { this?.jsonObject }.getOrNull()

/** Best-effort guess used to sort fetched model lists into chat vs embedding pickers. */
internal fun looksLikeEmbeddingModel(id: String): Boolean {
    val name = id.lowercase().substringAfterLast('/')
    return "embed" in name || name.startsWith("bge") || name.startsWith("e5-") ||
        name.startsWith("gte-") || name.startsWith("m3e") || "-e5-" in name
}

package com.zenread.data.ai.provider

import com.zenread.data.ai.chat.ChatEvent
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.chat.ChatMessage
import com.zenread.data.ai.chat.FinishReason
import com.zenread.data.ai.chat.Usage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class OpenAiCompatibleProviderTest : ProviderTestSupport() {
    private val provider by lazy { OpenAiCompatibleProvider(http) }
    private val request = ProviderRequest(
        model = "deepseek-chat",
        messages = listOf(ChatMessage.system("sys"), ChatMessage.user("hi"), ChatMessage.assistant("yo"), ChatMessage.user("q")),
        temperature = 0.3,
        maxTokens = 256,
    )

    @Test
    fun `streams deltas, reasoning and usage until DONE`() {
        enqueueSse(
            """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"Let me think"},"finish_reason":null}]}

            : OPENROUTER PROCESSING

            data: {"choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3}}

            data: [DONE]

            """.trimIndent(),
        )
        val events = collect(provider.stream(Endpoint(base("/v1"), "sk-test"), request))
        assertEquals("Hello", events.text())
        assertEquals("Let me think", events.reasoning())
        assertEquals(ChatEvent.Done(FinishReason.STOP, Usage(12, 3)), events.last())
        assertEquals(1, events.count { it is ChatEvent.Done })

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        val body = recorded.jsonBody()
        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals(256, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertNull(body["max_completion_tokens"])
        val roles = body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
    }

    @Test
    fun `accepts a stream that ends without DONE after a finish reason`() {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"length\"}]}\n\n")
        val events = collect(provider.stream(Endpoint(base(), ""), request))
        assertEquals(ChatEvent.Done(FinishReason.LENGTH, null), events.last())
        assertNull("keyless servers get no Authorization header", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `maps HTTP errors with the provider message`() {
        enqueueJson("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""", code = 401)
        val error = collectError(provider.stream(Endpoint(base("/v1"), "bad"), request))
        assertEquals(ChatException.Kind.AUTH, error.kind)
        assertEquals(401, error.httpStatus)
        assertTrue(error.message!!.contains("Incorrect API key provided"))
        assertFalse("never echo the key", error.message!!.contains("bad"))
    }

    @Test
    fun `in-band error chunks fail the stream`() {
        enqueueSse("data: {\"error\":{\"message\":\"upstream overloaded\"}}\n\n")
        val error = collectError(provider.stream(Endpoint(base(), "k"), request))
        assertEquals(ChatException.Kind.SERVER, error.kind)
        assertEquals("upstream overloaded", error.message)
    }

    @Test
    fun `rate limits are retryable kinds`() {
        enqueueJson("""{"error":{"message":"slow down"}}""", code = 429)
        assertEquals(ChatException.Kind.RATE_LIMIT, collectError(provider.stream(Endpoint(base(), "k"), request)).kind)
    }

    @Test
    fun `openai host uses max_completion_tokens`() {
        val body = OpenAiCompatibleProvider.buildChatBody("api.openai.com", request)
        assertEquals(256, body["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertNull(body["max_tokens"])
        val noParams = OpenAiCompatibleProvider.buildChatBody("x", request.copy(temperature = null, maxTokens = null))
        assertNull(noParams["temperature"])
        assertNull(noParams["max_tokens"])
    }

    @Test
    fun `normalizes pasted endpoints`() {
        assertEquals("https://api.x.com/v1", OpenAiCompatibleProvider.normalizeBase("https://api.x.com/v1/chat/completions/"))
        assertEquals("https://api.x.com/v1", OpenAiCompatibleProvider.normalizeBase(" https://api.x.com/v1 "))
    }

    @Test
    fun `lists models and splits embedding models`() {
        enqueueJson("""{"data":[{"id":"gpt-x"},{"id":"text-embedding-3-small"},{"id":"BAAI/bge-m3"},{"id":"a-model"}]}""")
        val models = runBlocking { provider.listModels(Endpoint(base("/v1"), "k")) }
        assertEquals(listOf("a-model", "gpt-x"), models.chat)
        assertEquals(listOf("BAAI/bge-m3", "text-embedding-3-small"), models.embedding)
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `embeddings come back in input order`() {
        enqueueJson("""{"data":[{"index":1,"embedding":[0.5,0.5]},{"index":0,"embedding":[1,0]}]}""")
        val vectors = runBlocking { provider.embed(Endpoint(base("/v1"), "k"), "emb", listOf("a", "b")) }
        assertEquals(listOf(1f, 0f), vectors[0].toList())
        assertEquals(listOf(0.5f, 0.5f), vectors[1].toList())
        val body = server.takeRequest().jsonBody()
        assertEquals(listOf("a", "b"), body["input"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `embedding count mismatch is a protocol error`() {
        enqueueJson("""{"data":[{"index":0,"embedding":[1]}]}""")
        val error = runCatching { runBlocking { provider.embed(Endpoint(base(), "k"), "emb", listOf("a", "b")) } }.exceptionOrNull()
        assertEquals(ChatException.Kind.PROTOCOL, (error as ChatException).kind)
    }
}

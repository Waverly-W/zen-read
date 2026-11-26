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
import org.junit.Assert.assertNull
import org.junit.Test

internal class AnthropicProviderTest : ProviderTestSupport() {
    private val provider by lazy { AnthropicProvider(http) }

    @Test
    fun `streams text and thinking from typed events`() {
        enqueueSse(
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":25,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Hmm."}}

            event: ping
            data: {"type":"ping"}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" there"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":15}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent(),
        )
        val request = ProviderRequest(
            model = "claude-test",
            messages = listOf(ChatMessage.system("be brief"), ChatMessage.user("a"), ChatMessage.user("b"), ChatMessage.assistant("c")),
            temperature = 1.7,
        )
        val events = collect(provider.stream(Endpoint(base("/v1"), "sk-ant"), request))
        assertEquals("Hello there", events.text())
        assertEquals("Hmm.", events.reasoning())
        assertEquals(ChatEvent.Done(FinishReason.LENGTH, Usage(25, 15)), events.last())

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.url.encodedPath)
        assertEquals("sk-ant", recorded.headers["x-api-key"])
        assertEquals(AnthropicProvider.API_VERSION, recorded.headers["anthropic-version"])
        assertNull(recorded.headers["Authorization"])
        val body = recorded.jsonBody()
        assertEquals("be brief", body["system"]!!.jsonPrimitive.content)
        assertEquals(AnthropicProvider.DEFAULT_MAX_TOKENS, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(1.0, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("a\n\nb", messages[0]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merges turns so the conversation starts with the user`() {
        val merged = AnthropicProvider.mergeTurns(listOf(ChatMessage.assistant("x"), ChatMessage.assistant("y"), ChatMessage.user("z")))
        assertEquals(listOf("…", "x\n\ny", "z"), merged.map { it.content })
    }

    @Test
    fun `error events become exceptions`() {
        enqueueSse(
            "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n",
        )
        val error = collectError(provider.stream(Endpoint(base(), "k"), ProviderRequest("m", listOf(ChatMessage.user("q")))))
        assertEquals(ChatException.Kind.SERVER, error.kind)
        assertEquals("Overloaded", error.message)
    }

    @Test
    fun `a stream cut before message_stop is a network error`() {
        enqueueSse(
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"par\"}}\n\n",
        )
        val error = collectError(provider.stream(Endpoint(base(), "k"), ProviderRequest("m", listOf(ChatMessage.user("q")))))
        assertEquals(ChatException.Kind.NETWORK, error.kind)
    }

    @Test
    fun `tolerates a v1 suffix in the base url and lists models`() {
        enqueueJson("""{"data":[{"id":"claude-a","type":"model"},{"id":"claude-b","type":"model"}],"has_more":false}""")
        val models = runBlocking { provider.listModels(Endpoint(base("/v1"), "k")) }
        assertEquals(listOf("claude-a", "claude-b"), models.chat)
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `embeddings are unsupported`() {
        val error = runCatching { runBlocking { provider.embed(Endpoint(base(), "k"), "m", listOf("a")) } }.exceptionOrNull()
        assertEquals(ChatException.Kind.UNSUPPORTED, (error as ChatException).kind)
    }
}

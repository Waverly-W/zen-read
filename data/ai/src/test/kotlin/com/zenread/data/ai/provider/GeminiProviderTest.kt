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
import org.junit.Test

internal class GeminiProviderTest : ProviderTestSupport() {
    private val provider by lazy { GeminiProvider(http) }

    @Test
    fun `streams parts, separates thoughts and reads finish reason`() {
        enqueueSse(
            """
            data: {"candidates":[{"content":{"parts":[{"text":"pondering","thought":true}],"role":"model"},"index":0}]}

            data: {"candidates":[{"content":{"parts":[{"text":"Bon"}],"role":"model"},"index":0}]}

            data: {"candidates":[{"content":{"parts":[{"text":"jour"}],"role":"model"},"finishReason":"MAX_TOKENS","index":0}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2}}

            """.trimIndent(),
        )
        val request = ProviderRequest(
            model = "models/gemini-test",
            messages = listOf(ChatMessage.system("sys"), ChatMessage.user("hi"), ChatMessage.assistant("yo"), ChatMessage.user("q")),
            temperature = 0.2,
            maxTokens = 64,
        )
        val events = collect(provider.stream(Endpoint(base("/v1beta"), "g-key"), request))
        assertEquals("Bonjour", events.text())
        assertEquals("pondering", events.reasoning())
        assertEquals(ChatEvent.Done(FinishReason.LENGTH, Usage(9, 2)), events.last())

        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-test:streamGenerateContent", recorded.url.encodedPath)
        assertEquals("sse", recorded.url.queryParameter("alt"))
        assertEquals("g-key", recorded.headers["x-goog-api-key"])
        val body = recorded.jsonBody()
        assertEquals(listOf("user", "model", "user"), body["contents"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val system = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("sys", system)
        val config = body["generationConfig"]!!.jsonObject
        assertEquals(64, config["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `safety blocks map to filtered`() {
        enqueueSse("data: {\"candidates\":[{\"finishReason\":\"SAFETY\",\"index\":0}]}\n\n")
        val events = collect(provider.stream(Endpoint(base(), "k"), ProviderRequest("gemini-x", listOf(ChatMessage.user("q")))))
        assertEquals(ChatEvent.Done(FinishReason.FILTERED, null), events.single())
    }

    @Test
    fun `an empty stream is a protocol error`() {
        enqueueSse("")
        val error = collectError(provider.stream(Endpoint(base(), "k"), ProviderRequest("gemini-x", listOf(ChatMessage.user("q")))))
        assertEquals(ChatException.Kind.PROTOCOL, error.kind)
    }

    @Test
    fun `http errors carry the google message`() {
        enqueueJson("""{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""", code = 400)
        val error = collectError(provider.stream(Endpoint(base(), "k"), ProviderRequest("gemini-x", listOf(ChatMessage.user("q")))))
        assertEquals(ChatException.Kind.BAD_REQUEST, error.kind)
        assertEquals("HTTP 400: API key not valid.", error.message)
    }

    @Test
    fun `lists models by supported method across pages`() {
        enqueueJson(
            """{"models":[{"name":"models/gemini-a","supportedGenerationMethods":["generateContent","countTokens"]}],"nextPageToken":"p2"}""",
        )
        enqueueJson("""{"models":[{"name":"models/gemini-embedding-001","supportedGenerationMethods":["embedContent"]}]}""")
        val models = runBlocking { provider.listModels(Endpoint(base(), "k")) }
        assertEquals(listOf("gemini-a"), models.chat)
        assertEquals(listOf("gemini-embedding-001"), models.embedding)
        server.takeRequest()
        assertEquals("p2", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test
    fun `batch embeds with the model name in each request`() {
        enqueueJson("""{"embeddings":[{"values":[0.1,0.2]},{"values":[0.3,0.4]}]}""")
        val vectors = runBlocking { provider.embed(Endpoint(base(), "k"), "gemini-embedding-001", listOf("a", "b")) }
        assertEquals(2, vectors.size)
        assertEquals(0.4f, vectors[1][1], 1e-6f)
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-embedding-001:batchEmbedContents", recorded.url.encodedPath)
        val first = recorded.jsonBody()["requests"]!!.jsonArray[0].jsonObject
        assertEquals("models/gemini-embedding-001", first["model"]!!.jsonPrimitive.content)
    }
}

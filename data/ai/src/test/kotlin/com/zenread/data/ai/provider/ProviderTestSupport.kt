package com.zenread.data.ai.provider

import com.zenread.data.ai.chat.ChatEvent
import com.zenread.data.ai.chat.ChatException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before

internal abstract class ProviderTestSupport {
    protected lateinit var server: MockWebServer
    protected val json = Json { ignoreUnknownKeys = true }
    protected val http = ProviderHttp(OkHttpClient(), json)

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    protected fun base(path: String = "") = server.url(path).toString().trimEnd('/')

    protected fun enqueueSse(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder().code(code).addHeader("Content-Type", "text/event-stream").body(body).build(),
        )
    }

    protected fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    }

    protected fun collect(flow: Flow<ChatEvent>): List<ChatEvent> = runBlocking { flow.toList() }

    protected fun collectError(flow: Flow<ChatEvent>): ChatException = runBlocking {
        try {
            flow.toList()
            error("expected a ChatException")
        } catch (e: ChatException) {
            e
        }
    }

    protected fun RecordedRequest.jsonBody(): JsonObject = json.parseToJsonElement(body!!.utf8()).jsonObject

    protected fun List<ChatEvent>.text() = filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }

    protected fun List<ChatEvent>.reasoning() = filterIsInstance<ChatEvent.Reasoning>().joinToString("") { it.text }
}

package com.zenread.data.translate.service

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before

abstract class ServiceTest {
    protected lateinit var server: MockWebServer
    protected val client = OkHttpClient()
    protected val json = Json { ignoreUnknownKeys = true }

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    protected fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse(code = code, body = body))

    protected fun take(): RecordedRequest = server.takeRequest()

    protected fun RecordedRequest.jsonBody(): JsonElement = json.parseToJsonElement(body!!.utf8())
}

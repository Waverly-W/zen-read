package com.zenread.data.translate.service

import com.zenread.data.translate.Credentials
import com.zenread.data.translate.Language
import com.zenread.data.translate.TranslateException
import com.zenread.data.translate.Translation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleFreeTranslatorTest : ServiceTest() {
    private fun translator() = GoogleFreeTranslator(client, json, server.url("/translate_a/single"))

    @Test
    fun buildsGtxQueryAndJoinsSegments() = runBlocking {
        respond("""[[["你好，","Hello, ",null,null,10],["世界。","world.",null,null,10]],null,"en",null,null,null,1]""")

        val result = translator().translate(listOf("Hello, world."), null, Language.SIMPLIFIED_CHINESE, Credentials.NONE)

        assertEquals(listOf(Translation("你好，世界。", Language.ENGLISH)), result)
        val url = take().url
        assertEquals("/translate_a/single", url.encodedPath)
        assertEquals("gtx", url.queryParameter("client"))
        assertEquals("auto", url.queryParameter("sl"))
        assertEquals("zh-CN", url.queryParameter("tl"))
        assertEquals("t", url.queryParameter("dt"))
        assertEquals("Hello, world.", url.queryParameter("q"))
    }

    @Test
    fun sendsOneRequestPerTextWithExplicitSource() = runBlocking {
        respond("""[[["Bonjour","Hello"]],null,"en"]""")
        respond("""[[["Monde","World"]],null,"en"]""")

        val result = translator().translate(listOf("Hello", "World"), Language.ENGLISH, Language.FRENCH, Credentials.NONE)

        assertEquals(listOf("Bonjour", "Monde"), result.map { it.text })
        assertEquals("en", take().url.queryParameter("sl"))
        assertEquals("World", take().url.queryParameter("q"))
    }

    @Test
    fun mapsRateLimitToQuota() = runBlocking {
        respond("", code = 429)
        val error = runCatching { translator().translate(listOf("Hi"), null, Language.GERMAN, Credentials.NONE) }
            .exceptionOrNull() as TranslateException
        assertEquals(TranslateException.Reason.QUOTA, error.reason)
    }

    @Test
    fun rejectsUnexpectedShape() {
        val error = runCatching { GoogleFreeTranslator.parse(json.parseToJsonElement("""{"error":1}""")) }.exceptionOrNull()
        assertEquals(TranslateException.Reason.BAD_RESPONSE, (error as TranslateException).reason)
    }

    @Test
    fun blankTextNeverHitsTheNetwork() = runBlocking {
        val result = translator().translate(listOf("  "), null, Language.GERMAN, Credentials.NONE)
        assertEquals("  ", result.single().text)
        assertEquals(0, server.requestCount)
    }
}

class MicrosoftTranslatorTest : ServiceTest() {
    private fun translator() = MicrosoftTranslator(client, json, server.url("/translate"))

    @Test
    fun sendsKeyRegionAndArrayBody() = runBlocking {
        respond(
            """[{"detectedLanguage":{"language":"en","score":1.0},"translations":[{"text":"你好","to":"zh-Hans"}]},
               {"detectedLanguage":{"language":"en","score":1.0},"translations":[{"text":"世界","to":"zh-Hans"}]}]""",
        )
        val creds = Credentials(mapOf(MicrosoftTranslator.KEY to "secret", MicrosoftTranslator.REGION to "eastasia"))

        val result = translator().translate(listOf("Hello", "World"), null, Language.SIMPLIFIED_CHINESE, creds)

        assertEquals(listOf(Translation("你好", Language.ENGLISH), Translation("世界", Language.ENGLISH)), result)
        val request = take()
        assertEquals("POST", request.method)
        assertEquals("3.0", request.url.queryParameter("api-version"))
        assertEquals("zh-Hans", request.url.queryParameter("to"))
        assertNull(request.url.queryParameter("from"))
        assertEquals("secret", request.headers["Ocp-Apim-Subscription-Key"])
        assertEquals("eastasia", request.headers["Ocp-Apim-Subscription-Region"])
        val body = request.jsonBody().jsonArray
        assertEquals(listOf("Hello", "World"), body.map { it.jsonObject["Text"]!!.jsonPrimitive.content })
    }

    @Test
    fun globalRegionSendsNoRegionHeader() = runBlocking {
        respond("""[{"translations":[{"text":"Hallo"}]}]""")
        val creds = Credentials(mapOf(MicrosoftTranslator.KEY to "k", MicrosoftTranslator.REGION to "global"))
        translator().translate(listOf("Hello"), Language.ENGLISH, Language.GERMAN, creds)
        val request = take()
        assertNull(request.headers["Ocp-Apim-Subscription-Region"])
        assertEquals("en", request.url.queryParameter("from"))
    }

    @Test
    fun missingKeyFailsWithoutRequest() = runBlocking {
        val error = runCatching { translator().translate(listOf("Hi"), null, Language.GERMAN, Credentials.NONE) }
            .exceptionOrNull() as TranslateException
        assertEquals(TranslateException.Reason.MISSING_CREDENTIALS, error.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unauthorizedIsReportedWithoutLeakingTheKey() = runBlocking {
        respond("""{"error":{"code":401000,"message":"bad key"}}""", code = 401)
        val creds = Credentials(mapOf(MicrosoftTranslator.KEY to "super-secret"))
        val error = runCatching { translator().translate(listOf("Hi"), null, Language.GERMAN, creds) }
            .exceptionOrNull() as TranslateException
        assertEquals(TranslateException.Reason.UNAUTHORIZED, error.reason)
        assertFalse(error.message!!.contains("super-secret"))
    }

    @Test
    fun countMismatchIsBadResponse() {
        val body = json.parseToJsonElement("""[{"translations":[{"text":"a"}]}]""")
        val error = runCatching { MicrosoftApi.parse("microsoft", body, 2) }.exceptionOrNull() as TranslateException
        assertEquals(TranslateException.Reason.BAD_RESPONSE, error.reason)
    }
}

class MicrosoftEdgeTranslatorTest : ServiceTest() {
    private var clock = 0L
    private fun translator() = MicrosoftEdgeTranslator(client, json, server.url("/auth"), server.url("/translate"), now = { clock })

    @Test
    fun fetchesTokenOnceAndReusesIt() = runBlocking {
        val t = translator()
        respond("aaa.bbb.ccc")
        respond("""[{"translations":[{"text":"Hallo"}]}]""")
        respond("""[{"translations":[{"text":"Welt"}]}]""")

        assertEquals("Hallo", t.translate(listOf("Hello"), null, Language.GERMAN, Credentials.NONE).single().text)
        assertEquals("Welt", t.translate(listOf("World"), null, Language.GERMAN, Credentials.NONE).single().text)

        assertEquals("/auth", take().url.encodedPath)
        val first = take()
        assertEquals("/translate", first.url.encodedPath)
        assertEquals("Bearer aaa.bbb.ccc", first.headers["Authorization"])
        assertEquals("de", first.url.queryParameter("to"))
        assertEquals("Bearer aaa.bbb.ccc", take().headers["Authorization"])
        assertEquals(3, server.requestCount)
    }

    @Test
    fun refreshesExpiredToken() = runBlocking {
        val t = translator()
        respond("aaa.bbb.ccc")
        respond("""[{"translations":[{"text":"Hallo"}]}]""")
        t.translate(listOf("Hello"), null, Language.GERMAN, Credentials.NONE)
        clock += 9 * 60 * 1000L
        respond("ddd.eee.fff")
        respond("""[{"translations":[{"text":"Welt"}]}]""")
        t.translate(listOf("World"), null, Language.GERMAN, Credentials.NONE)
        repeat(3) { take() }
        assertEquals("Bearer ddd.eee.fff", take().headers["Authorization"])
    }

    @Test
    fun retriesOnceWithFreshTokenAfter401() = runBlocking {
        val t = translator()
        respond("aaa.bbb.ccc")
        respond("", code = 401)
        respond("ddd.eee.fff")
        respond("""[{"translations":[{"text":"Hallo"}]}]""")

        assertEquals("Hallo", t.translate(listOf("Hello"), null, Language.GERMAN, Credentials.NONE).single().text)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun htmlInsteadOfTokenIsBadResponse() = runBlocking {
        respond("<html>blocked</html>")
        val error = runCatching { translator().translate(listOf("Hi"), null, Language.GERMAN, Credentials.NONE) }
            .exceptionOrNull() as TranslateException
        assertEquals(TranslateException.Reason.BAD_RESPONSE, error.reason)
    }
}

class DeepLTranslatorTest : ServiceTest() {
    private fun translator() = DeepLTranslator(client, json, server.url("/free/v2/translate"), server.url("/pro/v2/translate"))

    @Test
    fun freeKeyUsesFreeEndpointAndJsonBody() = runBlocking {
        respond("""{"translations":[{"detected_source_language":"EN","text":"Hallo"},{"detected_source_language":"EN","text":"Welt"}]}""")
        val creds = Credentials(mapOf(DeepLTranslator.KEY to "abc:fx"))

        val result = translator().translate(listOf("Hello", "World"), null, Language.GERMAN, creds)

        assertEquals(listOf(Translation("Hallo", Language.ENGLISH), Translation("Welt", Language.ENGLISH)), result)
        val request = take()
        assertEquals("/free/v2/translate", request.url.encodedPath)
        assertEquals("DeepL-Auth-Key abc:fx", request.headers["Authorization"])
        val body = request.jsonBody().jsonObject
        assertEquals(listOf("Hello", "World"), (body["text"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("DE", body["target_lang"]!!.jsonPrimitive.content)
        assertNull(body["source_lang"])
    }

    @Test
    fun proKeyUsesProEndpointAndRegionalTargets() = runBlocking {
        respond("""{"translations":[{"text":"你好"}]}""")
        val creds = Credentials(mapOf(DeepLTranslator.KEY to "pro-key"))
        translator().translate(listOf("Hello"), Language.ENGLISH, Language.SIMPLIFIED_CHINESE, creds)
        val request = take()
        assertEquals("/pro/v2/translate", request.url.encodedPath)
        val body = request.jsonBody() as JsonObject
        assertEquals("ZH-HANS", body["target_lang"]!!.jsonPrimitive.content)
        assertEquals("EN", body["source_lang"]!!.jsonPrimitive.content)
    }

    @Test
    fun quotaExceededIs456() = runBlocking {
        respond("", code = 456)
        val error = runCatching {
            translator().translate(listOf("Hi"), null, Language.GERMAN, Credentials(mapOf(DeepLTranslator.KEY to "k:fx")))
        }.exceptionOrNull() as TranslateException
        assertEquals(TranslateException.Reason.QUOTA, error.reason)
    }
}

class GoogleCloudTranslatorTest : ServiceTest() {
    private fun translator() = GoogleCloudTranslator(client, json, server.url("/language/translate/v2"))

    @Test
    fun keyGoesInHeaderNotUrl() = runBlocking {
        respond("""{"data":{"translations":[{"translatedText":"Hola","detectedSourceLanguage":"en"}]}}""")
        val creds = Credentials(mapOf(GoogleCloudTranslator.KEY to "g-key"))

        val result = translator().translate(listOf("Hello"), null, Language.SPANISH, creds)

        assertEquals(listOf(Translation("Hola", Language.ENGLISH)), result)
        val request = take()
        assertEquals("g-key", request.headers["X-Goog-Api-Key"])
        assertFalse(request.url.toString().contains("g-key"))
        val body = request.jsonBody().jsonObject
        assertEquals("es", body["target"]!!.jsonPrimitive.content)
        assertEquals("text", body["format"]!!.jsonPrimitive.content)
        assertEquals(listOf("Hello"), body["q"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun longTextIsSplitAndRejoined() = runBlocking {
        // 5,000-character limit: two sentences of 3,000 characters become two requests.
        val first = "a".repeat(2_999) + "."
        val second = "b".repeat(2_999) + "."
        respond("""{"data":{"translations":[{"translatedText":"A."}]}}""")
        respond("""{"data":{"translations":[{"translatedText":"B."}]}}""")

        val result = translator().translate(listOf(first + second), null, Language.SPANISH, Credentials(mapOf("api_key" to "k")))

        assertEquals("A.B.", result.single().text)
        assertEquals(2, server.requestCount)
        assertTrue(take().jsonBody().jsonObject["q"]!!.jsonArray.single().jsonPrimitive.content == first)
    }

    @Test
    fun manyShortTextsShareOneRequest() = runBlocking {
        val texts = (1..3).map { "Paragraph $it" }
        respond("""{"data":{"translations":[{"translatedText":"1"},{"translatedText":"2"},{"translatedText":"3"}]}}""")
        val result = translator().translate(texts, null, Language.SPANISH, Credentials(mapOf("api_key" to "k")))
        assertEquals(listOf("1", "2", "3"), result.map { it.text })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverErrorIsUnavailable() = runBlocking {
        respond("", code = 503)
        try {
            translator().translate(listOf("Hi"), null, Language.SPANISH, Credentials(mapOf("api_key" to "k")))
            fail("expected failure")
        } catch (e: TranslateException) {
            assertEquals(TranslateException.Reason.UNAVAILABLE, e.reason)
        }
    }
}

package com.zenread.data.tts

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenAI-compatible `/audio/speech`. Never logs the key, headers or request body. */
@Singleton
class OpenAiTtsClient @Inject constructor(
    client: OkHttpClient,
    private val settings: TtsSettingsRepository,
) : SpeechSynthesizer {
    private val http = client

    override val type = TtsEngineType.OPENAI

    override suspend fun synthesize(text: String, voice: String?, rate: Float, pitch: Float): ByteArray {
        val config = settings.current()
        val key = settings.openAiKey()
        if (key.isNullOrBlank()) throw TtsException(TtsException.Reason.CONFIGURATION, "OpenAI TTS key missing")
        val url = runCatching { OpenAiTtsRequest.endpoint(config.openAiBaseUrl) }
            .getOrElse { throw TtsException(TtsException.Reason.CONFIGURATION, "OpenAI TTS base URL invalid") }
        val body = OpenAiTtsRequest.body(
            text = text,
            model = config.openAiModel.ifBlank { TtsSettings.DEFAULT_OPENAI_MODEL },
            voice = voice?.takeIf { it.isNotBlank() } ?: config.openAiVoice,
            rate = rate,
            pitch = pitch,
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    throw TtsException(TtsException.Reason.HTTP, "OpenAI TTS failed (${response.code})", response.code)
                }
                val bytes = withContext(Dispatchers.IO) { response.body.bytes() }
                if (bytes.isEmpty()) throw TtsException(TtsException.Reason.INVALID_AUDIO, "OpenAI TTS returned no audio")
                bytes
            }
        } catch (e: IOException) {
            throw TtsException(TtsException.Reason.NETWORK, "OpenAI TTS network error: ${e.javaClass.simpleName}")
        }
    }

    override suspend fun voices(): List<TtsVoice> =
        OpenAiTtsRequest.builtInVoices.map { TtsVoice(id = it, name = it.replaceFirstChar(Char::uppercase), locale = "") }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

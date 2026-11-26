// Ported from Modu lib/service/tts/edge_tts_backend.dart (EdgeTtsClient / EdgeTtsProvider),
// which follows edge-tts 7.3.0 (LGPL-3.0-only, see LICENSES/edge-tts-LGPL-3.0.txt).
package com.zenread.data.tts.edge

import com.zenread.data.tts.SpeechSynthesizer
import com.zenread.data.tts.TtsEngineType
import com.zenread.data.tts.TtsException
import com.zenread.data.tts.TtsVoice
import com.zenread.data.tts.await
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Microsoft Edge Read Aloud client using the current consumer protocol. The time-bound
 * `Sec-MS-GEC` value is required; a stale token is rejected with HTTP 403, after which the
 * client corrects its clock from the server's `Date` header and retries once.
 */
@Singleton
class EdgeTtsClient @Inject constructor(client: OkHttpClient) : SpeechSynthesizer {
    private val http = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var clockSkewSeconds = 0.0

    override val type = TtsEngineType.EDGE

    private fun secMsGec() = EdgeTtsProtocol.generateSecMsGec(nowMicros(), clockSkewSeconds)

    override suspend fun voices(): List<TtsVoice> {
        var response = requestVoiceList()
        if (response.code == 403 && adjustClock(response)) {
            response.close()
            response = requestVoiceList()
        }
        response.use {
            if (!it.isSuccessful) throw TtsException(TtsException.Reason.HTTP, "Edge TTS voice list failed (${it.code})", it.code)
            val text = withContext(Dispatchers.IO) { it.body.string() }
            return parseVoices(text)
        }
    }

    private suspend fun requestVoiceList(): Response = try {
        http.newCall(
            Request.Builder()
                .url(EdgeTtsProtocol.voiceListUrl(secMsGec()))
                .header("User-Agent", EdgeTtsProtocol.USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "*/*")
                .header(
                    "Sec-CH-UA",
                    "\"Not_A Brand\";v=\"99\", \"Microsoft Edge\";v=\"${EdgeTtsProtocol.CHROMIUM_MAJOR_VERSION}\", " +
                        "\"Chromium\";v=\"${EdgeTtsProtocol.CHROMIUM_MAJOR_VERSION}\"",
                )
                .header("Sec-CH-UA-Mobile", "?0")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .header("Cookie", "muid=${EdgeTtsProtocol.muid()};")
                .build(),
        ).await()
    } catch (e: IOException) {
        throw TtsException(TtsException.Reason.NETWORK, "Edge TTS network error: ${e.javaClass.simpleName}")
    }

    override suspend fun synthesize(text: String, voice: String?, rate: Float, pitch: Float): ByteArray {
        val chunks = EdgeTtsProtocol.escapeAndSplit(text)
        if (chunks.isEmpty()) return ByteArray(0)
        val audio = ByteArrayOutputStream()
        for (chunk in chunks) {
            audio.write(synthesizeChunk(chunk, voice?.takeIf { it.isNotBlank() } ?: EdgeTtsProtocol.DEFAULT_VOICE, rate, pitch))
        }
        return audio.toByteArray()
    }

    private suspend fun synthesizeChunk(escaped: String, voice: String, rate: Float, pitch: Float): ByteArray {
        for (attempt in 0..1) {
            try {
                return withTimeout(SYNTHESIS_TIMEOUT_MS) { stream(escaped, voice, rate, pitch) }
            } catch (e: UpgradeRejected) {
                if (attempt == 0 && e.statusCode == 403 && adjustClock(e.serverDate)) continue
                throw TtsException(TtsException.Reason.HTTP, "Edge TTS connection rejected (${e.statusCode})", e.statusCode)
            } catch (e: TimeoutCancellationException) {
                throw TtsException(TtsException.Reason.NETWORK, "Edge TTS timed out")
            } catch (e: IOException) {
                throw TtsException(TtsException.Reason.NETWORK, "Edge TTS network error: ${e.javaClass.simpleName}")
            }
        }
        throw TtsException(TtsException.Reason.NETWORK, "Edge TTS WebSocket connection failed")
    }

    private suspend fun stream(escaped: String, voice: String, rate: Float, pitch: Float): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(EdgeTtsProtocol.webSocketUrl(secMsGec(), EdgeTtsProtocol.connectionId()))
                .header("User-Agent", EdgeTtsProtocol.USER_AGENT)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", EdgeTtsProtocol.ORIGIN)
                .header("Cookie", "muid=${EdgeTtsProtocol.muid()};")
                .build()
            val audio = ByteArrayOutputStream()
            val socket = http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val now = Instant.now()
                        webSocket.send(EdgeTtsProtocol.speechConfigMessage(EdgeTtsProtocol.timestamp(now)))
                        webSocket.send(
                            EdgeTtsProtocol.ssmlMessage(
                                escapedText = escaped,
                                voice = voice,
                                rate = rate,
                                pitch = pitch,
                                requestId = EdgeTtsProtocol.connectionId(),
                                timestamp = EdgeTtsProtocol.timestamp(now),
                            ),
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (EdgeTtsProtocol.messagePath(text) != "turn.end") return
                        webSocket.close(1000, null)
                        if (!continuation.isActive) return
                        if (audio.size() == 0) {
                            continuation.resumeWithException(IOException("Edge TTS returned no audio"))
                        } else {
                            continuation.resume(audio.toByteArray())
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val payload = runCatching { EdgeTtsProtocol.audioPayload(bytes.toByteArray()) }.getOrNull()
                        if (payload != null && payload.isNotEmpty()) audio.write(payload)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!continuation.isActive) return
                        val error = if (response != null && response.code != 101) {
                            UpgradeRejected(response.code, response.headers.getDate("Date")?.toInstant())
                        } else {
                            t as? IOException ?: IOException(t.javaClass.simpleName)
                        }
                        response?.close()
                        continuation.resumeWithException(error)
                    }
                },
            )
            continuation.invokeOnCancellation { socket.cancel() }
        }

    private fun adjustClock(response: Response): Boolean = adjustClock(response.headers.getDate("Date")?.toInstant())

    private fun adjustClock(serverDate: Instant?): Boolean {
        serverDate ?: return false
        clockSkewSeconds = (serverDate.toEpochMilli() - System.currentTimeMillis()) / 1000.0
        return true
    }

    private class UpgradeRejected(val statusCode: Int, val serverDate: Instant?) : IOException("Edge TTS upgrade failed ($statusCode)")

    internal companion object {
        const val SYNTHESIS_TIMEOUT_MS = 60_000L

        private fun nowMicros(): Long = Instant.now().let { it.epochSecond * 1_000_000 + it.nano / 1_000 }

        fun parseVoices(json: String): List<TtsVoice> {
            val array = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull()
                ?: throw TtsException(TtsException.Reason.HTTP, "Edge TTS returned an invalid voice list")
            return array.mapNotNull { element ->
                val voice = element as? JsonObject ?: return@mapNotNull null
                fun field(name: String) = (voice[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val shortName = field("ShortName")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TtsVoice(
                    id = shortName,
                    name = field("LocalName") ?: field("FriendlyName") ?: shortName,
                    locale = field("Locale").orEmpty(),
                    gender = field("Gender"),
                )
            }
        }
    }
}

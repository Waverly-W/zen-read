// Ported from Modu lib/service/tts/edge_tts_backend.dart (EdgeTtsClient), which follows
// edge-tts 7.3.0 (LGPL-3.0-only, see LICENSES/edge-tts-LGPL-3.0.txt).
package com.zenread.data.tts.edge

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure parts of the Edge Read Aloud protocol: token, URLs, message framing, SSML. */
internal object EdgeTtsProtocol {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val CHROMIUM_MAJOR_VERSION = "143"
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"
    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
    const val HOST = "speech.platform.bing.com"
    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"

    /** Max UTF-8 bytes of escaped text per SSML request. */
    const val MAX_CHUNK_BYTES = 4096

    private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L

    /**
     * The five-minute, time-bound token expected by Edge Read Aloud.
     * Integer buckets avoid rounding into the next window just before its edge.
     */
    fun generateSecMsGec(nowEpochMicros: Long, clockSkewSeconds: Double = 0.0): String {
        val windowsMicros = nowEpochMicros + (clockSkewSeconds * 1_000_000).roundToLong() + WINDOWS_EPOCH_SECONDS * 1_000_000
        val windowsTicks = (windowsMicros / 300_000_000) * 3_000_000_000
        return EdgeTtsToken.v2Token(windowsTicks, TRUSTED_CLIENT_TOKEN)
    }

    fun voiceListUrl(secMsGec: String): String =
        "https://$HOST/consumer/speech/synthesize/readaloud/voices/list" +
            "?trustedclienttoken=$TRUSTED_CLIENT_TOKEN&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    fun webSocketUrl(secMsGec: String, connectionId: String): String =
        "wss://$HOST/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    fun speechConfigMessage(timestamp: String): String =
        "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
            "\"sentenceBoundaryEnabled\":\"false\"," +
            "\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

    /** [escapedText] must already be XML-escaped (see [escapeAndSplit]). */
    fun ssmlMessage(
        escapedText: String,
        voice: String,
        rate: Float,
        pitch: Float,
        requestId: String,
        timestamp: String,
    ): String {
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${escapeXml(voice)}'><prosody pitch='${percent(pitch.coerceIn(0.5f, 2f))}' " +
            "rate='${percent(rate.coerceIn(0.1f, 3f))}' volume='+0%'>$escapedText</prosody></voice></speak>"
        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    private fun percent(value: Float): String {
        val p = ((value - 1f) * 100f).roundToInt()
        return if (p >= 0) "+$p%" else "$p%"
    }

    /**
     * Replaces control characters, XML-escapes [text] and splits it into chunks of at most
     * [maxBytes] UTF-8 bytes without breaking an escape sequence or a code point.
     */
    fun escapeAndSplit(text: String, maxBytes: Int = MAX_CHUNK_BYTES): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val safe = if (cp <= 8 || cp in 11..12 || cp in 14..31) " " else String(Character.toChars(cp))
            val escaped = escapeXml(safe)
            val bytes = escaped.toByteArray(Charsets.UTF_8).size
            if (currentBytes + bytes > maxBytes && currentBytes > 0) {
                chunks += current.toString().trim()
                current.clear()
                currentBytes = 0
            }
            current.append(escaped)
            currentBytes += bytes
        }
        if (currentBytes > 0) chunks += current.toString().trim()
        return chunks.filter { it.isNotEmpty() }
    }

    /** Same escapes as Dart's `HtmlEscape()` (HtmlEscapeMode.unknown). */
    fun escapeXml(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                '/' -> append("&#47;")
                else -> append(c)
            }
        }
    }

    /**
     * Binary frames are `[2-byte big-endian header length][header][payload]`. Returns the
     * payload for `Path:audio` frames, null for other frames.
     */
    fun audioPayload(message: ByteArray): ByteArray? {
        if (message.size < 2) return null
        val headerLength = ((message[0].toInt() and 0xff) shl 8) or (message[1].toInt() and 0xff)
        val payloadStart = headerLength + 2
        require(payloadStart <= message.size) { "Invalid Edge TTS audio frame" }
        val header = String(message, 2, headerLength, Charsets.UTF_8)
        if (!header.lowercase(Locale.ROOT).contains("path:audio")) return null
        return message.copyOfRange(payloadStart, message.size)
    }

    /** The lower-cased `Path` header of a text frame, e.g. `turn.end`. */
    fun messagePath(message: String): String? {
        val headerEnd = message.indexOf("\r\n\r\n")
        val headers = if (headerEnd < 0) message else message.substring(0, headerEnd)
        for (line in headers.split("\r\n")) {
            val separator = line.indexOf(':')
            if (separator < 0) continue
            if (line.substring(0, separator).trim().lowercase(Locale.ROOT) == "path") {
                return line.substring(separator + 1).trim().lowercase(Locale.ROOT)
            }
        }
        return null
    }

    private val TIMESTAMP = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC)

    /** JavaScript `Date.toString()` style, which the service expects in X-Timestamp. */
    fun timestamp(now: Instant): String = "${TIMESTAMP.format(now)} GMT+0000 (Coordinated Universal Time)"

    fun connectionId(): String = UUID.randomUUID().toString().replace("-", "").lowercase(Locale.ROOT)

    fun muid(): String = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)
}

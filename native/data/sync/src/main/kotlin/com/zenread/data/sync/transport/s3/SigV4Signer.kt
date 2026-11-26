package com.zenread.data.sync.transport.s3

import com.zenread.data.sync.transport.sha256Hex
import com.zenread.data.sync.transport.toHex
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl

/**
 * AWS Signature Version 4 for S3 (header-based, single-chunk payload), ported from upstream
 * `s3_signer.dart`. Verified against the examples in the AWS S3 API reference and the
 * upstream vectors generated with `@smithy/signature-v4`.
 *
 * S3 does not double-encode the canonical URI: each path segment is RFC 3986-encoded once.
 * Callers should send exactly that encoding ([S3Paths.encodeSegment]) so every
 * S3-compatible server computes the same canonical path.
 */
object SigV4Signer {
    const val EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    data class Credentials(val accessKey: String, val secretKey: String, val sessionToken: String = "") {
        override fun toString() = "Credentials(<redacted>)"
    }

    /**
     * Headers to add to the request: `x-amz-date`, `x-amz-content-sha256`,
     * `x-amz-security-token` (with a session token) and `authorization`.
     *
     * [headers] are extra headers to sign (e.g. `range`, `content-type`), names in any case.
     * `host` is derived from [url] the way OkHttp sends it.
     */
    fun sign(
        method: String,
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        payloadHash: String,
        credentials: Credentials,
        region: String,
        now: Instant,
        service: String = "s3",
    ): Map<String, String> {
        val amzDate = AMZ_DATE.format(now)
        val day = amzDate.substring(0, 8)
        val added = linkedMapOf("x-amz-date" to amzDate, "x-amz-content-sha256" to payloadHash)
        if (credentials.sessionToken.isNotEmpty()) added["x-amz-security-token"] = credentials.sessionToken

        val signed = sortedMapOf<String, String>()
        headers.forEach { (name, value) -> signed[name.lowercase()] = value }
        signed["host"] = hostHeader(url)
        signed.putAll(added)

        val canonical = canonicalRequest(method, url, signed, payloadHash)
        val scope = "$day/$region/$service/aws4_request"
        val stringToSign = "$ALGORITHM\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
        var key = hmac("AWS4${credentials.secretKey}".toByteArray(Charsets.UTF_8), day)
        for (part in listOf(region, service, "aws4_request")) key = hmac(key, part)
        val signature = hmac(key, stringToSign).toHex()

        return added + ("authorization" to "$ALGORITHM Credential=${credentials.accessKey}/$scope, " +
            "SignedHeaders=${signed.keys.joinToString(";")}, Signature=$signature")
    }

    internal fun canonicalRequest(method: String, url: HttpUrl, signedHeaders: Map<String, String>, payloadHash: String): String {
        val sorted = signedHeaders.mapKeys { it.key.lowercase() }.toSortedMap()
        return buildString {
            append(method).append('\n')
            append(canonicalUri(url)).append('\n')
            append(canonicalQuery(url)).append('\n')
            sorted.forEach { (name, value) -> append(name).append(':').append(value.trim().replace(WHITESPACE, " ")).append('\n') }
            append('\n')
            append(sorted.keys.joinToString(";")).append('\n')
            append(payloadHash)
        }
    }

    internal fun canonicalUri(url: HttpUrl): String =
        "/" + url.pathSegments.joinToString("/") { uriEncode(it) }

    internal fun canonicalQuery(url: HttpUrl): String {
        val raw = url.encodedQuery ?: return ""
        if (raw.isEmpty()) return ""
        return raw.split('&').filter { it.isNotEmpty() }.map { pair ->
            val eq = pair.indexOf('=')
            val name = percentDecode(if (eq < 0) pair else pair.substring(0, eq))
            val value = if (eq < 0) "" else percentDecode(pair.substring(eq + 1))
            uriEncode(name) to uriEncode(value)
        }.sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { "${it.first}=${it.second}" }
    }

    /** The Host header OkHttp sends: the port only when it is not the scheme's default. */
    fun hostHeader(url: HttpUrl): String {
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
    }

    /** RFC 3986 encoding: everything except `A-Z a-z 0-9 - _ . ~` as uppercase `%XX`. */
    fun uriEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            ) {
                out.append(c.toChar())
            } else {
                out.append('%').append(UPPER_HEX[c ushr 4]).append(UPPER_HEX[c and 0x0f])
            }
        }
        return out.toString()
    }

    /** Decodes `%XX` escapes; `+` stays a literal plus, as S3 expects. */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length &&
                value[i + 1].digitToIntOrNull(16) != null && value[i + 2].digitToIntOrNull(16) != null
            ) {
                out.write(value.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data.toByteArray(Charsets.UTF_8))

    private val WHITESPACE = Regex("\\s+")
    private val UPPER_HEX = "0123456789ABCDEF".toCharArray()
}

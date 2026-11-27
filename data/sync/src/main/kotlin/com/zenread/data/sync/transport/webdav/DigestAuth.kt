package com.zenread.data.sync.transport.webdav

import com.zenread.data.sync.transport.toHex
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Challenge
import okhttp3.Request

/**
 * HTTP Digest authentication (RFC 7616, `qop=auth`, MD5 / SHA-256 and their `-sess` forms).
 * Most WebDAV servers use Basic over HTTPS; some NAS and Apache setups only offer Digest.
 */
internal class DigestAuth(
    private val username: String,
    private val password: String,
    challenge: Challenge,
) {
    private val realm = challenge.authParams["realm"].orEmpty()
    val nonce = challenge.authParams["nonce"].orEmpty()
    private val opaque = challenge.authParams["opaque"]
    private val algorithm = challenge.authParams["algorithm"] ?: "MD5"
    private val qop = challenge.authParams["qop"]?.split(',')?.map { it.trim() }
        ?.let { if ("auth" in it) "auth" else null }
    private val counter = AtomicInteger(0)

    val supported: Boolean
        get() = nonce.isNotEmpty() && algorithm.uppercase().removeSuffix("-SESS") in setOf("MD5", "SHA-256")

    fun authorization(request: Request): String {
        val uri = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        val nc = "%08x".format(counter.incrementAndGet())
        val cnonce = ByteArray(8).also(random::nextBytes).toHex()
        val base = algorithm.uppercase().removeSuffix("-SESS")
        val session = algorithm.uppercase().endsWith("-SESS")
        fun h(value: String) = hash(base, value)
        var ha1 = h("$username:$realm:$password")
        if (session) ha1 = h("$ha1:$nonce:$cnonce")
        val ha2 = h("${request.method}:$uri")
        val response = if (qop != null) h("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else h("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"").append(quote(username)).append('"')
            append(", realm=\"").append(quote(realm)).append('"')
            append(", nonce=\"").append(quote(nonce)).append('"')
            append(", uri=\"").append(quote(uri)).append('"')
            append(", algorithm=").append(algorithm)
            if (qop != null) append(", qop=").append(qop).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
            append(", response=\"").append(response).append('"')
            if (opaque != null) append(", opaque=\"").append(quote(opaque)).append('"')
        }
    }

    private fun hash(algorithm: String, value: String): String =
        MessageDigest.getInstance(if (algorithm == "SHA-256") "SHA-256" else "MD5")
            .digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun quote(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        val random = SecureRandom()
    }
}

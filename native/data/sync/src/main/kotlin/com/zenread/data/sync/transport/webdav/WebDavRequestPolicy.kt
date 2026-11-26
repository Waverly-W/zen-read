package com.zenread.data.sync.transport.webdav

import com.zenread.data.sync.transport.SyncException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl

/** Jianguoyun (坚果云) meters WebDAV requests per account and answers bursts with 503. */
internal fun isJianguoyun(url: HttpUrl): Boolean {
    val host = url.host.lowercase().removeSuffix(".")
    return host == "jianguoyun.com" || host.endsWith(".jianguoyun.com")
}

/**
 * Per-account circuit breaker, shared by sync and book downloads in this process
 * (ported from upstream `webdav_request_policy.dart`).
 *
 * - After HTTP 429/503 no request is sent until the cooldown ends: Retry-After when given,
 *   otherwise exponential backoff (60 s base, 300 s for Jianguoyun, capped at 30 min).
 * - Jianguoyun's free tier allows 600 requests per 30 minutes; we keep a local budget of
 *   480 to leave room for other devices.
 * - Requests are never replayed automatically.
 */
internal class WebDavRequestPolicy(
    private val jianguoyun: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val requestLimit: Int = 480,
) {
    private val requests = ArrayDeque<Long>()
    private var blockedUntil = 0L
    private var lastBusy = 0L
    private var failures = 0

    @Synchronized
    fun beforeRequest() {
        val time = now()
        if (blockedUntil > time) throw SyncException.RateLimited(blockedUntil - time)
        if (!jianguoyun) return
        while (requests.isNotEmpty() && requests.first() <= time - WINDOW_MILLIS) requests.removeFirst()
        if (requests.size >= requestLimit) {
            throw SyncException.RateLimited(requests.first() + WINDOW_MILLIS - time)
        }
        requests.addLast(time)
    }

    @Synchronized
    fun observe(code: Int, retryAfter: String?) {
        if (code != 429 && code != 503) return
        val time = now()
        if (time - lastBusy > WINDOW_MILLIS) failures = 0
        // Concurrent failures from one burst count once.
        if (blockedUntil <= time) failures++
        lastBusy = time
        val base = if (jianguoyun) 300_000L else 60_000L
        var until = time + (base shl (failures - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MILLIS)
        parseRetryAfter(retryAfter, time)?.let { if (it > until) until = it }
        if (until > blockedUntil) blockedUntil = until
    }

    @Synchronized
    fun cooldownRemaining(): Long = (blockedUntil - now()).coerceAtLeast(0)

    private fun parseRetryAfter(value: String?, time: Long): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { seconds ->
            return if (seconds < 0) null else time + seconds.coerceAtMost(7 * 24 * 3600L) * 1000
        }
        return runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()?.coerceAtMost(time + 7 * 24 * 3600_000L)
    }

    companion object {
        const val WINDOW_MILLIS = 30 * 60_000L
        private const val MAX_BACKOFF_MILLIS = 30 * 60_000L
        private val accounts = ConcurrentHashMap<String, WebDavRequestPolicy>()

        /** One policy per server and account; paths and passwords do not split it. */
        fun forAccount(url: HttpUrl, username: String): WebDavRequestPolicy {
            val jianguoyun = isJianguoyun(url)
            val host = if (jianguoyun) "jianguoyun.com" else "${url.scheme}://${url.host}:${url.port}"
            val account = if (jianguoyun) username.trim().lowercase() else username
            return accounts.getOrPut("$host\n$account") { WebDavRequestPolicy(jianguoyun) }
        }
    }
}

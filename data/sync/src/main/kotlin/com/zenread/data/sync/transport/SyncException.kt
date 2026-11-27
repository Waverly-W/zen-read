package com.zenread.data.sync.transport

import java.io.IOException

/** What went wrong, coarse enough to show the user a helpful message. */
enum class SyncErrorKind {
    /** Wrong user name, password, access key or secret. */
    AUTH,

    /** Credentials accepted but not allowed to access this path or bucket. */
    FORBIDDEN,

    /** URL, bucket or path does not exist. */
    NOT_FOUND,

    /** The server asked us to slow down (HTTP 429/503, or the Jianguoyun quota). */
    RATE_LIMITED,

    /** No connection, DNS failure, timeout, TLS failure. */
    NETWORK,

    /** HTTP 5xx or another unexpected status. */
    SERVER,

    /** The server's storage is full (HTTP 507 / quota exceeded). */
    STORAGE_FULL,

    /** Malformed or unexpected data from the server. */
    PROTOCOL,

    /** The cloud data was written by a newer, incompatible version of the app. */
    INCOMPATIBLE,

    /** The settings are incomplete or invalid. */
    CONFIG,

    /** The device clock is too far off for S3 signing. */
    CLOCK_SKEW,
    UNKNOWN,
}

/**
 * Sync failures. Messages are short, English, and never contain credentials, signatures,
 * authorization headers or response bodies; they may name relative remote paths.
 */
sealed class SyncException(val kind: SyncErrorKind, message: String, cause: Throwable? = null) :
    IOException(message, cause) {

    /** Worth retrying later without user action. */
    open val retryable: Boolean get() = false

    class Unauthorized(detail: String = "Authentication failed") : SyncException(SyncErrorKind.AUTH, detail)

    class Forbidden(detail: String = "Access denied") : SyncException(SyncErrorKind.FORBIDDEN, detail)

    class NotFound(detail: String) : SyncException(SyncErrorKind.NOT_FOUND, detail)

    class RateLimited(val retryAfterMillis: Long) :
        SyncException(SyncErrorKind.RATE_LIMITED, "Server is busy; retry in ${(retryAfterMillis + 999) / 1000} s") {
        override val retryable get() = true
    }

    class Network(cause: IOException) :
        SyncException(SyncErrorKind.NETWORK, "Network error: ${cause.javaClass.simpleName}", cause) {
        override val retryable get() = true
    }

    class Http(val code: Int, detail: String = "HTTP $code") :
        SyncException(if (code == 507) SyncErrorKind.STORAGE_FULL else SyncErrorKind.SERVER, detail) {
        override val retryable get() = code >= 500 && code != 507
    }

    class Protocol(detail: String) : SyncException(SyncErrorKind.PROTOCOL, detail)

    class Incompatible(detail: String) : SyncException(SyncErrorKind.INCOMPATIBLE, detail)

    class Config(detail: String) : SyncException(SyncErrorKind.CONFIG, detail)

    class ClockSkew : SyncException(SyncErrorKind.CLOCK_SKEW, "Device clock differs too much from the server")
}

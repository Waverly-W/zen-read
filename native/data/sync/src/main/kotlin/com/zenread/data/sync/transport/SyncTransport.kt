package com.zenread.data.sync.transport

import java.io.File

/** One child of a remote directory. */
data class RemoteEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val etag: String? = null,
    /** Epoch millis, when the server reports it. */
    val modifiedAt: Long? = null,
)

/**
 * Minimal file store the sync engine works against. Paths are relative to the configured
 * root (WebDAV URL, or S3 bucket + prefix), use `/` separators and never start with `/`.
 *
 * Writes are all-or-nothing as seen by readers: WebDAV uploads to a temporary name and
 * MOVEs it into place, S3 PUT is atomic by itself.
 *
 * Every failure is a [SyncException]. Implementations never put credentials, signatures or
 * authorization headers into exception messages or logs.
 */
interface SyncTransport {
    /** Credential-free description for the UI and logs, e.g. `WebDAV · dav.example.com`. */
    val label: String

    /** Stable, secret-free id of the remote location; keys local caches. */
    val identity: String

    /**
     * Minimum time between automatic syncs. Non-zero for servers with a request quota
     * (Jianguoyun allows 600 requests per 30 minutes per account).
     */
    val minAutoSyncIntervalMillis: Long get() = 0

    /** Children of a directory; null when it does not exist (S3 returns an empty list). */
    suspend fun list(path: String): List<RemoteEntry>?

    /** Metadata of a file; null when it does not exist. */
    suspend fun stat(path: String): RemoteEntry?

    /** Whole content of a small file; null when it does not exist. */
    suspend fun read(path: String, maxBytes: Long = MAX_READ_BYTES): ByteArray?

    /** Streams a file to [target] (replaced atomically); false when it does not exist. */
    suspend fun download(path: String, target: File): Boolean

    suspend fun write(path: String, bytes: ByteArray, contentType: String = CONTENT_TYPE_JSON)

    /** [sha256Hex] of the file when already known (S3 signs the payload hash). */
    suspend fun upload(path: String, source: File, sha256Hex: String? = null, contentType: String = CONTENT_TYPE_BINARY)

    /** Deletes a file; succeeds when it is already gone. */
    suspend fun delete(path: String)

    /** Creates a directory and its parents (no-op for S3). */
    suspend fun ensureDirectory(path: String)

    companion object {
        const val CONTENT_TYPE_JSON = "application/json"
        const val CONTENT_TYPE_BINARY = "application/octet-stream"
        const val MAX_READ_BYTES = 64L * 1024 * 1024
    }
}

/** Joins relative path segments with `/`, ignoring empty ones. */
fun remotePath(vararg parts: String): String =
    parts.flatMap { it.split('/') }.filter { it.isNotEmpty() }.joinToString("/")

/** Validates a relative path built from untrusted input. */
internal fun checkRelativePath(path: String) {
    val segments = path.split('/')
    if (path.isEmpty() || path.contains('\\') || segments.any { it.isEmpty() || it == "." || it == ".." } ||
        path.any { it.code < 0x20 || it.code == 0x7f }
    ) {
        throw SyncException.Protocol("Unsafe remote path")
    }
}

package com.zenread.data.sync.engine

import com.zenread.data.sync.transport.SyncException
import kotlinx.serialization.Serializable

/**
 * Remote directory layout, relative to the configured WebDAV URL or S3 bucket/prefix.
 * Documented in `ARCHITECTURE.md` §5.
 *
 * ```
 * ZenRead/v1/
 *   manifest.json            format marker, written once
 *   log/<sha256>.json        immutable, content-addressed record segments
 *   books/<sha256>.<ext>     book files, named by the SHA-256 of their content
 *   covers/<sha256>          cover images, named by the book's SHA-256
 * ```
 */
object RemoteLayout {
    const val ROOT = "ZenRead/v1"
    const val MANIFEST = "$ROOT/manifest.json"
    const val LOG = "$ROOT/log"
    const val BOOKS = "$ROOT/books"
    const val COVERS = "$ROOT/covers"

    const val FORMAT = "zenread-sync"
    const val SEGMENT_FORMAT = "zenread-sync-segment"
    const val VERSION = 1

    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val SEGMENT = Regex("^([0-9a-f]{64})\\.json$")
    private val EXTENSION = Regex("^[a-z0-9]{1,8}$")

    fun isSha256(value: String) = SHA256.matches(value)

    /** The digest named by a segment file, or null for other names. */
    fun segmentDigest(name: String): String? = SEGMENT.matchEntire(name)?.groupValues?.get(1)

    fun segment(digest: String) = "$LOG/$digest.json"

    fun book(hash: String, extension: String): String {
        if (!isSha256(hash) || !EXTENSION.matches(extension)) throw SyncException.Protocol("Invalid book id")
        return "$BOOKS/$hash.$extension"
    }

    fun cover(hash: String): String {
        if (!isSha256(hash)) throw SyncException.Protocol("Invalid book id")
        return "$COVERS/$hash"
    }

    /** Temporary upload names (`.zenread-<uuid>.part`) and OS sidecars are not data. */
    fun isIgnorable(name: String) = name.startsWith(".") || name in setOf("Thumbs.db", "desktop.ini")
}

/** `ZenRead/v1/manifest.json`. A newer [version] means this app must not write. */
@Serializable
data class RemoteManifest(
    val format: String,
    val version: Int,
    val createdAt: Long = 0,
)

/** One immutable `log/<sha256>.json` file. */
@Serializable
data class Segment(
    val format: String = RemoteLayout.SEGMENT_FORMAT,
    val version: Int = RemoteLayout.VERSION,
    /** Device that published it. */
    val device: String,
    val createdAt: Long,
    /** Sorted by kind, then id. */
    val records: List<SyncRecord>,
)

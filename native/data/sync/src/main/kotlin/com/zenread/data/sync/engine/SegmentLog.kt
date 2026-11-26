package com.zenread.data.sync.engine

import com.zenread.data.sync.transport.RemoteEntry
import com.zenread.data.sync.transport.SyncException
import com.zenread.data.sync.transport.SyncLog
import com.zenread.data.sync.transport.SyncTransport
import com.zenread.data.sync.transport.sha256Hex
import java.io.File
import kotlinx.serialization.SerializationException

/**
 * The record log in `ZenRead/v1/log/`, after upstream `immutable_sync_log.dart`.
 *
 * Every publish writes a new file named by the SHA-256 of its bytes and never changes it,
 * so devices can publish concurrently without locks or compare-and-swap (which many WebDAV
 * servers and S3-compatible stores do not support reliably). Readers verify the digest.
 *
 * Compaction publishes the merged state (tombstones included) as one new segment and then
 * deletes exactly the segments it read. A segment published concurrently by another device
 * was not read, so it is never deleted; a reader that loses a race with a compaction sees a
 * 404 and lists again.
 */
internal class SegmentLog(
    private val transport: SyncTransport,
    /** Per-account cache of verified segments (they are immutable, so never re-downloaded). */
    private val cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Snapshot(val records: Map<String, SyncRecord>, val segments: Set<String>)

    private class Vanished : Exception()

    suspend fun read(): Snapshot {
        repeat(READ_ATTEMPTS) {
            val listing = transport.list(RemoteLayout.LOG).orEmpty()
            cleanUpTemporaryUploads(listing)
            val segments = listing.filter { !it.isDirectory && RemoteLayout.segmentDigest(it.name) != null }
            try {
                val merged = LinkedHashMap<String, SyncRecord>()
                for (entry in segments) {
                    val segment = load(entry) ?: continue
                    SyncMerge.merge(segment.records.mapNotNull(::validated), merged)
                }
                pruneCache(segments.map { it.name }.toSet())
                return Snapshot(merged, segments.map { it.name }.toSet())
            } catch (_: Vanished) {
                // Removed by a concurrent compaction; its successor is in the next listing.
            }
        }
        throw SyncException.Protocol("Cloud records keep changing; try again later")
    }

    /** Publishes [records] as a new segment and returns its file name. */
    suspend fun publish(records: Collection<SyncRecord>, device: String): String {
        val segment = Segment(
            device = device,
            createdAt = clock(),
            records = records.sortedWith(compareBy({ it.kind }, { it.id })),
        )
        val bytes = SyncJson.encodeToString(Segment.serializer(), segment).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SEGMENT_BYTES) throw SyncException.Protocol("Too many changes for one upload")
        val digest = sha256Hex(bytes)
        val path = RemoteLayout.segment(digest)
        transport.write(path, bytes)
        val stored = transport.stat(path)
        if (stored == null || (stored.size != null && stored.size != bytes.size.toLong())) {
            throw SyncException.Protocol("Upload of $path was not confirmed")
        }
        cache("$digest.json", bytes)
        return "$digest.json"
    }

    /**
     * Replaces [inputs] by one segment holding [state]. Returns how many inputs were
     * removed; a failed delete only leaves redundant segments behind.
     */
    suspend fun compact(state: Map<String, SyncRecord>, inputs: Set<String>, device: String): Int {
        val successor = publish(state.values, device)
        var removed = 0
        for (name in inputs - successor) {
            try {
                transport.delete(RemoteLayout.segment(RemoteLayout.segmentDigest(name)!!))
                File(cacheDir, name).delete()
                removed++
            } catch (e: SyncException) {
                SyncLog.w("Compaction stopped early", e)
                break
            }
        }
        return removed
    }

    private suspend fun load(entry: RemoteEntry): Segment? {
        val name = entry.name
        val digest = RemoteLayout.segmentDigest(name)!!
        val cached = File(cacheDir, name)
        if (cached.isFile) {
            runCatching { return parse(cached.readBytes()) }
            cached.delete()
        }
        val bytes = transport.read(RemoteLayout.segment(digest), MAX_SEGMENT_BYTES.toLong()) ?: throw Vanished()
        if (sha256Hex(bytes) != digest) {
            // An interrupted upload on a server without atomic MOVE. Wait for it to finish
            // (or be retried) for a while, then stop letting it block every sync.
            val age = entry.modifiedAt?.let { clock() - it }
            if (age != null && age > CORRUPT_GRACE_MILLIS) {
                SyncLog.w("Ignoring corrupt cloud segment $name")
                return null
            }
            throw SyncException.Protocol("Cloud segment $name is incomplete; try again later")
        }
        val segment = parse(bytes)
        cache(name, bytes)
        return segment
    }

    private fun parse(bytes: ByteArray): Segment {
        val segment = try {
            SyncJson.decodeFromString(Segment.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw SyncException.Protocol("Unreadable cloud segment")
        } catch (e: IllegalArgumentException) {
            throw SyncException.Protocol("Unreadable cloud segment")
        }
        if (segment.format != RemoteLayout.SEGMENT_FORMAT) throw SyncException.Protocol("Unknown cloud segment format")
        if (segment.version > RemoteLayout.VERSION) {
            throw SyncException.Incompatible("Cloud data was written by a newer version of Zen Read")
        }
        return segment
    }

    private fun validated(record: SyncRecord): SyncRecord? {
        val valid = record.kind.isNotEmpty() && record.kind.length <= 32 && record.id.isNotEmpty() &&
            record.id.length <= 128 && record.id.none { it.code < 0x20 || it == '/' || it == '\\' } &&
            (record.deleted || record.data != null)
        return if (valid) record.normalized() else null
    }

    private suspend fun cleanUpTemporaryUploads(listing: List<RemoteEntry>) {
        val cutoff = clock() - STALE_TEMP_MILLIS
        listing.filter { it.name.startsWith(".zenread-") && (it.modifiedAt ?: Long.MAX_VALUE) < cutoff }.forEach {
            runCatching { transport.delete("${RemoteLayout.LOG}/${it.name}") }
        }
    }

    private fun cache(name: String, bytes: ByteArray) {
        runCatching {
            cacheDir.mkdirs()
            val temp = File(cacheDir, ".$name.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(File(cacheDir, name))) temp.delete()
        }
    }

    private fun pruneCache(keep: Set<String>) {
        cacheDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    companion object {
        const val COMPACT_THRESHOLD = 32
        const val MAX_SEGMENT_BYTES = 64 * 1024 * 1024
        private const val READ_ATTEMPTS = 3
        private const val CORRUPT_GRACE_MILLIS = 60 * 60_000L
        private const val STALE_TEMP_MILLIS = 24 * 60 * 60_000L
    }
}

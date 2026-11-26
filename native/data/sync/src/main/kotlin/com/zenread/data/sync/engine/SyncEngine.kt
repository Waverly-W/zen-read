package com.zenread.data.sync.engine

import com.zenread.data.sync.transport.SyncException
import com.zenread.data.sync.transport.SyncTransport
import com.zenread.data.sync.transport.sha256Hex
import java.io.File
import java.util.UUID
import kotlinx.serialization.SerializationException

/** What to sync, from the user's settings and the current network. */
data class SyncOptions(
    val progress: Boolean = true,
    val annotations: Boolean = true,
    /** Upload book files the cloud does not have yet (downloads happen on demand). */
    val uploadBooks: Boolean = true,
)

enum class SyncPhase { CONNECTING, READING, APPLYING, PUBLISHING, COVERS, BOOKS, COMPACTING }

data class SyncProgress(val phase: SyncPhase, val done: Int = 0, val total: Int = 0)

data class SyncReport(
    /** Local rows changed from the cloud. */
    val pulled: Int,
    /** Records published to the cloud. */
    val pushed: Int,
    val uploadedBooks: Int,
    val uploadedCovers: Int,
    val downloadedCovers: Int,
    /** Segments removed by compaction. */
    val compacted: Int,
)

/**
 * One synchronization between the local library and the cloud:
 *
 * 1. Check `manifest.json` (create it on first use; refuse newer formats).
 * 2. Read and merge every log segment (verified ones come from the local cache).
 * 3. Compare with the local rows ([SyncMerge.plan]); apply the remote winners locally.
 * 4. Publish local winners as one new immutable segment.
 * 5. Upload covers and book files the cloud lacks, download missing covers.
 * 6. Compact the log once it has more than [SegmentLog.COMPACT_THRESHOLD] segments.
 *
 * Interrupting at any point is safe: segments and files are written atomically and
 * content-addressed, and the next run recomputes what is missing from the current state.
 */
class SyncEngine(
    private val transport: SyncTransport,
    private val store: SyncLocalStore,
    /** Root for local caches; each remote account gets its own subdirectory. */
    cacheRoot: File,
    private val device: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cacheDir = File(cacheRoot, sha256Hex(transport.identity.toByteArray()).take(24))
    private val log = SegmentLog(transport, File(cacheDir, "segments"), clock)

    suspend fun sync(options: SyncOptions, onProgress: (SyncProgress) -> Unit = {}): SyncReport {
        onProgress(SyncProgress(SyncPhase.CONNECTING))
        checkManifest(createIfMissing = true)

        onProgress(SyncProgress(SyncPhase.READING))
        val remote = log.read()
        val local = store.snapshot(device)
        val kinds = buildSet {
            add(RecordKind.BOOK)
            if (options.progress) add(RecordKind.PROGRESS)
            if (options.annotations) add(RecordKind.ANNOTATION)
        }
        val plan = SyncMerge.plan(
            local = local.records.filter { it.kind in kinds }.associateBy { it.key },
            remote = remote.records.filterValues { it.kind in kinds },
        )

        onProgress(SyncProgress(SyncPhase.APPLYING))
        val applied = if (plan.toApply.isEmpty()) ApplyResult(0, emptyList()) else store.apply(plan.toApply, device)

        // Everything the cloud holds, of every kind (including ones this device skips or does
        // not understand), plus what we publish now: the input for compaction.
        val state = LinkedHashMap(remote.records)
        var segments = remote.segments
        val toPublish = plan.toPublish + applied.toPublish
        if (toPublish.isNotEmpty()) {
            onProgress(SyncProgress(SyncPhase.PUBLISHING))
            segments = segments + log.publish(toPublish, device)
            SyncMerge.merge(toPublish, state)
        }

        val books = if (applied.applied > 0) store.snapshot(device).books else local.books
        val covers = syncCovers(books, onProgress)
        val uploadedBooks = if (options.uploadBooks) uploadBooks(books, onProgress) else 0

        var compacted = 0
        if (segments.size > SegmentLog.COMPACT_THRESHOLD) {
            onProgress(SyncProgress(SyncPhase.COMPACTING))
            compacted = log.compact(state, segments, device)
        }
        return SyncReport(
            pulled = applied.applied,
            pushed = toPublish.size,
            uploadedBooks = uploadedBooks,
            uploadedCovers = covers.first,
            downloadedCovers = covers.second,
            compacted = compacted,
        )
    }

    /**
     * Downloads a book that exists only in the cloud to [target], verifying its SHA-256.
     * Returns false when the cloud does not have the file.
     */
    suspend fun downloadBook(hash: String, extension: String, target: File): Boolean {
        val temp = File(target.parentFile, ".${target.name}.download")
        try {
            if (!transport.download(RemoteLayout.book(hash, extension), temp)) return false
            if (sha256Hex(temp) != hash) throw SyncException.Protocol("Downloaded book does not match its checksum")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
            return true
        } finally {
            temp.delete()
        }
    }

    /**
     * Checks that the server can hold the sync folder: create it, write, read back, list and
     * delete a probe file, and that any existing cloud data is in a format we understand.
     */
    suspend fun testConnection() {
        transport.ensureDirectory(RemoteLayout.ROOT)
        val name = ".probe-${UUID.randomUUID()}"
        val path = "${RemoteLayout.ROOT}/$name"
        val content = "Zen Read connection test ${clock()}".toByteArray()
        transport.write(path, content, "text/plain")
        try {
            val back = transport.read(path) ?: throw SyncException.Protocol("The test file vanished after upload")
            if (!back.contentEquals(content)) throw SyncException.Protocol("The test file came back different")
            val listing = transport.list(RemoteLayout.ROOT).orEmpty()
            if (listing.none { it.name == name }) throw SyncException.Protocol("The server does not list uploaded files")
        } finally {
            runCatching { transport.delete(path) }
        }
        checkManifest(createIfMissing = false)
    }

    private suspend fun checkManifest(createIfMissing: Boolean) {
        val bytes = transport.read(RemoteLayout.MANIFEST, 64 * 1024)
        if (bytes == null) {
            if (!createIfMissing) return
            val manifest = RemoteManifest(RemoteLayout.FORMAT, RemoteLayout.VERSION, clock())
            transport.ensureDirectory(RemoteLayout.LOG)
            transport.write(RemoteLayout.MANIFEST, SyncJson.encodeToString(RemoteManifest.serializer(), manifest).toByteArray())
            return
        }
        val manifest = try {
            SyncJson.decodeFromString(RemoteManifest.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw SyncException.Protocol("The cloud folder has an unreadable manifest.json")
        } catch (e: IllegalArgumentException) {
            throw SyncException.Protocol("The cloud folder has an unreadable manifest.json")
        }
        if (manifest.format != RemoteLayout.FORMAT) throw SyncException.Protocol("The cloud folder is not a Zen Read sync folder")
        if (manifest.version > RemoteLayout.VERSION) {
            throw SyncException.Incompatible("Cloud data was written by a newer version of Zen Read")
        }
    }

    /** Returns (uploaded, downloaded). Covers are small, so they sync regardless of settings. */
    private suspend fun syncCovers(books: List<LocalBook>, onProgress: (SyncProgress) -> Unit): Pair<Int, Int> {
        val eligible = books.filter { RemoteLayout.isSha256(it.hash) }
        if (eligible.isEmpty()) return 0 to 0
        val remote = transport.list(RemoteLayout.COVERS).orEmpty().filter { !it.isDirectory }.mapTo(HashSet()) { it.name }
        val uploads = eligible.filter { it.cover != null && it.hash !in remote }
        val downloads = eligible.filter { it.cover == null && it.hash in remote }
        var done = 0
        val total = uploads.size + downloads.size
        for (book in uploads) {
            onProgress(SyncProgress(SyncPhase.COVERS, done++, total))
            transport.upload(RemoteLayout.cover(book.hash), book.cover!!)
        }
        var downloaded = 0
        for (book in downloads) {
            onProgress(SyncProgress(SyncPhase.COVERS, done++, total))
            val target = store.coverFile(book.hash)
            target.parentFile?.mkdirs()
            if (transport.download(RemoteLayout.cover(book.hash), target)) {
                if (target.length() in 1..MAX_COVER_BYTES) {
                    store.setCover(book.hash, target)
                    downloaded++
                } else {
                    target.delete()
                }
            }
        }
        return uploads.size to downloaded
    }

    private suspend fun uploadBooks(books: List<LocalBook>, onProgress: (SyncProgress) -> Unit): Int {
        val candidates = books.filter { RemoteLayout.isSha256(it.hash) && it.file.isFile }
        if (candidates.isEmpty()) return 0
        val remote = transport.list(RemoteLayout.BOOKS).orEmpty().filter { !it.isDirectory }.mapTo(HashSet()) { it.name }
        val uploads = candidates.filter { "${it.hash}.${it.extension}" !in remote }
        uploads.forEachIndexed { index, book ->
            onProgress(SyncProgress(SyncPhase.BOOKS, index, uploads.size))
            // A delayed listing is only a hint: check the exact name before re-sending megabytes.
            val path = RemoteLayout.book(book.hash, book.extension)
            if (transport.stat(path)?.size == book.file.length()) return@forEachIndexed
            transport.upload(path, book.file, sha256Hex = book.hash)
        }
        return uploads.size
    }

    private companion object {
        const val MAX_COVER_BYTES = 10L * 1024 * 1024
    }
}

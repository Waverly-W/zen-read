package com.zenread.data.sync.engine

import android.content.Context
import androidx.room.withTransaction
import com.zenread.core.database.AnnotationEntity
import com.zenread.core.database.BookEntity
import com.zenread.core.database.SyncTombstoneEntity
import com.zenread.core.database.ZenReadDatabase
import com.zenread.core.model.AnnotationType
import com.zenread.core.model.BookFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncLocalStore] over the main Room database. Books live at `files/books/<sha256>.<ext>`
 * and covers at `files/covers/<sha256>`, as written by `BookImporter`.
 */
@Singleton
class RoomSyncLocalStore internal constructor(
    private val db: ZenReadDatabase,
    private val filesDir: File,
    private val clock: () -> Long,
) : SyncLocalStore {

    @Inject
    constructor(db: ZenReadDatabase, @ApplicationContext context: Context) :
        this(db, context.filesDir, System::currentTimeMillis)

    private val dao get() = db.syncDao()

    override fun bookFile(hash: String, extension: String) = File(filesDir, "books/$hash.$extension")

    override fun coverFile(hash: String) = File(filesDir, "covers/$hash")

    override suspend fun snapshot(device: String): LocalSnapshot = db.withTransaction {
        val books = dao.books()
        val annotations = dao.annotations()
        val tombstones = dao.tombstones()
        val records = mutableListOf<SyncRecord>()
        val hashById = books.associate { it.id to it.fileHash }
        books.forEach { book ->
            records += book.record(device)
            book.progressRecord(device)?.let(records::add)
        }
        annotations.forEach { annotation ->
            hashById[annotation.bookId]?.let { records += annotation.record(it, device) }
        }
        val live = records.mapTo(HashSet()) { it.kind to it.id }
        tombstones.forEach { tombstone ->
            if ((tombstone.kind to tombstone.syncId) in live) {
                // Re-imported after deletion: the live row is the newer state.
                dao.deleteTombstone(tombstone.kind, tombstone.syncId)
            } else {
                records += SyncRecord(tombstone.kind, tombstone.syncId, tombstone.deletedAt, device, deleted = true)
            }
        }
        LocalSnapshot(
            records = records,
            books = books.mapNotNull { book ->
                val format = BookFormat.entries.firstOrNull { it.name == book.format } ?: return@mapNotNull null
                LocalBook(
                    hash = book.fileHash,
                    extension = format.primaryExtension,
                    file = File(book.filePath),
                    cover = book.coverPath?.let(::File)?.takeIf { it.isFile },
                )
            },
        )
    }

    override suspend fun apply(records: List<SyncRecord>, device: String): ApplyResult {
        val obsoleteFiles = mutableListOf<File>()
        val result = db.withTransaction {
            val tombstones = dao.tombstones().associateBy { it.kind to it.syncId }
            val extra = mutableListOf<SyncRecord>()
            var applied = 0
            for (record in records.sortedBy(::phase)) {
                val changed = when (record.kind) {
                    RecordKind.BOOK ->
                        if (record.deleted) deleteBook(record, device, obsoleteFiles) else upsertBook(record, device, tombstones)
                    RecordKind.PROGRESS -> applyProgress(record, device)
                    RecordKind.ANNOTATION ->
                        if (record.deleted) deleteAnnotation(record) else upsertAnnotation(record, device, tombstones, extra)
                    else -> false
                }
                if (changed) applied++
            }
            ApplyResult(applied, extra)
        }
        obsoleteFiles.forEach { it.delete() }
        return result
    }

    override suspend fun setCover(hash: String, cover: File) {
        val book = dao.bookByHash(hash) ?: return
        dao.updateCoverPath(book.id, cover.absolutePath)
    }

    // --- Apply -------------------------------------------------------------------------------

    /** Parents before children, deletions of books last (they cascade). */
    private fun phase(record: SyncRecord): Int = when (record.kind) {
        RecordKind.BOOK -> if (record.deleted) 4 else 0
        RecordKind.PROGRESS -> 1
        RecordKind.ANNOTATION -> if (record.deleted) 2 else 3
        else -> 5
    }

    private suspend fun upsertBook(
        record: SyncRecord,
        device: String,
        tombstones: Map<Pair<String, String>, SyncTombstoneEntity>,
    ): Boolean {
        val data = record.data?.decodeOrNull(BookData.serializer()) ?: return false
        val format = BookFormat.entries.firstOrNull { it.name == data.format } ?: return false
        if (!RemoteLayout.isSha256(record.id)) return false
        val existing = dao.bookByHash(record.id)
        if (existing == null) {
            val tombstone = tombstones[SyncTombstoneEntity.KIND_BOOK to record.id]
            if (tombstone != null && SyncMerge.compare(tombstone.record(device), record) >= 0) return false
            val cover = coverFile(record.id).takeIf { it.isFile }
            dao.insertBook(
                BookEntity(
                    title = data.title,
                    author = data.author,
                    format = format.name,
                    filePath = bookFile(record.id, format.primaryExtension).absolutePath,
                    coverPath = cover?.absolutePath,
                    fileHash = record.id,
                    lastLocation = null,
                    progress = 0f,
                    createdAt = data.createdAt,
                    lastReadAt = null,
                    updatedAt = record.updatedAt,
                ),
            )
            dao.deleteTombstone(SyncTombstoneEntity.KIND_BOOK, record.id)
            return true
        }
        if (SyncMerge.compare(existing.record(device), record) >= 0) return false
        dao.updateBookMetadata(existing.id, data.title, data.author, format.name, data.createdAt, record.updatedAt)
        return true
    }

    private suspend fun applyProgress(record: SyncRecord, device: String): Boolean {
        if (record.deleted) return false
        val data = record.data?.decodeOrNull(ProgressData.serializer()) ?: return false
        if (data.progress.isNaN() || data.progress !in 0f..1f || data.location.isEmpty()) return false
        val book = dao.bookByHash(record.id) ?: return false
        val local = book.progressRecord(device)
        if (local != null && SyncMerge.compare(local, record) >= 0) return false
        dao.updateProgress(book.id, data.location, data.progress, record.updatedAt)
        return true
    }

    private suspend fun deleteBook(record: SyncRecord, device: String, obsoleteFiles: MutableList<File>): Boolean {
        val existing = dao.bookByHash(record.id) ?: return false
        if (SyncMerge.compare(existing.record(device), record) >= 0) return false
        dao.deleteBook(existing.id)
        dao.upsertTombstone(SyncTombstoneEntity(SyncTombstoneEntity.KIND_BOOK, record.id, record.updatedAt))
        obsoleteFiles += File(existing.filePath)
        existing.coverPath?.let { obsoleteFiles += File(it) }
        return true
    }

    private suspend fun deleteAnnotation(record: SyncRecord): Boolean {
        dao.annotationByUuid(record.id) ?: return false
        dao.deleteAnnotation(record.id)
        dao.upsertTombstone(SyncTombstoneEntity(SyncTombstoneEntity.KIND_ANNOTATION, record.id, record.updatedAt))
        return true
    }

    private suspend fun upsertAnnotation(
        record: SyncRecord,
        device: String,
        tombstones: Map<Pair<String, String>, SyncTombstoneEntity>,
        extra: MutableList<SyncRecord>,
    ): Boolean {
        val data = record.data?.decodeOrNull(AnnotationData.serializer()) ?: return false
        if (AnnotationType.entries.none { it.name == data.type } || data.position.isNaN()) return false
        // Deletion always wins for annotations.
        if ((SyncTombstoneEntity.KIND_ANNOTATION to record.id) in tombstones) return false
        // Annotations of books this device does not have stay in the cloud until it does.
        val book = dao.bookByHash(data.book) ?: return false
        val existing = dao.annotationByUuid(record.id)
        if (existing != null && SyncMerge.compare(existing.record(data.book, device), record) >= 0) return false

        // Two devices marked the same range independently: keep one deterministically
        // (later edit, then larger id) and delete the other everywhere.
        val occupant = dao.annotationByRange(book.id, data.type, data.cfi)?.takeIf { it.uuid != record.id }
        if (occupant != null) {
            val occupantWins = occupant.updatedAt > record.updatedAt ||
                (occupant.updatedAt == record.updatedAt && occupant.uuid > record.id)
            if (occupantWins) {
                dao.upsertTombstone(SyncTombstoneEntity(SyncTombstoneEntity.KIND_ANNOTATION, record.id, clock()))
                extra += tombstone(RecordKind.ANNOTATION, record.id, device)
                existing?.let { dao.deleteAnnotation(it.uuid) }
                return existing != null
            }
            dao.deleteAnnotation(occupant.uuid)
            dao.upsertTombstone(SyncTombstoneEntity(SyncTombstoneEntity.KIND_ANNOTATION, occupant.uuid, clock()))
            extra += tombstone(RecordKind.ANNOTATION, occupant.uuid, device)
        }

        val entity = AnnotationEntity(
            id = existing?.id ?: 0,
            bookId = book.id,
            type = data.type,
            cfi = data.cfi,
            text = data.text,
            chapter = data.chapter,
            color = data.color,
            note = data.note,
            position = data.position,
            createdAt = data.createdAt,
            updatedAt = record.updatedAt,
            uuid = record.id,
        )
        if (existing != null) dao.updateAnnotation(entity) else dao.insertAnnotation(entity)
        return true
    }

    private fun tombstone(kind: String, id: String, device: String) =
        SyncRecord(kind, id, clock(), device, deleted = true)
}

// --- Row ↔ record ------------------------------------------------------------------------------

internal fun BookEntity.record(device: String) = SyncRecord(
    kind = RecordKind.BOOK,
    id = fileHash,
    updatedAt = updatedAt,
    device = device,
    data = BookData(title, author, format, createdAt).toJsonObject(BookData.serializer()),
)

internal fun BookEntity.progressRecord(device: String): SyncRecord? {
    val readAt = lastReadAt ?: return null
    val location = lastLocation?.takeIf { it.isNotEmpty() } ?: return null
    return SyncRecord(
        kind = RecordKind.PROGRESS,
        id = fileHash,
        updatedAt = readAt,
        device = device,
        data = ProgressData(location, progress).toJsonObject(ProgressData.serializer()),
    )
}

internal fun AnnotationEntity.record(bookHash: String, device: String) = SyncRecord(
    kind = RecordKind.ANNOTATION,
    id = uuid,
    updatedAt = updatedAt,
    device = device,
    data = AnnotationData(
        book = bookHash,
        type = type,
        cfi = cfi,
        text = text,
        chapter = chapter,
        color = color,
        note = note,
        position = position,
        createdAt = createdAt,
    ).toJsonObject(AnnotationData.serializer()),
)

internal fun SyncTombstoneEntity.record(device: String) =
    SyncRecord(kind, syncId, deletedAt, device, deleted = true)

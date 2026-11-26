package com.zenread.data.sync.engine

import java.io.File

/** A book as the file-sync step sees it. */
data class LocalBook(
    /** SHA-256 of the file (the book's sync id). */
    val hash: String,
    val extension: String,
    val file: File,
    /** Local cover file, if any. */
    val cover: File?,
)

data class LocalSnapshot(
    /** Every syncable row as a record; tombstones for local deletions. */
    val records: List<SyncRecord>,
    val books: List<LocalBook>,
)

data class ApplyResult(
    /** Rows changed locally. */
    val applied: Int,
    /** Records created while applying (e.g. the loser of two marks on the same range). */
    val toPublish: List<SyncRecord>,
)

/** The device's library as the sync engine reads and changes it. */
interface SyncLocalStore {
    suspend fun snapshot(device: String): LocalSnapshot

    /**
     * Applies remote versions that won the merge. Each one is compared again against the
     * current local row inside one transaction, so an edit made while the sync was running
     * is never overwritten by an older remote version.
     */
    suspend fun apply(records: List<SyncRecord>, device: String): ApplyResult

    /** Where a book's file lives (whether or not it has been downloaded). */
    fun bookFile(hash: String, extension: String): File

    fun coverFile(hash: String): File

    /** Points the book at a cover that was just downloaded to [coverFile]. */
    suspend fun setCover(hash: String, cover: File)
}

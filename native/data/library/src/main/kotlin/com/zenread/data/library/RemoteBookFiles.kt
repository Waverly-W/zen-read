package com.zenread.data.library

import com.zenread.core.model.Book
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Fetches a book file that exists only in the cloud (a book synced from another device).
 * Implemented by `data:sync`; absent when that module is not in the app.
 */
interface RemoteBookFiles {
    /** Downloads [book] to [Book.filePath]. False when the cloud does not have it. */
    suspend fun download(book: Book): Boolean
}

/** Whether a book's file is on this device after [BookRepository.ensureFile]. */
enum class BookFileState {
    /** On this device (already, or just downloaded). */
    AVAILABLE,

    /** Neither on this device nor in the cloud. */
    MISSING,

    /** In the cloud but the download failed (offline, server error). */
    DOWNLOAD_FAILED,
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RemoteBookFilesModule {
    @BindsOptionalOf
    abstract fun remoteBookFiles(): RemoteBookFiles
}

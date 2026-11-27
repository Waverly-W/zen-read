package com.zenread.data.sync

import com.zenread.core.model.Book
import com.zenread.data.library.RemoteBookFiles
import com.zenread.data.sync.engine.RoomSyncLocalStore
import com.zenread.data.sync.engine.SyncLocalStore
import com.zenread.data.sync.work.SyncScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SyncModule {
    @Binds
    abstract fun localStore(store: RoomSyncLocalStore): SyncLocalStore

    @Binds
    abstract fun remoteBookFiles(files: SyncBookFiles): RemoteBookFiles
}

/** Lets the library download books that only exist in the cloud. */
internal class SyncBookFiles @Inject constructor(private val manager: SyncManager) : RemoteBookFiles {
    override suspend fun download(book: Book): Boolean = manager.downloadBook(book)
}

/** For WorkManager workers and the startup initializer, which Hilt cannot inject. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SyncEntryPoint {
    fun syncManager(): SyncManager
    fun syncScheduler(): SyncScheduler
}

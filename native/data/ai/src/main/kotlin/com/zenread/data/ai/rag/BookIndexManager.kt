package com.zenread.data.ai.rag

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zenread.core.model.Book
import com.zenread.core.model.BookFormat
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.settings.AiSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Index state of one book for the UI. */
data class BookIndexState(
    val status: IndexStatus?,
    /** True while a WorkManager job for this book is enqueued or running. */
    val queued: Boolean,
    val running: Boolean,
    val progress: IndexProgress?,
    val chunkCount: Int,
    val embeddedCount: Int,
    val embeddingModel: String?,
    val error: String?,
) {
    /** Keyword search works as soon as all chunks are stored. */
    val searchable: Boolean get() = chunkCount > 0 && status != IndexStatus.EXTRACTING

    companion object {
        val NONE = BookIndexState(null, false, false, null, 0, 0, null, null)
    }
}

data class IndexedBook(
    val bookId: Long,
    val title: String,
    val status: IndexStatus,
    val chunkCount: Int,
    val embeddedCount: Int,
    val embeddingModel: String?,
    val updatedAt: Long,
)

/** Schedules index builds in WorkManager and reports their state. */
@Singleton
class BookIndexManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: IndexStore,
    private val settings: AiSettingsRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun observe(bookId: Long): Flow<BookIndexState> = combine(
        store.dao.observe(bookId),
        workManager.getWorkInfosForUniqueWorkFlow(workName(bookId)),
    ) { header, infos ->
        val info = infos.firstOrNull { !it.state.isFinished }
        val running = info?.state == WorkInfo.State.RUNNING
        val progress = info?.progress?.let { data ->
            val stage = data.getString(KEY_STAGE)?.let { runCatching { IndexStage.valueOf(it) }.getOrNull() }
            stage?.let { IndexProgress(it, data.getInt(KEY_DONE, 0), data.getInt(KEY_TOTAL, 0)) }
        }
        BookIndexState(
            status = header?.status?.let(IndexStatus::of),
            queued = info != null,
            running = running,
            progress = progress,
            chunkCount = header?.chunkCount ?: 0,
            embeddedCount = header?.embeddedCount ?: 0,
            embeddingModel = header?.embeddingModel,
            error = header?.error,
        )
    }

    fun observeIndexedBooks(): Flow<List<IndexedBook>> = store.dao.observeAll().map { list ->
        list.map {
            IndexedBook(it.bookId, it.title, IndexStatus.of(it.status), it.chunkCount, it.embeddedCount, it.embeddingModel, it.updatedAt)
        }
    }

    /** Starts (or resumes) indexing [book]; [rebuild] discards existing chunks and vectors. */
    suspend fun build(book: Book, rebuild: Boolean = false) {
        val options = settings.current().index
        val needsNetwork = settings.current().resolvedEmbeddingModel != null
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(
                workDataOf(
                    KEY_BOOK_ID to book.id,
                    KEY_TITLE to book.title,
                    KEY_PATH to book.filePath,
                    KEY_FORMAT to book.format.name,
                    KEY_HASH to book.fileHash,
                    KEY_REBUILD to rebuild,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        when {
                            !needsNetwork -> NetworkType.NOT_REQUIRED
                            options.unmeteredOnly -> NetworkType.UNMETERED
                            else -> NetworkType.CONNECTED
                        },
                    )
                    .build(),
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(workName(book.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(bookId: Long) {
        workManager.cancelUniqueWork(workName(bookId))
    }

    suspend fun delete(bookId: Long) {
        cancel(bookId)
        store.deleteBook(bookId)
    }

    internal companion object {
        const val TAG = "ai-index"
        const val KEY_BOOK_ID = "bookId"
        const val KEY_TITLE = "title"
        const val KEY_PATH = "path"
        const val KEY_FORMAT = "format"
        const val KEY_HASH = "hash"
        const val KEY_REBUILD = "rebuild"
        const val KEY_STAGE = "stage"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        fun workName(bookId: Long) = "ai-index-$bookId"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface IndexWorkerEntryPoint {
    fun indexer(): BookIndexer
}

/**
 * Runs [BookIndexer] for one book. Uses a Hilt entry point instead of a HiltWorkerFactory
 * so the app's WorkManager configuration stays untouched. If the system stops the job
 * (e.g. the 10-minute limit), the retry resumes from the stored chunks and vectors.
 */
internal class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val input = inputData
        val format = input.getString(BookIndexManager.KEY_FORMAT)?.let { runCatching { BookFormat.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val target = IndexTarget(
            bookId = input.getLong(BookIndexManager.KEY_BOOK_ID, -1).takeIf { it > 0 } ?: return Result.failure(),
            title = input.getString(BookIndexManager.KEY_TITLE).orEmpty(),
            filePath = input.getString(BookIndexManager.KEY_PATH) ?: return Result.failure(),
            format = format,
            fileHash = input.getString(BookIndexManager.KEY_HASH).orEmpty(),
        )
        val indexer = EntryPointAccessors.fromApplication(applicationContext, IndexWorkerEntryPoint::class.java).indexer()
        // A retry after a system stop must resume, not start over.
        val rebuild = input.getBoolean(BookIndexManager.KEY_REBUILD, false) && runAttemptCount == 0
        return try {
            indexer.build(target, rebuild) { progress ->
                setProgress(
                    workDataOf(
                        BookIndexManager.KEY_STAGE to progress.stage.name,
                        BookIndexManager.KEY_DONE to progress.done,
                        BookIndexManager.KEY_TOTAL to progress.total,
                    ),
                )
            }
            Result.success()
        } catch (e: ChatException) {
            val transient = e.kind == ChatException.Kind.NETWORK || e.kind == ChatException.Kind.SERVER ||
                e.kind == ChatException.Kind.RATE_LIMIT
            if (transient && runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure()
        }
    }
}

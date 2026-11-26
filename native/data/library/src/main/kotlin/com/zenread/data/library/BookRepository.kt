package com.zenread.data.library

import com.zenread.core.common.ApplicationScope
import com.zenread.core.common.IoDispatcher
import com.zenread.core.database.BookDao
import com.zenread.core.database.asModel
import com.zenread.core.model.Book
import java.io.File
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class BookRepository @Inject constructor(
    private val dao: BookDao,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val remoteFiles: Optional<RemoteBookFiles>,
) {
    fun observeBooks(): Flow<List<Book>> = dao.observeAll().map { list -> list.map { it.asModel() } }

    suspend fun getBook(id: Long): Book? = dao.get(id)?.asModel()

    /**
     * Makes sure the book's file is on this device, downloading it from the sync server
     * when the book came from another device and was never opened here.
     */
    suspend fun ensureFile(book: Book): BookFileState {
        if (withContext(io) { File(book.filePath).isFile }) return BookFileState.AVAILABLE
        val remote = remoteFiles.orElse(null) ?: return BookFileState.MISSING
        return try {
            if (remote.download(book)) BookFileState.AVAILABLE else BookFileState.MISSING
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BookFileState.DOWNLOAD_FAILED
        }
    }

    /** Fire-and-forget so the write survives the reader screen closing. */
    fun saveProgress(id: Long, location: String, progress: Float) {
        appScope.launch(io) {
            dao.updateProgress(id, location, progress, System.currentTimeMillis())
        }
    }

    suspend fun delete(book: Book) = withContext(io) {
        dao.delete(book.id)
        File(book.filePath).delete()
        book.coverPath?.let { File(it).delete() }
    }
}

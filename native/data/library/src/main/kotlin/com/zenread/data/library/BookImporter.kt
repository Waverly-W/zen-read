package com.zenread.data.library

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.zenread.core.common.IoDispatcher
import com.zenread.core.database.BookDao
import com.zenread.core.database.BookEntity
import com.zenread.core.model.BookFormat
import com.zenread.reader.engine.MetadataExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ImportOutcome {
    val fileName: String

    data class Imported(override val fileName: String, val bookId: Long) : ImportOutcome
    data class Duplicate(override val fileName: String) : ImportOutcome
    data class Unsupported(override val fileName: String) : ImportOutcome
    data class Failed(override val fileName: String, val reason: String) : ImportOutcome
}

/**
 * Copies picked documents into app storage (`files/books/<sha256>.<ext>`),
 * reads metadata through foliate-js, stores the cover, and inserts the row.
 */
@Singleton
class BookImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val metadata: MetadataExtractor,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val booksDir get() = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir get() = File(context.filesDir, "covers").apply { mkdirs() }

    // One import at a time: hashing and WebView parsing are heavy, and serializing
    // makes duplicate detection race-free.
    private val mutex = Mutex()

    suspend fun import(uris: List<Uri>): List<ImportOutcome> = mutex.withLock {
        uris.map { importOne(it) }
    }

    private suspend fun importOne(uri: Uri): ImportOutcome {
        val name = withContext(io) { displayName(uri) } ?: uri.lastPathSegment ?: "book"
        val format = BookFormat.fromFileName(name) ?: return ImportOutcome.Unsupported(name)
        var staged: File? = null
        return try {
            val (tmp, hash) = withContext(io) { copyAndHash(uri) }
            staged = tmp
            if (dao.findByHash(hash) != null) return ImportOutcome.Duplicate(name)

            val target = File(booksDir, "$hash.${format.primaryExtension}")
            withContext(io) { if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true) }
            staged = target

            val meta = metadata.extract(target, format)
            val cover = meta.coverDataUrl?.let { withContext(io) { saveCover(hash, it) } }
            val id = dao.insert(
                BookEntity(
                    title = meta.title ?: name.substringBeforeLast('.'),
                    author = meta.author.orEmpty(),
                    format = format.name,
                    filePath = target.absolutePath,
                    coverPath = cover?.absolutePath,
                    fileHash = hash,
                    lastLocation = null,
                    progress = 0f,
                    createdAt = System.currentTimeMillis(),
                    lastReadAt = null,
                ),
            )
            staged = null
            ImportOutcome.Imported(name, id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("BookImporter", "Import failed: $name", e)
            ImportOutcome.Failed(name, e.message ?: e.javaClass.simpleName)
        } finally {
            staged?.delete()
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun copyAndHash(uri: Uri): Pair<File, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val tmp = File.createTempFile("import", ".tmp", booksDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("Cannot open file")
        return tmp to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun saveCover(hash: String, dataUrl: String): File? {
        val comma = dataUrl.indexOf(',')
        if (comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64")) return null
        val bytes = runCatching { Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
            ?: return null
        // The declared MIME type is unreliable (MOBI covers come as octet-stream);
        // keep the bytes only if Android can decode them as an image.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return File(coversDir, hash).apply { writeBytes(bytes) }
    }
}

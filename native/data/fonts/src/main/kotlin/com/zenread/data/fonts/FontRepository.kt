package com.zenread.data.fonts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.zenread.core.common.ApplicationScope
import com.zenread.core.common.IoDispatcher
import com.zenread.core.datastore.ReaderSettingsRepository
import com.zenread.core.model.ReaderFont
import com.zenread.reader.engine.ReaderFontFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "ZenFonts"

/** Which part of the text a font choice applies to. */
enum class FontTarget { BODY, LATIN }

/** A font file in the app's fonts directory, imported by the user or downloaded from [FontCatalog]. */
data class InstalledFont(
    val fileName: String,
    val label: String,
    val format: FontFormat,
    val sizeBytes: Long,
    /** The [DownloadableFont.id] this file came from, or null for a user import. */
    val catalogId: String?,
) {
    val font: ReaderFont.Installed get() = ReaderFont.Installed(fileName)
}

sealed interface DownloadState {
    data class Running(val downloaded: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    data object Failed : DownloadState
}

data class ImportResult(val imported: List<InstalledFont>, val failed: Int)

/**
 * Owns the installed-fonts directory: import from the system picker, catalog downloads, delete,
 * and applying a font to [ReaderSettingsRepository]. File names are content hashes (see
 * [FontFileNames]); labels live in a small JSON index next to the directory.
 */
@Singleton
class FontRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ReaderSettingsRepository,
    private val client: OkHttpClient,
    private val json: Json,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val directory = ReaderFontFiles.directory(context)
    private val indexFile = File(context.filesDir, "fonts-index.json")
    private val mutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()

    private val _installed = MutableStateFlow<List<InstalledFont>>(emptyList())

    /** Installed fonts, sorted by label. */
    val installed: StateFlow<List<InstalledFont>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** Running or failed catalog downloads by [DownloadableFont.id]. Finished ones are removed. */
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    init {
        scope.launch(io) {
            mutex.withLock {
                // Leftovers from an import or download the process did not finish.
                directory.listFiles { file -> file.name.startsWith(".") }?.forEach { it.delete() }
                refreshLocked()
            }
        }
    }

    fun installedFile(fileName: String): File? =
        File(directory, fileName).takeIf { FontFileNames.isInstalledName(fileName) && it.isFile }

    suspend fun apply(target: FontTarget, font: ReaderFont?) {
        settings.update {
            when (target) {
                FontTarget.BODY -> it.copy(font = font ?: ReaderFont.Book)
                FontTarget.LATIN -> it.copy(latinFont = font?.takeUnless { f -> f == ReaderFont.Book })
            }
        }
    }

    suspend fun setFontWeight(weight: Int) {
        settings.update { it.copy(fontWeight = weight) }
    }

    /**
     * Copies picked files into the fonts directory. A single successful file is applied to
     * [applyTo] (when given), like picking it from the list.
     */
    suspend fun import(uris: List<Uri>, applyTo: FontTarget?): ImportResult = withContext(io) {
        val imported = mutableListOf<InstalledFont>()
        var failed = 0
        for (uri in uris) {
            val font = try {
                importOne(uri)
            } catch (e: IOException) {
                Log.w(TAG, "Font import failed", e)
                null
            } catch (e: MalformedFontException) {
                Log.w(TAG, "Not a usable font: ${e.message}")
                null
            } catch (e: SecurityException) {
                Log.w(TAG, "Font import denied", e)
                null
            }
            if (font == null) failed++ else imported += font
        }
        if (applyTo != null && uris.size == 1 && imported.size == 1) apply(applyTo, imported.single().font)
        ImportResult(imported, failed)
    }

    private suspend fun importOne(uri: Uri): InstalledFont? {
        val resolver = context.contentResolver
        val displayName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val input = resolver.openInputStream(uri) ?: return null
        val staged = input.use { stage(it, prefix = ".import-") }
        return install(staged, labelFallback = FontLabels.fromFileName(displayName), catalogId = null)
    }

    /** Starts (or restarts after a failure) a catalog download; a running one is left alone. */
    fun download(id: String, applyTo: FontTarget?) {
        val entry = FontCatalog.downloadable(id) ?: return
        if (jobs[id]?.isActive == true) return
        _downloads.update { it + (id to DownloadState.Running(0, entry.sizeBytes)) }
        jobs[id] = scope.launch(io) {
            val font = try {
                downloadOne(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Font download failed: ${entry.id}", e)
                null
            }
            if (font != null) {
                _downloads.update { it - id }
                if (applyTo != null) apply(applyTo, font.font)
            } else {
                _downloads.update { it + (id to DownloadState.Failed) }
            }
            jobs.remove(id)
        }.also { job -> job.invokeOnCompletion { cause -> if (cause != null) _downloads.update { it - id } } }
    }

    fun cancelDownload(id: String) {
        jobs.remove(id)?.cancel()
        calls.remove(id)?.cancel()
        _downloads.update { it - id }
    }

    private suspend fun downloadOne(entry: DownloadableFont): InstalledFont? {
        for (url in entry.urls) {
            currentCoroutineContext().ensureActive()
            val staged = try {
                fetch(entry, url)
            } catch (e: IOException) {
                Log.w(TAG, "Download from ${url.toHttpUrl().host} failed: ${e.message}")
                null
            } ?: continue
            if (staged.sha256 == entry.sha256 && staged.size == entry.sizeBytes) {
                return install(staged, labelFallback = entry.label, catalogId = entry.id, label = entry.label)
            }
            Log.w(TAG, "Checksum mismatch from ${url.toHttpUrl().host}")
            staged.file.delete()
        }
        return null
    }

    private suspend fun fetch(entry: DownloadableFont, url: String): Staged? {
        val call = client.newCall(Request.Builder().url(url).build())
        // cancelDownload() cancels the call directly: a stalled blocking read never reaches a
        // coroutine cancellation check.
        calls[entry.id] = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                return response.body.byteStream().use { body ->
                    stage(body, prefix = ".download-", limit = entry.sizeBytes) { copied ->
                        _downloads.update { it + (entry.id to DownloadState.Running(copied, entry.sizeBytes)) }
                    }
                }
            }
        } finally {
            calls.remove(entry.id, call)
        }
    }

    private class Staged(val file: File, val sha256: String, val size: Long)

    /** Streams [input] to a hidden temp file in the fonts directory while hashing it. */
    private suspend fun stage(
        input: InputStream,
        prefix: String,
        limit: Long = MAX_FONT_BYTES,
        onProgress: (Long) -> Unit = {},
    ): Staged {
        directory.mkdirs()
        val temp = File(directory, "$prefix${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var lastReport = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > limit) throw IOException("Font file too large")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    currentCoroutineContext().ensureActive()
                    if (size - lastReport >= PROGRESS_STEP) {
                        onProgress(size)
                        lastReport = size
                    }
                }
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        return Staged(temp, digest.digest().toHex(), size)
    }

    /** Validates a staged file and moves it to its content-hash name. */
    private suspend fun install(staged: Staged, labelFallback: String?, catalogId: String?, label: String? = null): InstalledFont? {
        val info = try {
            FontInspector.inspect(staged.file)
        } catch (e: Exception) {
            staged.file.delete()
            throw e
        }
        val name = FontFileNames.installedName(staged.sha256, info.format)
        return mutex.withLock {
            val target = File(directory, name)
            if (target.exists()) {
                staged.file.delete()
            } else if (!staged.file.renameTo(target)) {
                staged.file.delete()
                throw IOException("Could not store font")
            }
            val index = readIndex()
            val entry = index.fonts[name] ?: FontIndex.Entry(
                label = label ?: info.displayName ?: labelFallback ?: name,
                catalogId = catalogId,
                addedAt = System.currentTimeMillis(),
            )
            writeIndex(index.copy(fonts = index.fonts + (name to entry)))
            refreshLocked()
            _installed.value.firstOrNull { it.fileName == name }
        }
    }

    /** Deletes an installed font. Settings that use it fall back first, so the reader never asks for a missing file. */
    suspend fun delete(fileName: String) = withContext(io) {
        if (!FontFileNames.isInstalledName(fileName)) return@withContext
        val removed = ReaderFont.Installed(fileName)
        settings.update {
            it.copy(
                font = if (it.font == removed) ReaderFont.Book else it.font,
                latinFont = if (it.latinFont == removed) null else it.latinFont,
            )
        }
        mutex.withLock {
            File(directory, fileName).delete()
            val index = readIndex()
            writeIndex(index.copy(fonts = index.fonts - fileName))
            refreshLocked()
        }
    }

    private fun refreshLocked() {
        val index = readIndex()
        val files = directory.listFiles { file -> file.isFile && FontFileNames.isInstalledName(file.name) }.orEmpty()
        _installed.value = files.mapNotNull { file ->
            val format = FontFormat.fromExtension(file.extension) ?: return@mapNotNull null
            val entry = index.fonts[file.name]
            val catalogId = entry?.catalogId ?: FontCatalog.downloadable.firstOrNull { it.fileName == file.name }?.id
            val label = entry?.label
                ?: FontCatalog.downloadable(catalogId.orEmpty())?.label
                ?: runCatching { FontInspector.inspect(file).displayName }.getOrNull()
                ?: file.name
            InstalledFont(file.name, label, format, file.length(), catalogId)
        }.sortedBy { it.label.lowercase() }
    }

    private fun readIndex(): FontIndex = try {
        if (indexFile.isFile) json.decodeFromString(FontIndex.serializer(), indexFile.readText()) else FontIndex()
    } catch (e: Exception) {
        Log.w(TAG, "Font index unreadable, rebuilding", e)
        FontIndex()
    }

    private fun writeIndex(index: FontIndex) {
        val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
        temp.writeText(json.encodeToString(FontIndex.serializer(), index))
        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(temp.readText())
            temp.delete()
        }
    }

    private companion object {
        /** Large enough for full CJK families, small enough not to exhaust the renderer. */
        const val MAX_FONT_BYTES = 64L * 1024 * 1024
        const val PROGRESS_STEP = 256L * 1024
    }
}

@Serializable
internal data class FontIndex(val fonts: Map<String, Entry> = emptyMap()) {
    @Serializable
    data class Entry(val label: String, val catalogId: String? = null, val addedAt: Long = 0)
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

package com.zenread.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.zenread.core.common.ApplicationScope
import com.zenread.core.model.Book
import com.zenread.data.sync.engine.SyncEngine
import com.zenread.data.sync.engine.SyncLocalStore
import com.zenread.data.sync.engine.SyncOptions
import com.zenread.data.sync.engine.SyncPhase
import com.zenread.data.sync.engine.SyncProgress
import com.zenread.data.sync.engine.SyncReport
import com.zenread.data.sync.transport.SyncErrorKind
import com.zenread.data.sync.transport.SyncException
import com.zenread.data.sync.transport.SyncLog
import com.zenread.data.sync.transport.SyncTransport
import com.zenread.data.sync.transport.s3.S3Config
import com.zenread.data.sync.transport.s3.S3Transport
import com.zenread.data.sync.transport.webdav.WebDavConfig
import com.zenread.data.sync.transport.webdav.WebDavTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

enum class SyncTrigger {
    /** "Sync now" in settings. */
    MANUAL,

    /** WorkManager: periodic, after local changes, at app start. */
    AUTO,
}

sealed interface SyncOutcome {
    data class Success(val report: SyncReport) : SyncOutcome
    data class Failure(val error: SyncException) : SyncOutcome

    /** No backend chosen, or auto sync turned off. */
    data object Disabled : SyncOutcome

    /** Too soon after the last sync for a metered server; try again after [delayMillis]. */
    data class Deferred(val delayMillis: Long) : SyncOutcome
}

/**
 * Entry point for syncing: builds the transport from the saved settings and runs the engine.
 * A process-wide mutex guarantees that no two syncs (manual, periodic, debounced) overlap;
 * WorkManager runs workers in this process, so the lock covers them too.
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SyncSettingsRepository,
    private val store: SyncLocalStore,
    private val httpClient: OkHttpClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<SyncProgress?>(null)

    /** Non-null while a sync runs. */
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    @Volatile
    var lastFinishedAt: Long = 0L
        private set

    val isRunning: Boolean get() = mutex.isLocked

    /** Starts a sync in the application scope, so leaving the screen does not cancel it. */
    fun syncNow() {
        scope.launch { sync(SyncTrigger.MANUAL) }
    }

    suspend fun sync(trigger: SyncTrigger): SyncOutcome {
        val current = settings.current()
        if (current.backend == SyncBackend.NONE) return SyncOutcome.Disabled
        if (trigger == SyncTrigger.AUTO && !current.autoSync) return SyncOutcome.Disabled
        return mutex.withLock {
            val startedAt = System.currentTimeMillis()
            try {
                val transport = createTransport(current, settings.secrets())
                if (trigger == SyncTrigger.AUTO && transport.minAutoSyncIntervalMillis > 0) {
                    val last = settings.status.first().lastAttemptAt ?: 0L
                    val wait = last + transport.minAutoSyncIntervalMillis - startedAt
                    if (wait > 0) return@withLock SyncOutcome.Deferred(wait)
                }
                _progress.value = SyncProgress(SyncPhase.CONNECTING)
                val options = SyncOptions(
                    progress = current.syncProgress,
                    annotations = current.syncAnnotations,
                    uploadBooks = current.syncBookFiles && (!current.wifiOnlyForFiles || isUnmetered()),
                )
                val report = engine(transport).sync(options) { _progress.value = it }
                settings.recordAttempt(startedAt, null, null)
                SyncLog.i("Sync done: pulled ${report.pulled}, pushed ${report.pushed}, books ${report.uploadedBooks}")
                SyncOutcome.Success(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncException) {
                SyncLog.w("Sync failed", e)
                settings.recordAttempt(startedAt, e.kind, e.message)
                SyncOutcome.Failure(e)
            } catch (e: Exception) {
                SyncLog.w("Sync failed unexpectedly", e)
                settings.recordAttempt(startedAt, SyncErrorKind.UNKNOWN, e.javaClass.simpleName)
                SyncOutcome.Failure(SyncException.Protocol(e.javaClass.simpleName))
            } finally {
                _progress.value = null
                lastFinishedAt = System.currentTimeMillis()
            }
        }
    }

    /** Checks the given (possibly unsaved) settings. Null on success. */
    suspend fun testConnection(candidate: SyncSettings, secrets: SyncSecrets): SyncException? = try {
        if (candidate.backend == SyncBackend.NONE) throw SyncException.Config("Choose WebDAV or S3")
        engine(createTransport(candidate, secrets)).testConnection()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncException) {
        e
    } catch (e: Exception) {
        SyncException.Protocol(e.javaClass.simpleName)
    }

    /** Downloads a book that was synced from another device to [Book.filePath]. */
    suspend fun downloadBook(book: Book): Boolean {
        val current = settings.current()
        if (current.backend == SyncBackend.NONE) return false
        val target = File(book.filePath)
        target.parentFile?.mkdirs()
        return engine(createTransport(current, settings.secrets()))
            .downloadBook(book.fileHash, book.format.primaryExtension, target)
    }

    private suspend fun engine(transport: SyncTransport) =
        SyncEngine(transport, store, File(context.noBackupFilesDir, "sync"), settings.deviceId())

    private fun createTransport(settings: SyncSettings, secrets: SyncSecrets): SyncTransport = when (settings.backend) {
        SyncBackend.WEBDAV -> WebDavTransport(
            WebDavConfig(settings.webDavUrl, settings.webDavUsername, secrets.webDavPassword),
            httpClient,
        )
        SyncBackend.S3 -> S3Transport(
            S3Config(
                endpoint = settings.s3Endpoint,
                region = settings.s3Region.ifBlank { "us-east-1" },
                bucket = settings.s3Bucket,
                prefix = settings.s3Prefix,
                accessKey = settings.s3AccessKey,
                secretKey = secrets.s3SecretKey,
                pathStyle = settings.s3PathStyle,
            ),
            httpClient,
        )
        SyncBackend.NONE -> throw SyncException.Config("Sync is off")
    }

    private fun isUnmetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

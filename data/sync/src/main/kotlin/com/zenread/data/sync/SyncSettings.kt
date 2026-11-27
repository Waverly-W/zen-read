package com.zenread.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zenread.core.datastore.SecretStore
import com.zenread.data.sync.transport.SyncErrorKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class SyncBackend { NONE, WEBDAV, S3 }

/** Non-secret sync settings. Passwords and secret keys live in [SecretStore]. */
data class SyncSettings(
    val backend: SyncBackend = SyncBackend.NONE,
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val s3Endpoint: String = "",
    val s3Region: String = "us-east-1",
    val s3Bucket: String = "",
    val s3Prefix: String = "",
    val s3AccessKey: String = "",
    val s3PathStyle: Boolean = true,
    val autoSync: Boolean = true,
    val syncProgress: Boolean = true,
    val syncAnnotations: Boolean = true,
    val syncBookFiles: Boolean = true,
    val wifiOnlyForFiles: Boolean = true,
)

data class SyncSecrets(val webDavPassword: String = "", val s3SecretKey: String = "") {
    override fun toString() = "SyncSecrets(<redacted>)"
}

/** Outcome of the most recent sync attempts, persisted across restarts. */
data class SyncStatus(
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    /** Set when the most recent attempt failed. */
    val lastError: SyncErrorKind? = null,
    /** Short technical detail (never credentials). */
    val lastErrorDetail: String? = null,
)

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_settings")

@Singleton
class SyncSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore,
) {
    private val store = context.syncDataStore

    val settings: Flow<SyncSettings> = store.data.map { it.toSettings() }

    val status: Flow<SyncStatus> = store.data.map { prefs ->
        SyncStatus(
            lastSuccessAt = prefs[LAST_SUCCESS],
            lastAttemptAt = prefs[LAST_ATTEMPT],
            lastError = prefs[LAST_ERROR]?.let { name -> SyncErrorKind.entries.firstOrNull { it.name == name } },
            lastErrorDetail = prefs[LAST_ERROR_DETAIL],
        )
    }

    suspend fun current(): SyncSettings = settings.first()

    suspend fun secrets(): SyncSecrets = SyncSecrets(
        webDavPassword = secrets.get(WEBDAV_PASSWORD).orEmpty(),
        s3SecretKey = secrets.get(S3_SECRET_KEY).orEmpty(),
    )

    /** Saves connection settings and credentials together. */
    suspend fun save(settings: SyncSettings, credentials: SyncSecrets) {
        secrets.set(WEBDAV_PASSWORD, credentials.webDavPassword)
        secrets.set(S3_SECRET_KEY, credentials.s3SecretKey)
        update { settings }
    }

    suspend fun update(transform: (SyncSettings) -> SyncSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[BACKEND] = next.backend.name
            prefs[WEBDAV_URL] = next.webDavUrl.trim()
            prefs[WEBDAV_USER] = next.webDavUsername
            prefs[S3_ENDPOINT] = next.s3Endpoint.trim()
            prefs[S3_REGION] = next.s3Region.trim()
            prefs[S3_BUCKET] = next.s3Bucket.trim()
            prefs[S3_PREFIX] = next.s3Prefix.trim()
            prefs[S3_ACCESS_KEY] = next.s3AccessKey.trim()
            prefs[S3_PATH_STYLE] = next.s3PathStyle
            prefs[AUTO_SYNC] = next.autoSync
            prefs[SYNC_PROGRESS] = next.syncProgress
            prefs[SYNC_ANNOTATIONS] = next.syncAnnotations
            prefs[SYNC_BOOK_FILES] = next.syncBookFiles
            prefs[WIFI_ONLY] = next.wifiOnlyForFiles
        }
    }

    /** Random id of this installation; breaks ties between equal timestamps. */
    suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        var id = ""
        store.edit { prefs -> id = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it } }
        return id
    }

    suspend fun recordAttempt(at: Long, error: SyncErrorKind?, detail: String?) {
        store.edit { prefs ->
            prefs[LAST_ATTEMPT] = at
            if (error == null) {
                prefs[LAST_SUCCESS] = at
                prefs.remove(LAST_ERROR)
                prefs.remove(LAST_ERROR_DETAIL)
            } else {
                prefs[LAST_ERROR] = error.name
                if (detail != null) prefs[LAST_ERROR_DETAIL] = detail.take(300) else prefs.remove(LAST_ERROR_DETAIL)
            }
        }
    }

    private fun Preferences.toSettings(): SyncSettings {
        val defaults = SyncSettings()
        return SyncSettings(
            backend = this[BACKEND]?.let { name -> SyncBackend.entries.firstOrNull { it.name == name } } ?: defaults.backend,
            webDavUrl = this[WEBDAV_URL] ?: defaults.webDavUrl,
            webDavUsername = this[WEBDAV_USER] ?: defaults.webDavUsername,
            s3Endpoint = this[S3_ENDPOINT] ?: defaults.s3Endpoint,
            s3Region = this[S3_REGION] ?: defaults.s3Region,
            s3Bucket = this[S3_BUCKET] ?: defaults.s3Bucket,
            s3Prefix = this[S3_PREFIX] ?: defaults.s3Prefix,
            s3AccessKey = this[S3_ACCESS_KEY] ?: defaults.s3AccessKey,
            s3PathStyle = this[S3_PATH_STYLE] ?: defaults.s3PathStyle,
            autoSync = this[AUTO_SYNC] ?: defaults.autoSync,
            syncProgress = this[SYNC_PROGRESS] ?: defaults.syncProgress,
            syncAnnotations = this[SYNC_ANNOTATIONS] ?: defaults.syncAnnotations,
            syncBookFiles = this[SYNC_BOOK_FILES] ?: defaults.syncBookFiles,
            wifiOnlyForFiles = this[WIFI_ONLY] ?: defaults.wifiOnlyForFiles,
        )
    }

    private companion object {
        const val WEBDAV_PASSWORD = "sync.webdav.password"
        const val S3_SECRET_KEY = "sync.s3.secretKey"

        val BACKEND = stringPreferencesKey("backend")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val S3_ENDPOINT = stringPreferencesKey("s3_endpoint")
        val S3_REGION = stringPreferencesKey("s3_region")
        val S3_BUCKET = stringPreferencesKey("s3_bucket")
        val S3_PREFIX = stringPreferencesKey("s3_prefix")
        val S3_ACCESS_KEY = stringPreferencesKey("s3_access_key")
        val S3_PATH_STYLE = booleanPreferencesKey("s3_path_style")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val SYNC_PROGRESS = booleanPreferencesKey("sync_progress")
        val SYNC_ANNOTATIONS = booleanPreferencesKey("sync_annotations")
        val SYNC_BOOK_FILES = booleanPreferencesKey("sync_book_files")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_for_files")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val LAST_SUCCESS = longPreferencesKey("last_success_at")
        val LAST_ATTEMPT = longPreferencesKey("last_attempt_at")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val LAST_ERROR_DETAIL = stringPreferencesKey("last_error_detail")
    }
}

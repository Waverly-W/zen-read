package com.zenread.feature.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.data.sync.SyncBackend
import com.zenread.data.sync.SyncManager
import com.zenread.data.sync.SyncSecrets
import com.zenread.data.sync.SyncSettings
import com.zenread.data.sync.SyncSettingsRepository
import com.zenread.data.sync.SyncStatus
import com.zenread.data.sync.engine.SyncProgress
import com.zenread.data.sync.transport.SyncErrorKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The editable connection settings, credentials included. */
data class ConnectionForm(
    val backend: SyncBackend = SyncBackend.NONE,
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val s3Endpoint: String = "",
    val s3Region: String = "us-east-1",
    val s3Bucket: String = "",
    val s3Prefix: String = "",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    val s3PathStyle: Boolean = true,
) {
    /** Required fields are filled in for the chosen backend. */
    val complete: Boolean
        get() = when (backend) {
            SyncBackend.NONE -> true
            SyncBackend.WEBDAV -> webDavUrl.isNotBlank()
            SyncBackend.S3 -> s3Endpoint.isNotBlank() && s3Bucket.isNotBlank() && s3AccessKey.isNotBlank() && s3SecretKey.isNotBlank()
        }

    /** The address is plain http (credentials and data travel unencrypted). */
    val insecure: Boolean
        get() = when (backend) {
            SyncBackend.WEBDAV -> webDavUrl.trim().startsWith("http://", ignoreCase = true)
            SyncBackend.S3 -> s3Endpoint.trim().startsWith("http://", ignoreCase = true)
            SyncBackend.NONE -> false
        }

    override fun toString() = "ConnectionForm(backend=$backend, <fields redacted>)"
}

sealed interface ConnectionTest {
    data object Running : ConnectionTest
    data object Passed : ConnectionTest
    data class Failed(val kind: SyncErrorKind, val detail: String?) : ConnectionTest
}

data class SyncSettingsUiState(
    val loaded: Boolean = false,
    val form: ConnectionForm = ConnectionForm(),
    /** The form as last saved; differs from [form] while there are unsaved edits. */
    val saved: ConnectionForm = ConnectionForm(),
    val test: ConnectionTest? = null,
) {
    val dirty: Boolean get() = form != saved
}

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val repository: SyncSettingsRepository,
    private val manager: SyncManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SyncSettingsUiState())
    val state: StateFlow<SyncSettingsUiState> = _state.asStateFlow()

    /** Toggles (auto sync, what to sync); saved as soon as they change. */
    val settings: StateFlow<SyncSettings?> = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val status: StateFlow<SyncStatus> = repository.status.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStatus())
    val progress: StateFlow<SyncProgress?> = manager.progress

    init {
        viewModelScope.launch {
            val settings = repository.current()
            val secrets = repository.secrets()
            val form = ConnectionForm(
                backend = settings.backend,
                webDavUrl = settings.webDavUrl,
                webDavUsername = settings.webDavUsername,
                webDavPassword = secrets.webDavPassword,
                s3Endpoint = settings.s3Endpoint,
                s3Region = settings.s3Region,
                s3Bucket = settings.s3Bucket,
                s3Prefix = settings.s3Prefix,
                s3AccessKey = settings.s3AccessKey,
                s3SecretKey = secrets.s3SecretKey,
                s3PathStyle = settings.s3PathStyle,
            )
            _state.update { it.copy(loaded = true, form = form, saved = form) }
        }
    }

    fun edit(transform: (ConnectionForm) -> ConnectionForm) =
        _state.update { it.copy(form = transform(it.form), test = null) }

    /** Turning sync off takes effect at once; choosing a service waits for Save. */
    fun selectBackend(backend: SyncBackend) {
        edit { it.copy(backend = backend) }
        if (backend == SyncBackend.NONE) save()
    }

    fun save() {
        val form = _state.value.form
        viewModelScope.launch {
            repository.save(
                repository.current().withConnection(form),
                SyncSecrets(webDavPassword = form.webDavPassword, s3SecretKey = form.s3SecretKey),
            )
            _state.update { it.copy(saved = form) }
        }
    }

    fun testConnection() {
        val form = _state.value.form
        _state.update { it.copy(test = ConnectionTest.Running) }
        viewModelScope.launch {
            val error = manager.testConnection(
                repository.current().withConnection(form),
                SyncSecrets(webDavPassword = form.webDavPassword, s3SecretKey = form.s3SecretKey),
            )
            _state.update {
                it.copy(test = if (error == null) ConnectionTest.Passed else ConnectionTest.Failed(error.kind, error.message))
            }
        }
    }

    fun syncNow() = manager.syncNow()

    fun updateOptions(transform: (SyncSettings) -> SyncSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    private fun SyncSettings.withConnection(form: ConnectionForm) = copy(
        backend = form.backend,
        webDavUrl = form.webDavUrl.trim(),
        webDavUsername = form.webDavUsername,
        s3Endpoint = form.s3Endpoint.trim(),
        s3Region = form.s3Region.trim(),
        s3Bucket = form.s3Bucket.trim(),
        s3Prefix = form.s3Prefix.trim(),
        s3AccessKey = form.s3AccessKey.trim(),
        s3PathStyle = form.s3PathStyle,
    )
}

package com.zenread.feature.settings.sync

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenread.data.sync.SyncBackend
import com.zenread.data.sync.SyncSettings
import com.zenread.data.sync.SyncStatus
import com.zenread.data.sync.engine.SyncPhase
import com.zenread.data.sync.engine.SyncProgress
import com.zenread.data.sync.transport.SyncErrorKind
import com.zenread.feature.settings.R

@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_sync)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        val options = settings
        if (!state.loaded || options == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Scaffold
        }
        val form = state.form
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BackendSelector(form.backend, viewModel::selectBackend) }
            if (form.backend == SyncBackend.NONE) {
                item {
                    Text(
                        stringResource(R.string.sync_off_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                return@LazyColumn
            }
            item { SectionTitle(R.string.sync_section_connection) }
            item {
                Group {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (form.backend == SyncBackend.WEBDAV) WebDavFields(form, viewModel::edit) else S3Fields(form, viewModel::edit)
                        if (form.insecure) Notice(Icons.Rounded.Warning, stringResource(R.string.sync_insecure_warning), MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = viewModel::save, enabled = state.dirty && form.complete) {
                                Text(stringResource(R.string.sync_save))
                            }
                            OutlinedButton(
                                onClick = viewModel::testConnection,
                                enabled = form.complete && state.test != ConnectionTest.Running,
                            ) {
                                Text(stringResource(if (state.test == ConnectionTest.Running) R.string.sync_testing else R.string.sync_test))
                            }
                        }
                        if (state.dirty) {
                            Text(
                                stringResource(R.string.sync_unsaved),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (val test = state.test) {
                            ConnectionTest.Passed ->
                                Notice(Icons.Rounded.CheckCircle, stringResource(R.string.sync_test_ok), MaterialTheme.colorScheme.primary)
                            is ConnectionTest.Failed -> Notice(
                                Icons.Rounded.ErrorOutline,
                                stringResource(R.string.sync_test_failed, stringResource(test.kind.messageRes())),
                                MaterialTheme.colorScheme.error,
                                detail = test.detail,
                            )
                            else -> Unit
                        }
                    }
                }
            }
            item { SectionTitle(R.string.sync_section_status) }
            item {
                StatusGroup(
                    status = status,
                    progress = progress,
                    canSync = state.saved.backend != SyncBackend.NONE && !state.dirty,
                    onSyncNow = viewModel::syncNow,
                )
            }
            item { SectionTitle(R.string.sync_section_options) }
            item { OptionsGroup(options, viewModel::updateOptions) }
            item {
                Text(
                    stringResource(R.string.sync_layout_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BackendSelector(selected: SyncBackend, onSelect: (SyncBackend) -> Unit) {
    val choices = listOf(
        SyncBackend.NONE to R.string.sync_backend_off,
        SyncBackend.WEBDAV to R.string.sync_backend_webdav,
        SyncBackend.S3 to R.string.sync_backend_s3,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (backend, label) ->
            SegmentedButton(
                selected = backend == selected,
                onClick = { onSelect(backend) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun WebDavFields(form: ConnectionForm, onEdit: ((ConnectionForm) -> ConnectionForm) -> Unit) {
    Field(
        value = form.webDavUrl,
        label = R.string.sync_webdav_url,
        supporting = R.string.sync_webdav_url_hint,
        keyboardType = KeyboardType.Uri,
    ) { value -> onEdit { it.copy(webDavUrl = value) } }
    Field(form.webDavUsername, R.string.sync_username) { value -> onEdit { it.copy(webDavUsername = value) } }
    SecretField(
        value = form.webDavPassword,
        label = R.string.sync_password,
        supporting = if (form.webDavUrl.contains("jianguoyun", ignoreCase = true)) R.string.sync_password_hint_jianguoyun else null,
    ) { value -> onEdit { it.copy(webDavPassword = value) } }
}

@Composable
private fun S3Fields(form: ConnectionForm, onEdit: ((ConnectionForm) -> ConnectionForm) -> Unit) {
    Field(form.s3Endpoint, R.string.sync_s3_endpoint, R.string.sync_s3_endpoint_hint, KeyboardType.Uri) { value ->
        onEdit { it.copy(s3Endpoint = value) }
    }
    Field(form.s3Region, R.string.sync_s3_region, R.string.sync_s3_region_hint) { value -> onEdit { it.copy(s3Region = value) } }
    Field(form.s3Bucket, R.string.sync_s3_bucket) { value -> onEdit { it.copy(s3Bucket = value) } }
    Field(form.s3Prefix, R.string.sync_s3_prefix) { value -> onEdit { it.copy(s3Prefix = value) } }
    Field(form.s3AccessKey, R.string.sync_s3_access_key) { value -> onEdit { it.copy(s3AccessKey = value) } }
    SecretField(form.s3SecretKey, R.string.sync_s3_secret_key) { value -> onEdit { it.copy(s3SecretKey = value) } }
    ListItem(
        headlineContent = { Text(stringResource(R.string.sync_s3_path_style)) },
        supportingContent = { Text(stringResource(R.string.sync_s3_path_style_summary)) },
        trailingContent = {
            Switch(checked = form.s3PathStyle, onCheckedChange = { checked -> onEdit { it.copy(s3PathStyle = checked) } })
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun Field(
    value: String,
    @StringRes label: Int,
    @StringRes supporting: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretField(
    value: String,
    @StringRes label: Int,
    @StringRes supporting: Int? = null,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    stringResource(if (visible) R.string.sync_hide_password else R.string.sync_show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatusGroup(status: SyncStatus, progress: SyncProgress?, canSync: Boolean, onSyncNow: () -> Unit) {
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (progress != null) {
                Text(stringResource(R.string.sync_running, stringResource(progress.phase.labelRes())))
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            } else {
                val last = status.lastSuccessAt
                Text(
                    if (last == null) {
                        stringResource(R.string.sync_never)
                    } else {
                        stringResource(
                            R.string.sync_last_success,
                            DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        )
                    },
                )
                status.lastError?.let { kind ->
                    Notice(
                        Icons.Rounded.ErrorOutline,
                        stringResource(R.string.sync_last_error, stringResource(kind.messageRes())),
                        MaterialTheme.colorScheme.error,
                        detail = status.lastErrorDetail,
                    )
                }
            }
            Button(
                onClick = onSyncNow,
                enabled = canSync && progress == null,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.sync_now))
            }
            if (!canSync) {
                Text(
                    stringResource(R.string.sync_save_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OptionsGroup(settings: SyncSettings, onChange: ((SyncSettings) -> SyncSettings) -> Unit) {
    Group {
        Column(Modifier.padding(vertical = 4.dp)) {
            Toggle(R.string.sync_auto, R.string.sync_auto_summary, settings.autoSync) { on -> onChange { it.copy(autoSync = on) } }
            Toggle(R.string.sync_progress, null, settings.syncProgress) { on -> onChange { it.copy(syncProgress = on) } }
            Toggle(R.string.sync_annotations, null, settings.syncAnnotations) { on -> onChange { it.copy(syncAnnotations = on) } }
            Toggle(R.string.sync_book_files, R.string.sync_book_files_summary, settings.syncBookFiles) { on ->
                onChange { it.copy(syncBookFiles = on) }
            }
            Toggle(R.string.sync_wifi_only, null, settings.wifiOnlyForFiles, enabled = settings.syncBookFiles) { on ->
                onChange { it.copy(wifiOnlyForFiles = on) }
            }
        }
    }
}

@Composable
private fun Toggle(
    @StringRes title: Int,
    @StringRes summary: Int?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun Notice(icon: ImageVector, text: String, tint: Color, detail: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = tint)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

private fun SyncPhase.labelRes(): Int = when (this) {
    SyncPhase.CONNECTING -> R.string.sync_phase_connecting
    SyncPhase.READING -> R.string.sync_phase_reading
    SyncPhase.APPLYING -> R.string.sync_phase_applying
    SyncPhase.PUBLISHING -> R.string.sync_phase_publishing
    SyncPhase.COVERS -> R.string.sync_phase_covers
    SyncPhase.BOOKS -> R.string.sync_phase_books
    SyncPhase.COMPACTING -> R.string.sync_phase_compacting
}

internal fun SyncErrorKind.messageRes(): Int = when (this) {
    SyncErrorKind.AUTH -> R.string.sync_error_auth
    SyncErrorKind.FORBIDDEN -> R.string.sync_error_forbidden
    SyncErrorKind.NOT_FOUND -> R.string.sync_error_not_found
    SyncErrorKind.RATE_LIMITED -> R.string.sync_error_rate_limited
    SyncErrorKind.NETWORK -> R.string.sync_error_network
    SyncErrorKind.SERVER -> R.string.sync_error_server
    SyncErrorKind.STORAGE_FULL -> R.string.sync_error_storage_full
    SyncErrorKind.PROTOCOL -> R.string.sync_error_protocol
    SyncErrorKind.INCOMPATIBLE -> R.string.sync_error_incompatible
    SyncErrorKind.CONFIG -> R.string.sync_error_config
    SyncErrorKind.CLOCK_SKEW -> R.string.sync_error_clock
    SyncErrorKind.UNKNOWN -> R.string.sync_error_unknown
}

package com.zenread.feature.settings.tts

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenread.core.designsystem.component.ZenSlider
import com.zenread.data.tts.OpenAiTtsRequest
import com.zenread.data.tts.SleepTimerOption
import com.zenread.data.tts.TtsEngineType
import com.zenread.data.tts.TtsSettings
import com.zenread.data.tts.TtsVoice
import com.zenread.feature.settings.R
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Settings › Read aloud: engine, voice, speed, pitch, OpenAI-compatible service, sleep timer. */
@Composable
fun TtsSettingsScreen(onBack: () -> Unit, viewModel: TtsSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    var pickingVoice by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_tts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val current = settings ?: return@Scaffold
        LaunchedEffect(current.engine) { viewModel.loadVoices(current.engine) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Group(stringResource(R.string.tts_settings_engine)) {
                    EngineSelector(current.engine) { engine -> viewModel.update { it.copy(engine = engine) } }
                    Text(
                        text = stringResource(
                            when (current.engine) {
                                TtsEngineType.SYSTEM -> R.string.tts_settings_engine_system_summary
                                TtsEngineType.EDGE -> R.string.tts_settings_engine_edge_summary
                                TtsEngineType.OPENAI -> R.string.tts_settings_engine_openai_summary
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    if (current.engine == TtsEngineType.SYSTEM) SystemTtsSettingsLink()
                }
            }
            item {
                Group(stringResource(R.string.tts_settings_voice)) {
                    ListItem(
                        onClick = {
                            viewModel.loadVoices(current.engine)
                            pickingVoice = true
                        },
                        supportingContent = { Text(stringResource(R.string.tts_settings_voice_change)) },
                        leadingContent = { Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) { Text(voiceLabel(current, voices.voices)) }
                    PreviewButton(
                        playing = preview == PreviewState.PLAYING,
                        failed = preview == PreviewState.FAILED,
                        onPreview = viewModel::preview,
                        onStop = viewModel::stopPreview,
                    )
                }
            }
            item {
                Group(stringResource(R.string.tts_settings_speech)) {
                    ValueSlider(
                        label = stringResource(R.string.tts_settings_rate),
                        value = current.rate,
                        range = TtsSettings.RATE_RANGE,
                        steps = 24,
                        onChange = { rate -> viewModel.update { it.copy(rate = rate) } },
                    )
                    val pitchSupported = TtsSettings.supportsPitch(current.engine, current.openAiModel)
                    ValueSlider(
                        label = stringResource(R.string.tts_settings_pitch),
                        value = current.pitch,
                        range = TtsSettings.PITCH_RANGE,
                        steps = 14,
                        enabled = pitchSupported,
                        onChange = { pitch -> viewModel.update { it.copy(pitch = pitch) } },
                    )
                    if (!pitchSupported) {
                        Hint(stringResource(R.string.tts_settings_pitch_unsupported))
                    }
                }
            }
            if (current.engine == TtsEngineType.OPENAI) {
                item {
                    Group(stringResource(R.string.tts_settings_openai)) {
                        OpenAiFields(
                            settings = current,
                            hasKey = hasKey,
                            onUpdate = viewModel::update,
                            onSaveKey = viewModel::saveKey,
                            onClearKey = viewModel::clearKey,
                        )
                    }
                }
            }
            item {
                Group(stringResource(R.string.tts_settings_sleep_timer)) {
                    Hint(stringResource(R.string.tts_settings_sleep_timer_summary))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        SleepTimerOption.choices.forEach { minutes ->
                            FilterChip(
                                selected = current.sleepTimerMinutes == minutes,
                                onClick = { viewModel.update { it.copy(sleepTimerMinutes = minutes) } },
                                label = { Text(sleepChoiceLabel(minutes)) },
                            )
                        }
                    }
                }
            }
        }

        if (pickingVoice) {
            VoicePickerSheet(
                engine = current.engine,
                selected = current.voice,
                state = voices,
                onRetry = { viewModel.loadVoices(current.engine, force = true) },
                onSelect = { id ->
                    pickingVoice = false
                    viewModel.update {
                        when (it.engine) {
                            TtsEngineType.SYSTEM -> it.copy(systemVoice = id)
                            TtsEngineType.EDGE -> it.copy(edgeVoice = id ?: TtsSettings.DEFAULT_EDGE_VOICE)
                            TtsEngineType.OPENAI -> it.copy(openAiVoice = id ?: TtsSettings.DEFAULT_OPENAI_VOICE)
                        }
                    }
                },
                onDismiss = { pickingVoice = false },
            )
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(vertical = 8.dp), content = content)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun EngineSelector(selected: TtsEngineType, onSelect: (TtsEngineType) -> Unit) {
    val engines = TtsEngineType.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        engines.forEachIndexed { index, engine ->
            SegmentedButton(
                selected = engine == selected,
                onClick = { onSelect(engine) },
                shape = SegmentedButtonDefaults.itemShape(index, engines.size),
                label = { Text(engineLabel(engine), maxLines = 1) },
            )
        }
    }
}

@Composable
internal fun engineLabel(engine: TtsEngineType): String = stringResource(
    when (engine) {
        TtsEngineType.SYSTEM -> R.string.tts_settings_engine_system
        TtsEngineType.EDGE -> R.string.tts_settings_engine_edge
        TtsEngineType.OPENAI -> R.string.tts_settings_engine_openai
    },
)

@Composable
private fun SystemTtsSettingsLink() {
    val context = LocalContext.current
    ListItem(
        onClick = {
            try {
                context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // Some ROMs hide the screen; nothing else to offer.
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Text(stringResource(R.string.tts_settings_system_open)) }
}

@Composable
private fun PreviewButton(playing: Boolean, failed: Boolean, onPreview: (String) -> Unit, onStop: () -> Unit) {
    val sample = stringResource(R.string.tts_settings_preview_sample)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        if (playing) {
            OutlinedButton(onClick = onStop) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tts_settings_preview_stop))
            }
        } else {
            OutlinedButton(onClick = { onPreview(sample) }) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tts_settings_preview))
            }
        }
        if (failed) {
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.tts_settings_preview_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                String.format(Locale.ROOT, "%.1f×", shown),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (enabled) {
            ZenSlider(
                value = value,
                onValueChange = { dragging = (it * 10).roundToInt() / 10f },
                valueRange = range,
                steps = steps,
                onValueChangeFinished = {
                    dragging?.let(onChange)
                    dragging = null
                },
            )
        }
    }
}

@Composable
private fun OpenAiFields(
    settings: TtsSettings,
    hasKey: Boolean,
    onUpdate: ((TtsSettings) -> TtsSettings) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AutoSaveField(
            label = stringResource(R.string.tts_settings_openai_base_url),
            initial = settings.openAiBaseUrl,
            keyboardType = KeyboardType.Uri,
            supporting = stringResource(R.string.tts_settings_openai_base_url_hint),
            onCommit = { url -> onUpdate { it.copy(openAiBaseUrl = url.trim().ifEmpty { TtsSettings.DEFAULT_OPENAI_BASE_URL }) } },
        )
        AutoSaveField(
            label = stringResource(R.string.tts_settings_openai_model),
            initial = settings.openAiModel,
            onCommit = { model -> onUpdate { it.copy(openAiModel = model.trim().ifEmpty { TtsSettings.DEFAULT_OPENAI_MODEL }) } },
        )
        AutoSaveField(
            label = stringResource(R.string.tts_settings_openai_voice),
            initial = settings.openAiVoice,
            supporting = OpenAiTtsRequest.builtInVoices.joinToString(", "),
            onCommit = { voice -> onUpdate { it.copy(openAiVoice = voice.trim().ifEmpty { TtsSettings.DEFAULT_OPENAI_VOICE }) } },
        )
        ApiKeyField(hasKey = hasKey, onSave = onSaveKey, onClear = onClearKey)
    }
}

/** Saves shortly after the user stops typing; the stored value never overwrites typing. */
@Composable
private fun AutoSaveField(
    label: String,
    initial: String,
    onCommit: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
) {
    var text by rememberSaveable(label) { mutableStateOf(initial) }
    LaunchedEffect(text) {
        if (text == initial) return@LaunchedEffect
        delay(500)
        onCommit(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supporting?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ApiKeyField(hasKey: Boolean, onSave: (String) -> Unit, onClear: () -> Unit) {
    val key = rememberTextFieldState()
    OutlinedSecureTextField(
        state = key,
        label = { Text(stringResource(R.string.tts_settings_openai_key)) },
        placeholder = { if (hasKey) Text(stringResource(R.string.tts_settings_openai_key_saved)) },
        leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
        supportingText = { Text(stringResource(R.string.tts_settings_openai_key_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onSave(key.text.toString())
                key.clearText()
            },
            enabled = key.text.isNotBlank(),
        ) { Text(stringResource(R.string.tts_settings_openai_key_save)) }
        if (hasKey) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.tts_settings_openai_key_remove)) }
        }
    }
}

@Composable
private fun VoicePickerSheet(
    engine: TtsEngineType,
    selected: String?,
    state: VoiceListState,
    onRetry: () -> Unit,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.tts_settings_voice),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.tts_settings_voice_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            )
            when {
                state.engine != engine || state.loading -> Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                ) { LoadingIndicator() }
                state.failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                ) {
                    Text(stringResource(R.string.tts_settings_voice_failed))
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.tts_settings_retry)) }
                }
                else -> {
                    val filtered = remember(state.voices, query) { filterVoices(state.voices, query) }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (engine == TtsEngineType.SYSTEM && query.isBlank()) {
                            item {
                                VoiceRow(
                                    title = stringResource(R.string.tts_settings_voice_default),
                                    subtitle = null,
                                    selected = selected == null,
                                    onClick = { onSelect(null) },
                                )
                            }
                        }
                        items(filtered, key = { it.id }) { voice ->
                            VoiceRow(
                                title = voice.name.ifBlank { voice.id },
                                subtitle = voiceSubtitle(voice),
                                selected = voice.id == selected,
                                onClick = { onSelect(voice.id) },
                            )
                        }
                        if (filtered.isEmpty()) {
                            item { Hint(stringResource(R.string.tts_settings_voice_empty)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text(title) }
}

@Composable
private fun voiceSubtitle(voice: TtsVoice): String = buildList {
    add(voice.id)
    if (voice.locale.isNotBlank()) add(voice.locale)
    voice.gender?.takeIf { it.isNotBlank() }?.let(::add)
    if (voice.requiresNetwork) add(stringResource(R.string.tts_settings_voice_online))
}.distinct().joinToString(" · ")

internal fun filterVoices(voices: List<TtsVoice>, query: String): List<TtsVoice> {
    val q = query.trim()
    if (q.isEmpty()) return voices
    return voices.filter {
        it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) || it.locale.contains(q, ignoreCase = true)
    }
}

@Composable
private fun voiceLabel(settings: TtsSettings, voices: List<TtsVoice>): String {
    val id = settings.voice ?: return stringResource(R.string.tts_settings_voice_default)
    return voices.firstOrNull { it.id == id }?.let { "${it.name} ($id)" } ?: id
}

@Composable
private fun sleepChoiceLabel(minutes: Int): String = when (minutes) {
    SleepTimerOption.OFF -> stringResource(R.string.tts_settings_sleep_off)
    SleepTimerOption.END_OF_CHAPTER -> stringResource(R.string.tts_settings_sleep_end_of_chapter)
    else -> stringResource(R.string.tts_settings_sleep_minutes, minutes)
}

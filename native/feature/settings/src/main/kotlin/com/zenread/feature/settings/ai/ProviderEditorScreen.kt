package com.zenread.feature.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zenread.core.designsystem.component.ZenSlider
import com.zenread.data.ai.chat.ChatException
import com.zenread.data.ai.provider.ModelParams
import com.zenread.data.ai.provider.ProviderKind
import com.zenread.feature.settings.R
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderEditorScreen(state: ProviderEditorState, viewModel: AiSettingsViewModel) {
    val draft = state.draft
    var paramsFor by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.ai_provider_new else R.string.ai_provider_edit)) },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeEditor) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.ai_provider_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::saveEditor, enabled = draft.baseUrl.isNotBlank()) {
                        Text(stringResource(R.string.ai_provider_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { name -> viewModel.updateDraft { it.copy(name = name) } },
                label = { Text(stringResource(R.string.ai_provider_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.ai_provider_protocol), style = MaterialTheme.typography.labelLarge)
            val kinds = ProviderKind.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, kind ->
                    SegmentedButton(
                        selected = draft.kind == kind,
                        onClick = { viewModel.updateDraft { it.copy(kind = kind) } },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    ) {
                        Text(
                            stringResource(
                                when (kind) {
                                    ProviderKind.OPENAI_COMPATIBLE -> R.string.ai_provider_openai
                                    ProviderKind.ANTHROPIC -> R.string.ai_provider_anthropic
                                    ProviderKind.GEMINI -> R.string.ai_provider_gemini
                                },
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { url -> viewModel.updateDraft { it.copy(baseUrl = url) } },
                label = { Text(stringResource(R.string.ai_provider_base_url)) },
                supportingText = {
                    Text(
                        stringResource(
                            when (draft.kind) {
                                ProviderKind.OPENAI_COMPATIBLE -> R.string.ai_provider_base_url_help_openai
                                ProviderKind.ANTHROPIC -> R.string.ai_provider_base_url_help_anthropic
                                ProviderKind.GEMINI -> R.string.ai_provider_base_url_help_gemini
                            },
                        ),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            // Never pre-filled: a saved key stays in SecretStore and is only replaced.
            val keyField = rememberTextFieldState()
            LaunchedEffect(keyField) {
                snapshotFlow { keyField.text.toString() }.collect { viewModel.setKeyInput(it) }
            }
            OutlinedSecureTextField(
                state = keyField,
                label = { Text(stringResource(R.string.ai_provider_api_key)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (state.hasSavedKey && !state.clearKey) R.string.ai_provider_api_key_saved else R.string.ai_provider_api_key_help,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.hasSavedKey && !state.clearKey) {
                TextButton(onClick = {
                    keyField.clearText()
                    viewModel.clearSavedKey()
                }) { Text(stringResource(R.string.ai_provider_clear_key)) }
            }

            SwitchRow(stringResource(R.string.ai_provider_enabled), draft.enabled) { checked ->
                viewModel.updateDraft { it.copy(enabled = checked) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = viewModel::fetchModels, enabled = !state.busy) {
                    Text(stringResource(R.string.ai_provider_fetch_models))
                }
                OutlinedButton(onClick = viewModel::testConnection, enabled = !state.busy) {
                    Text(stringResource(R.string.ai_provider_test))
                }
                if (state.busy) LoadingIndicator(Modifier.size(32.dp))
            }
            state.message?.let { EditorMessageLine(it) }

            SectionTitle(stringResource(R.string.ai_provider_chat_models))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.models.forEach { model ->
                    val custom = draft.modelParams[model] != null
                    InputChip(
                        selected = custom,
                        onClick = { paramsFor = model },
                        label = { Text(model) },
                        leadingIcon = if (custom) ({ Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) }) else null,
                        trailingIcon = {
                            IconButton(onClick = { viewModel.removeModel(model, embedding = false) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.ai_provider_remove_model, model), Modifier.size(16.dp))
                            }
                        },
                    )
                }
            }
            AddModelField(onAdd = { viewModel.addModel(it, embedding = false) })

            if (draft.kind != ProviderKind.ANTHROPIC) {
                SectionTitle(stringResource(R.string.ai_provider_embedding_models))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.embeddingModels.forEach { model ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(model) },
                            trailingIcon = {
                                IconButton(onClick = { viewModel.removeModel(model, embedding = true) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.ai_provider_remove_model, model), Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                }
                AddModelField(onAdd = { viewModel.addModel(it, embedding = true) })
            }

            if (!state.isNew) {
                TextButton(onClick = { viewModel.deleteProvider(draft.id) }) {
                    Text(stringResource(R.string.ai_provider_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    paramsFor?.let { model ->
        ModelParamsDialog(
            model = model,
            initial = draft.paramsFor(model),
            onDismiss = { paramsFor = null },
            onSave = {
                viewModel.setModelParams(model, it)
                paramsFor = null
            },
        )
    }
}

@Composable
private fun EditorMessageLine(message: EditorMessage) {
    val (text, error) = when (message) {
        EditorMessage.TestOk -> stringResource(R.string.ai_provider_test_ok) to false
        is EditorMessage.ModelsFetched -> stringResource(R.string.ai_provider_fetched, message.chat, message.embedding) to false
        EditorMessage.NeedsModel -> stringResource(R.string.ai_provider_needs_model) to true
        is EditorMessage.Failed -> stringResource(
            R.string.ai_provider_failed,
            message.detail.ifBlank { message.kind?.name ?: ChatException.Kind.NETWORK.name },
        ) to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AddModelField(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.ai_provider_model_name)) },
        singleLine = true,
        trailingIcon = {
            IconButton(
                onClick = {
                    onAdd(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
            ) { Icon(Icons.Rounded.Add, stringResource(R.string.ai_provider_add_model)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ModelParamsDialog(model: String, initial: ModelParams, onDismiss: () -> Unit, onSave: (ModelParams) -> Unit) {
    var useDefaultTemperature by remember { mutableStateOf(initial.temperature == null) }
    var temperature by remember { mutableFloatStateOf((initial.temperature ?: 0.7).toFloat()) }
    var maxTokens by remember { mutableStateOf(initial.maxTokens?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_provider_model_params, model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(stringResource(R.string.ai_provider_default), useDefaultTemperature) { useDefaultTemperature = it }
                if (!useDefaultTemperature) {
                    Text(stringResource(R.string.ai_provider_temperature_value, String.format(Locale.ROOT, "%.1f", temperature)))
                    ZenSlider(
                        value = temperature,
                        onValueChange = { temperature = (it * 10).roundToInt() / 10f },
                        valueRange = 0f..2f,
                        steps = 19,
                    )
                }
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { value -> maxTokens = value.filter(Char::isDigit).take(7) },
                    label = { Text(stringResource(R.string.ai_provider_max_tokens)) },
                    supportingText = { Text(stringResource(R.string.ai_provider_max_tokens_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    ModelParams(
                        temperature = if (useDefaultTemperature) null else temperature.toDouble(),
                        maxTokens = maxTokens.toIntOrNull()?.takeIf { it > 0 },
                    ),
                )
            }) { Text(stringResource(R.string.ai_provider_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_provider_cancel)) } },
    )
}

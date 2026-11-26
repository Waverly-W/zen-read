package com.zenread.feature.settings.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenread.core.designsystem.component.ZenSlider
import com.zenread.data.ai.chat.ModelRef
import com.zenread.data.ai.provider.ProviderConfig
import com.zenread.data.ai.provider.ProviderKind
import com.zenread.data.ai.provider.ProviderPreset
import com.zenread.data.ai.rag.IndexStatus
import com.zenread.data.ai.rag.IndexedBook
import com.zenread.data.ai.settings.AiSettings
import com.zenread.data.ai.settings.IndexOptions
import com.zenread.feature.settings.R
import kotlin.math.roundToInt

/** Settings › AI: providers, default models, book index options. */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val indexed by viewModel.indexedBooks.collectAsStateWithLifecycle()

    editor?.let { state ->
        BackHandler(onBack = viewModel::closeEditor)
        ProviderEditorScreen(state, viewModel)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_ai)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        val current = settings ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { SectionTitle(stringResource(R.string.ai_settings_providers)) }
            if (current.providers.isEmpty()) {
                item { Hint(stringResource(R.string.ai_settings_no_providers)) }
            }
            itemsIndexed(current.providers, key = { _, it -> it.id }) { index, provider ->
                ProviderRow(provider, index, current.providers.size, onClick = { viewModel.startEdit(provider.id) })
            }
            item { AddProviderButton(onPick = viewModel::startAdd) }

            item { SectionTitle(stringResource(R.string.ai_settings_models)) }
            item { ChatModelPicker(current, onPick = viewModel::setChatModel) }
            item { EmbeddingModelPicker(current, onPick = viewModel::setEmbeddingModel) }
            item { Hint(stringResource(R.string.ai_settings_embedding_help)) }

            item { SectionTitle(stringResource(R.string.ai_settings_index)) }
            item { IndexOptionsCard(current.index, onChange = viewModel::updateIndexOptions) }
            item { Hint(stringResource(R.string.ai_settings_privacy)) }

            if (indexed.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.ai_settings_indexed_books)) }
                items(indexed, key = { "index-${it.bookId}" }) { book ->
                    IndexedBookRow(book, onDelete = { viewModel.deleteIndex(book.bookId) })
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(provider: ProviderConfig, index: Int, count: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = segmentShape(index, count), color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            supportingContent = {
                Text(
                    listOf(provider.baseUrl, provider.models.firstOrNull()).filterNotNull().joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(
                    Icons.Rounded.Cloud,
                    null,
                    tint = if (provider.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Text(provider.name)
        }
    }
}

private fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 24.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun AddProviderButton(onPick: (ProviderPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Add, null)
            Text(stringResource(R.string.ai_settings_add_provider), modifier = Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ProviderPreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(if (preset == ProviderPreset.CUSTOM) stringResource(R.string.ai_provider_openai) else preset.displayName) },
                    onClick = {
                        open = false
                        onPick(preset)
                    },
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    value: String,
    index: Int,
    count: Int,
    options: List<Pair<String, () -> Unit>>,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { if (options.isNotEmpty()) open = true },
            shape = segmentShape(index, count),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            ListItem(
                supportingContent = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Text(title)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}

@Composable
private fun ChatModelPicker(settings: AiSettings, onPick: (ModelRef?) -> Unit) {
    val options = settings.providers.filter { it.enabled }.flatMap { provider ->
        provider.models.map { model -> "${provider.name} · $model" to { onPick(ModelRef(provider.id, model)) } }
    }
    PickerRow(
        title = stringResource(R.string.ai_settings_chat_model),
        value = settings.resolvedChatModel?.let { label(settings, it) } ?: stringResource(R.string.ai_settings_not_set),
        index = 0,
        count = 2,
        options = options,
    )
}

@Composable
private fun EmbeddingModelPicker(settings: AiSettings, onPick: (ModelRef?) -> Unit) {
    val none = stringResource(R.string.ai_settings_none)
    val options = listOf<Pair<String, () -> Unit>>(none to { onPick(null) }) +
        settings.providers.filter { it.enabled && it.kind != ProviderKind.ANTHROPIC }.flatMap { provider ->
            provider.embeddingModels.map { model -> "${provider.name} · $model" to { onPick(ModelRef(provider.id, model)) } }
        }
    PickerRow(
        title = stringResource(R.string.ai_settings_embedding_model),
        value = settings.resolvedEmbeddingModel?.let { label(settings, it) } ?: none,
        index = 1,
        count = 2,
        options = options,
    )
}

private fun label(settings: AiSettings, ref: ModelRef) = "${settings.provider(ref.providerId)?.name.orEmpty()} · ${ref.model}"

@Composable
private fun IndexOptionsCard(options: IndexOptions, onChange: ((IndexOptions) -> IndexOptions) -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var chunk by remember(options.chunkSize) { mutableFloatStateOf(options.chunkSize.toFloat()) }
            Text(stringResource(R.string.ai_settings_chunk_size, chunk.roundToInt()), style = MaterialTheme.typography.bodyMedium)
            ZenSlider(
                value = chunk,
                onValueChange = { chunk = it },
                valueRange = 300f..2000f,
                steps = 16,
                onValueChangeFinished = { onChange { it.copy(chunkSize = (chunk / 100).roundToInt() * 100) } },
            )
            var overlap by remember(options.chunkOverlap) { mutableFloatStateOf(options.chunkOverlap.toFloat()) }
            Text(stringResource(R.string.ai_settings_chunk_overlap, overlap.roundToInt()), style = MaterialTheme.typography.bodyMedium)
            ZenSlider(
                value = overlap,
                onValueChange = { overlap = it },
                valueRange = 0f..400f,
                steps = 7,
                onValueChangeFinished = { onChange { it.copy(chunkOverlap = (overlap / 50).roundToInt() * 50) } },
            )
            var topK by remember(options.topK) { mutableFloatStateOf(options.topK.toFloat()) }
            Text(stringResource(R.string.ai_settings_top_k, topK.roundToInt()), style = MaterialTheme.typography.bodyMedium)
            ZenSlider(
                value = topK,
                onValueChange = { topK = it },
                valueRange = 2f..12f,
                steps = 9,
                onValueChangeFinished = { onChange { it.copy(topK = topK.roundToInt()) } },
            )
            Hint(stringResource(R.string.ai_settings_index_note))
            SwitchRow(stringResource(R.string.ai_settings_use_index), options.useForQuestions) { checked ->
                onChange { it.copy(useForQuestions = checked) }
            }
            SwitchRow(stringResource(R.string.ai_settings_unmetered), options.unmeteredOnly) { checked ->
                onChange { it.copy(unmeteredOnly = checked) }
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Text(label)
    }
}

@Composable
private fun IndexedBookRow(book: IndexedBook, onDelete: () -> Unit) {
    val status = stringResource(
        when (book.status) {
            IndexStatus.READY -> R.string.ai_settings_status_ready
            IndexStatus.EXTRACTING, IndexStatus.EMBEDDING -> R.string.ai_settings_status_working
            IndexStatus.PAUSED -> R.string.ai_settings_status_paused
            IndexStatus.FAILED -> R.string.ai_settings_status_failed
        },
    )
    val model = book.embeddingModel ?: stringResource(R.string.ai_settings_keyword_only)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        ListItem(
            supportingContent = { Text(stringResource(R.string.ai_settings_indexed_summary, book.chunkCount, "$model · $status")) },
            trailingContent = {
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.ai_settings_delete_index)) }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

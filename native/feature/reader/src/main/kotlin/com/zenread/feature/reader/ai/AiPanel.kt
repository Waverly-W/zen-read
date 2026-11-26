package com.zenread.feature.reader.ai

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.zenread.data.ai.chat.ChatRole
import com.zenread.data.ai.history.Citation
import com.zenread.data.ai.history.MessageStatus
import com.zenread.data.ai.history.StoredMessage
import com.zenread.data.ai.prompt.PromptAssembler
import com.zenread.data.ai.rag.BookIndexState
import com.zenread.data.ai.rag.IndexStage
import com.zenread.data.ai.rag.IndexStatus
import com.zenread.data.ai.skills.ReadingSkill
import com.zenread.data.ai.skills.ReadingSkills
import com.zenread.feature.reader.R

/**
 * AI chat about the open book. [quote] is the reader selection the panel was opened with
 * (attached to the next message); [onJump] opens a cited location in the reader.
 */
@Composable
internal fun AiPanelSheet(
    bookId: Long,
    quote: String?,
    chapterSource: ChapterSource,
    onJump: (cfi: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: AiPanelViewModel = hiltViewModel<AiPanelViewModel, AiPanelViewModel.Factory>(
        key = "ai-$bookId",
        creationCallback = { it.create(bookId) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(quote) { viewModel.attachQuote(quote) }
    DisposableEffect(viewModel, chapterSource) {
        viewModel.chapterSource = chapterSource
        onDispose { viewModel.chapterSource = null }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.PartiallyExpanded)),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            PanelHeader(state, viewModel, onDismiss)
            if (state.configured) IndexBar(state.index, state.embeddingConfigured, viewModel)
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!state.configured) {
                    CenterMessage(stringResource(R.string.ai_not_configured_title), stringResource(R.string.ai_not_configured_body))
                } else if (state.messages.isEmpty() && state.live == null) {
                    CenterMessage(stringResource(R.string.ai_empty_title), stringResource(R.string.ai_empty_body))
                } else {
                    MessageList(state, onJump = onJump, onRegenerate = viewModel::regenerate)
                }
            }
            if (state.configured) Composer(state, viewModel)
        }
    }
}

@Composable
private fun PanelHeader(state: AiPanelState, viewModel: AiPanelViewModel, onDismiss: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_panel_title), style = MaterialTheme.typography.titleMedium)
            state.modelName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = viewModel::newConversation, enabled = !state.generating && state.conversationId != null) {
            Icon(Icons.Rounded.Add, stringResource(R.string.ai_new_chat))
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = state.conversations.isNotEmpty()) {
                Icon(Icons.Rounded.History, stringResource(R.string.ai_history))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                state.conversations.forEach { conversation ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                conversation.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (conversation.id == state.conversationId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.widthIn(max = 240.dp),
                            )
                        },
                        onClick = {
                            menu = false
                            viewModel.openConversation(conversation.id)
                        },
                        trailingIcon = {
                            IconButton(onClick = { viewModel.deleteConversation(conversation.id) }) {
                                Icon(Icons.Rounded.Delete, stringResource(R.string.ai_delete_chat))
                            }
                        },
                    )
                }
            }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.ai_close)) }
    }
}

@Composable
private fun IndexBar(index: BookIndexState, embeddingConfigured: Boolean, viewModel: AiPanelViewModel) {
    val progress = index.progress
    val label: String
    val action: Pair<String, () -> Unit>
    when {
        index.running && progress != null -> {
            label = stringResource(
                if (progress.stage == IndexStage.EXTRACTING) R.string.ai_index_extracting else R.string.ai_index_embedding,
                progress.done,
                progress.total,
            )
            action = stringResource(R.string.ai_index_cancel) to viewModel::cancelIndex
        }
        index.queued -> {
            label = stringResource(R.string.ai_index_queued)
            action = stringResource(R.string.ai_index_cancel) to viewModel::cancelIndex
        }
        index.status == IndexStatus.READY -> {
            label = pluralStringResource(
                if (index.embeddingModel == null) R.plurals.ai_index_ready_keywords else R.plurals.ai_index_ready,
                index.chunkCount,
                index.chunkCount,
            )
            action = stringResource(R.string.ai_index_rebuild) to { viewModel.buildIndex(rebuild = true) }
        }
        index.status == IndexStatus.FAILED -> {
            label = stringResource(R.string.ai_index_failed, index.error.orEmpty())
            action = stringResource(R.string.ai_index_retry) to { viewModel.buildIndex() }
        }
        index.status == IndexStatus.PAUSED || index.status == IndexStatus.EMBEDDING || index.status == IndexStatus.EXTRACTING -> {
            label = stringResource(R.string.ai_index_paused)
            action = stringResource(R.string.ai_index_resume) to { viewModel.buildIndex() }
        }
        else -> {
            label = stringResource(if (embeddingConfigured) R.string.ai_index_none else R.string.ai_index_no_embedding)
            action = stringResource(R.string.ai_index_build) to { viewModel.buildIndex() }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = if (index.status == IndexStatus.FAILED && !index.queued) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            action.let { (text, onClick) -> TextButton(onClick = onClick) { Text(text) } }
        }
        if (index.running && progress != null && progress.total > 0) {
            LinearWavyProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun CenterMessage(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessageList(state: AiPanelState, onJump: (String) -> Unit, onRegenerate: () -> Unit) {
    val listState = rememberLazyListState()
    val liveLength = state.live?.text?.length ?: 0
    // Follow the answer while it streams unless the user scrolled up to read.
    LaunchedEffect(state.messages.size, liveLength) {
        if (state.messages.isNotEmpty() && (!listState.canScrollForward || liveLength < 200)) listState.scrollBy(100_000f)
    }
    val lastAssistantId = state.messages.lastOrNull { it.role == ChatRole.ASSISTANT }?.id
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(state.messages, key = { it.id }) { message ->
            if (message.role == ChatRole.USER) {
                UserBubble(message)
            } else {
                val live = state.live?.takeIf { it.messageId == message.id }
                AssistantMessage(
                    message = message,
                    liveText = live?.text,
                    liveReasoning = live?.reasoning,
                    canRegenerate = message.id == lastAssistantId && !state.generating,
                    onJump = onJump,
                    onRegenerate = onRegenerate,
                )
            }
        }
    }
}

@Composable
private fun UserBubble(message: StoredMessage) {
    val skillName = message.skillId?.let(ReadingSkills::byId)?.let { stringResource(it.name) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        message.quote?.let { QuoteBox(it, maxLines = 4) }
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(start = 48.dp, top = 4.dp),
        ) {
            Text(
                skillName ?: message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun QuoteBox(text: String, maxLines: Int, onRemove: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.FormatQuote, stringResource(R.string.ai_quote_label), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.ai_remove_quote), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: StoredMessage,
    liveText: String?,
    liveReasoning: String?,
    canRegenerate: Boolean,
    onJump: (String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val text = liveText ?: message.content
    val reasoning = liveReasoning ?: message.reasoning.orEmpty()
    val streaming = liveText != null
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        if (reasoning.isNotBlank()) Reasoning(reasoning, initiallyOpen = streaming && text.isEmpty())
        if (text.isNotEmpty()) {
            CitationAwareMarkdown(text, message.citations, onJump)
        } else if (streaming) {
            LoadingIndicator(Modifier.size(32.dp))
        }
        when (message.status) {
            MessageStatus.ERROR -> if (!streaming) ErrorLine(message.error)
            MessageStatus.STOPPED -> if (!streaming) {
                Text(stringResource(R.string.ai_stopped), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Unit
        }
        if (!streaming && message.citations.isNotEmpty()) Sources(message.citations, onJump)
        if (!streaming && (text.isNotEmpty() || canRegenerate)) {
            Row {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AI", text))
                    }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.ai_copy), modifier = Modifier.size(20.dp)) }
                }
                if (canRegenerate) {
                    IconButton(onClick = onRegenerate) { Icon(Icons.Rounded.Refresh, stringResource(R.string.ai_regenerate), modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Reasoning(text: String, initiallyOpen: Boolean) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Text(
                text.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(max = 240.dp),
            )
        }
    }
}

/** Markdown where `[n]` citations are links that jump to the cited passage. */
@Composable
private fun CitationAwareMarkdown(text: String, citations: List<Citation>, onJump: (String) -> Unit) {
    val parent = LocalUriHandler.current
    val handler = remember(citations, parent) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith(PromptAssembler.CITATION_SCHEME + ":")) {
                    val number = uri.substringAfter(':').toIntOrNull()
                    citations.firstOrNull { it.number == number }?.let { onJump(it.cfi) }
                } else {
                    runCatching { parent.openUri(uri) }
                }
            }
        }
    }
    val content = remember(text, citations) { PromptAssembler.linkCitations(text, citations) }
    val markdownState = rememberMarkdownState(content, retainState = true)
    val type = MaterialTheme.typography
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(
            markdownState = markdownState,
            typography = markdownTypography(
                h1 = type.titleLarge,
                h2 = type.titleMedium,
                h3 = type.titleSmall,
                h4 = type.labelLarge,
                h5 = type.labelLarge,
                h6 = type.labelLarge,
                text = type.bodyLarge,
                paragraph = type.bodyLarge,
                ordered = type.bodyLarge,
                bullet = type.bodyLarge,
                list = type.bodyLarge,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Sources(citations: List<Citation>, onJump: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(stringResource(R.string.ai_sources), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            citations.sortedBy { it.number }.forEach { citation ->
                AssistChip(
                    onClick = { onJump(citation.cfi) },
                    label = {
                        Text(
                            "[${citation.number}] " + citation.chapter.ifBlank { citation.excerpt.take(24) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorLine(error: String?) {
    val kind = error?.substringBefore('\n').orEmpty()
    val detail = error?.substringAfter('\n', "").orEmpty()
    val summary = stringResource(
        when (kind) {
            "AUTH" -> R.string.ai_error_auth
            "RATE_LIMIT" -> R.string.ai_error_rate_limit
            "NETWORK" -> R.string.ai_error_network
            "SERVER" -> R.string.ai_error_server
            "BAD_REQUEST", "PROTOCOL", "UNSUPPORTED" -> R.string.ai_error_bad_request
            "FILTERED" -> R.string.ai_error_filtered
            "NOT_CONFIGURED" -> R.string.ai_error_not_configured
            else -> R.string.ai_error_other
        },
    )
    Column {
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Composer(state: AiPanelState, viewModel: AiPanelViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.quote?.let { QuoteBox(it, maxLines = 3, onRemove = { viewModel.attachQuote(null) }) }
        SkillChips(
            skills = if (state.quote != null) ReadingSkills.selectionActions + ReadingSkills.all else ReadingSkills.all,
            enabled = !state.generating,
            onRun = viewModel::runSkill,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.ai_input_hint)) },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (state.generating) {
                FilledIconButton(onClick = viewModel::stop) { Icon(Icons.Rounded.Stop, stringResource(R.string.ai_stop)) }
            } else {
                FilledIconButton(
                    onClick = {
                        viewModel.ask(input)
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.ai_send)) }
            }
        }
    }
}

@Composable
private fun SkillChips(skills: List<ReadingSkill>, enabled: Boolean, onRun: (ReadingSkill) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        skills.forEach { skill ->
            SuggestionChip(
                onClick = { onRun(skill) },
                enabled = enabled,
                label = { Text(stringResource(skill.name)) },
            )
        }
    }
}

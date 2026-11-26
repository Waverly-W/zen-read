package com.zenread.feature.reader.translate

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenread.core.model.ReadingTheme
import com.zenread.data.translate.TranslationColor
import com.zenread.data.translate.messageRes
import com.zenread.feature.reader.R
import com.zenread.reader.engine.ReaderController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Everything translation adds on top of the reader page: applies page-translation commands,
 * shows failures in a snackbar and hosts the selection translation sheet.
 */
@Composable
internal fun ReaderTranslationLayer(
    viewModel: ReaderTranslationViewModel,
    controller: ReaderController?,
    theme: ReadingTheme,
    pageReady: Boolean,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(pageReady) {
        if (pageReady) viewModel.onPageReady()
    }
    LaunchedEffect(controller, theme) {
        val page = controller ?: return@LaunchedEffect
        viewModel.commands.collect { command ->
            when (command) {
                is TranslationCommand.Enable -> page.setTranslation(
                    mode = command.mode.jsName,
                    generation = command.generation,
                    textStyle = translationTextStyle(command.color, command.italic, theme),
                    clear = command.clear,
                    prefetchPages = command.prefetchPages,
                )
                is TranslationCommand.Disable ->
                    page.setTranslation("off", command.generation, textStyle = null, clear = false, prefetchPages = 0)
            }
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val text = resources.getString(message.text)
            snackbar.showSnackbar(
                if (message.stopped) resources.getString(R.string.reader_translate_stopped, text) else text,
            )
        }
    }
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)) {
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }

    val selection by viewModel.selection.collectAsStateWithLifecycle()
    selection?.let { state ->
        SelectionTranslationSheet(
            state = state,
            services = viewModel.services,
            onSwitchService = viewModel::switchSelectionService,
            onRetry = viewModel::retrySelection,
            onDismiss = viewModel::dismissSelection,
        )
    }
}

@Composable
private fun SelectionTranslationSheet(
    state: SelectionTranslationState,
    services: List<TranslationServiceOption>,
    onSwitchService: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.reader_translate)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.reader_translate), style = MaterialTheme.typography.titleLarge)
            Text(
                state.source,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider()
            val result = state.result
            when {
                state.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(48.dp))
                }
                state.error != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(state.error.messageRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Text(stringResource(R.string.reader_translate_retry), Modifier.padding(start = 8.dp))
                    }
                }
                result != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        listOfNotNull(result.from?.nativeName, result.to.nativeName).joinToString(" → "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SelectionContainer {
                        Text(result.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                services.forEach { service ->
                    FilterChip(
                        selected = service.id == state.serviceId,
                        onClick = { onSwitchService(service.id) },
                        label = { Text(stringResource(service.label)) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    enabled = result != null && !state.loading,
                    onClick = {
                        result ?: return@TextButton
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(clipLabel, result.text))
                    },
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.reader_translate_copy), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** `{color, italic, bold}` for foliate-js `Translator.setTextStyle`; sizes follow the book. */
internal fun translationTextStyle(color: TranslationColor, italic: Boolean, theme: ReadingTheme): JsonObject =
    buildJsonObject {
        put(
            "color",
            when (color) {
                TranslationColor.TEXT -> theme.text
                TranslationColor.MUTED -> blend(theme.text, theme.background, 0.4f)
                TranslationColor.ACCENT -> if (theme == ReadingTheme.NIGHT) "#8AB4F8" else "#2F5D9B"
            },
        )
        put("italic", italic)
        put("bold", false)
    }

/** Mixes [amount] of [over] into [base]; both `#RRGGBB`. */
internal fun blend(base: String, over: String, amount: Float): String {
    val a = base.removePrefix("#").toIntOrNull(16) ?: return base
    val b = over.removePrefix("#").toIntOrNull(16) ?: return base
    fun channel(shift: Int): Int {
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        return Math.round(x + (y - x) * amount).coerceIn(0, 255)
    }
    return "#%02X%02X%02X".format(channel(16), channel(8), channel(0))
}

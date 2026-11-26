package com.zenread.feature.fonts

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenread.core.designsystem.component.ZenSlider
import com.zenread.core.model.ReaderFont
import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.SystemFontFamily
import com.zenread.data.fonts.DownloadState
import com.zenread.data.fonts.DownloadableFont
import com.zenread.data.fonts.FontCatalog
import com.zenread.data.fonts.FontTarget
import com.zenread.data.fonts.ImportResult
import com.zenread.data.fonts.InstalledFont
import java.io.File
import kotlin.math.roundToInt

/**
 * A "Font" row showing the current body font; tapping it opens [FontPickerSheet].
 * Drop it into any typography panel.
 */
@Composable
fun FontSettingRow(
    modifier: Modifier = Modifier,
    viewModel: FontPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val current = state?.let { fontLabel(it.settings.font, FontTarget.BODY, it.installed) }
    // Same title style as the typography sheet's other sections, no extra horizontal inset.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = { open = true })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.fonts_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            if (current != null) {
                Text(
                    current,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
    }
    if (open) FontPickerSheet(onDismiss = { open = false }, viewModel = viewModel)
}

/** Font picker in a bottom sheet. Changes apply immediately, also to an open book. */
@Composable
fun FontPickerSheet(
    onDismiss: () -> Unit,
    viewModel: FontPickerViewModel = hiltViewModel(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FontPicker(
            viewModel = viewModel,
            contentPadding = PaddingValues(bottom = 32.dp),
        )
    }
}

/**
 * Body / Latin font choice, weight, system / built-in / downloadable / imported fonts, import and
 * delete, as one scrolling list. [header] adds items above the font controls.
 */
@Composable
fun FontPicker(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    header: LazyListScope.() -> Unit = {},
    viewModel: FontPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var target by rememberSaveable { mutableStateOf(FontTarget.BODY) }
    var pendingDelete by remember { mutableStateOf<InstalledFont?>(null) }
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // File managers label font MIME types inconsistently, so the picker shows everything
        // and the repository checks the bytes.
        if (uris.isNotEmpty()) viewModel.import(uris, target) { result -> context.showImportResult(result) }
    }
    val current = state

    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        header()
        item(key = "title") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fonts_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.fonts_import), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        item(key = "target") {
            TargetSelector(target, onChange = { target = it })
        }
        if (current == null) return@LazyColumn

        if (target == FontTarget.BODY) {
            item(key = "weight") { WeightSlider(current.settings.fontWeight, viewModel::setWeight) }
        } else {
            item(key = "latin-hint") {
                Text(
                    stringResource(R.string.fonts_latin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }

        val selected = current.selected(target)
        val onSelect = { font: ReaderFont? -> viewModel.select(target, font) }
        val fileOf = viewModel::installedFile

        item(key = "default") {
            if (target == FontTarget.BODY) {
                FontRow(
                    font = ReaderFont.Book, label = stringResource(R.string.fonts_follow_book),
                    supporting = stringResource(R.string.fonts_follow_book_detail),
                    selected = selected == ReaderFont.Book, onClick = { onSelect(ReaderFont.Book) }, installedFile = fileOf,
                )
            } else {
                FontRow(
                    font = null, label = stringResource(R.string.fonts_same_as_body),
                    selected = selected == null, onClick = { onSelect(null) }, installedFile = fileOf,
                )
            }
        }

        sectionHeader(R.string.fonts_section_system)
        items(SystemFontFamily.entries, key = { "system-${it.css}" }) { family ->
            val font = ReaderFont.System(family)
            FontRow(
                font = font, label = stringResource(family.labelRes()),
                selected = selected == font, onClick = { onSelect(font) }, installedFile = fileOf,
            )
        }

        sectionHeader(R.string.fonts_section_bundled)
        items(FontCatalog.bundled, key = { "bundled-${it.fileName}" }) { bundled ->
            FontRow(
                font = bundled.font, label = bundled.label, supporting = bundled.license,
                selected = selected == bundled.font, onClick = { onSelect(bundled.font) }, installedFile = fileOf,
            )
        }

        sectionHeader(R.string.fonts_section_download)
        items(FontCatalog.downloadable, key = { "download-${it.id}" }) { entry ->
            val installed = current.installed.firstOrNull { it.fileName == entry.fileName }
            DownloadableRow(
                entry = entry,
                installed = installed,
                download = current.downloads[entry.id],
                selected = selected == entry.font,
                onSelect = { onSelect(entry.font) },
                onDownload = { viewModel.download(entry.id, target) },
                onCancel = { viewModel.cancelDownload(entry.id) },
                onDelete = { installed?.let { pendingDelete = it } },
                installedFile = fileOf,
            )
        }

        sectionHeader(R.string.fonts_section_imported)
        val imported = current.installed.filter { it.catalogId == null }
        if (imported.isEmpty()) {
            item(key = "imported-empty") {
                Text(
                    stringResource(R.string.fonts_imported_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
        items(imported, key = { "file-${it.fileName}" }) { font ->
            FontRow(
                font = font.font,
                label = font.label,
                supporting = installedDetail(font),
                selected = selected == font.font,
                onClick = { onSelect(font.font) },
                installedFile = fileOf,
                trailing = {
                    IconButton(onClick = { pendingDelete = font }) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.fonts_delete))
                    }
                },
            )
        }
    }

    pendingDelete?.let { font ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.fonts_delete_title)) },
            text = { Text(stringResource(R.string.fonts_delete_body, font.label)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(font.fileName)
                    pendingDelete = null
                }) { Text(stringResource(R.string.fonts_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.fonts_cancel)) }
            },
        )
    }
}

@Composable
private fun TargetSelector(target: FontTarget, onChange: (FontTarget) -> Unit) {
    val options = listOf(FontTarget.BODY to R.string.fonts_target_body, FontTarget.LATIN to R.string.fonts_target_latin)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = target == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun WeightSlider(weight: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row {
            Text(
                stringResource(R.string.fonts_weight),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                weight.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val min = ReaderSettings.FONT_WEIGHT_MIN
        val max = ReaderSettings.FONT_WEIGHT_MAX
        ZenSlider(
            value = weight.toFloat(),
            onValueChange = { onChange(((it / 100f).roundToInt() * 100).coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min) / 100 - 1,
        )
    }
}

private fun LazyListScope.sectionHeader(@StringRes title: Int) {
    item(key = "header-$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun FontRow(
    font: ReaderFont?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    installedFile: (String) -> File?,
    supporting: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val family: FontFamily? = rememberPreviewFamily(font, installedFile)
    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        headlineContent = {
            Text(
                label,
                fontFamily = family,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = supporting?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = trailing,
    )
}

@Composable
private fun DownloadableRow(
    entry: DownloadableFont,
    installed: InstalledFont?,
    download: DownloadState?,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    installedFile: (String) -> File?,
) {
    val context = LocalContext.current
    val description = stringResource(entry.descriptionRes())
    val supporting = when (download) {
        is DownloadState.Running -> stringResource(R.string.fonts_downloading, (download.fraction * 100).roundToInt())
        DownloadState.Failed -> stringResource(R.string.fonts_download_failed)
        null -> if (installed != null) {
            description
        } else {
            stringResource(R.string.fonts_download_detail, description, Formatter.formatShortFileSize(context, entry.sizeBytes), entry.license)
        }
    }
    FontRow(
        font = if (installed != null) entry.font else null,
        label = entry.label,
        supporting = supporting,
        selected = selected && installed != null,
        // Tapping a font that is not here yet downloads it and then applies it.
        onClick = when {
            installed != null -> onSelect
            download is DownloadState.Running -> ({})
            else -> onDownload
        },
        installedFile = installedFile,
        trailing = {
            when {
                download is DownloadState.Running -> Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { download.fraction }, modifier = Modifier.size(40.dp))
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, stringResource(R.string.fonts_cancel_download)) }
                }
                installed != null -> IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, stringResource(R.string.fonts_delete))
                }
                download == DownloadState.Failed -> IconButton(onClick = onDownload) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.fonts_download))
                }
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Rounded.Download, stringResource(R.string.fonts_download))
                }
            }
        },
    )
}

@Composable
private fun installedDetail(font: InstalledFont): String {
    val size = Formatter.formatShortFileSize(LocalContext.current, font.sizeBytes)
    val type = font.format.extension.uppercase()
    return if (font.format.previewable) {
        "$type · $size"
    } else {
        stringResource(R.string.fonts_no_preview, "$type · $size")
    }
}

/** Display name for a font choice, as shown in summaries. */
@Composable
fun fontLabel(font: ReaderFont?, target: FontTarget, installed: List<InstalledFont>): String = when (font) {
    null -> stringResource(if (target == FontTarget.BODY) R.string.fonts_follow_book else R.string.fonts_same_as_body)
    ReaderFont.Book -> stringResource(R.string.fonts_follow_book)
    is ReaderFont.System -> stringResource(font.family.labelRes())
    is ReaderFont.Bundled -> FontCatalog.bundled.firstOrNull { it.fileName == font.fileName }?.label ?: font.fileName
    is ReaderFont.Installed -> installed.firstOrNull { it.fileName == font.fileName }?.label
        ?: FontCatalog.downloadable.firstOrNull { it.fileName == font.fileName }?.label
        ?: stringResource(R.string.fonts_missing)
}

private fun SystemFontFamily.labelRes(): Int = when (this) {
    SystemFontFamily.SERIF -> R.string.fonts_serif
    SystemFontFamily.SANS_SERIF -> R.string.fonts_sans_serif
    SystemFontFamily.MONOSPACE -> R.string.fonts_monospace
}

private fun DownloadableFont.descriptionRes(): Int = when (id) {
    "source-han-serif-cn" -> R.string.fonts_desc_source_han_serif
    "lxgw-wenkai-lite" -> R.string.fonts_desc_lxgw_wenkai
    "literata" -> R.string.fonts_desc_literata
    else -> R.string.fonts_desc_generic
}

private fun android.content.Context.showImportResult(result: ImportResult) {
    val messages = buildList {
        if (result.imported.isNotEmpty()) {
            add(resources.getQuantityString(R.plurals.fonts_imported, result.imported.size, result.imported.size))
        }
        if (result.failed > 0) {
            add(resources.getQuantityString(R.plurals.fonts_import_failed, result.failed, result.failed))
        }
    }
    if (messages.isNotEmpty()) Toast.makeText(this, messages.joinToString("\n"), Toast.LENGTH_LONG).show()
}

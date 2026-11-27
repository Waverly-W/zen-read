package com.zenread.feature.settings.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenread.data.translate.CredentialField
import com.zenread.data.translate.Language
import com.zenread.data.translate.PageTranslationMode
import com.zenread.data.translate.TranslateSettings
import com.zenread.data.translate.TranslationColor
import com.zenread.data.translate.TranslatorDescriptor
import com.zenread.data.translate.messageRes
import com.zenread.feature.settings.R
import java.util.Locale

@Composable
fun TranslateSettingsScreen(
    onBack: () -> Unit,
    viewModel: TranslateSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val savedKeys by viewModel.savedKeys.collectAsStateWithLifecycle()
    val tests by viewModel.tests.collectAsStateWithLifecycle()
    val cached by viewModel.cached.collectAsStateWithLifecycle()
    var pickingLanguage by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_translate)) },
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
            item {
                Text(
                    stringResource(R.string.translate_settings_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }

            header(R.string.translate_settings_service)
            item {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    viewModel.services.forEachIndexed { index, service ->
                        ServiceRow(
                            service = service,
                            selected = service.id == current.serviceId,
                            keySet = service.credentialFields.filter { it.required }
                                .all { savedKeys[service.id]?.get(it.name).orEmpty().isNotBlank() },
                            shape = segmentShape(index, viewModel.services.size),
                            onSelect = { viewModel.setService(service.id) },
                        )
                    }
                }
            }

            header(R.string.translate_settings_language)
            item {
                Segment(segmentShape(0, 1)) {
                    ListItem(
                        modifier = Modifier.selectable(selected = false, role = Role.Button) { pickingLanguage = true },
                        supportingContent = { Text(targetLabel(current.targetLanguage)) },
                        colors = segmentColors(),
                    ) { Text(stringResource(R.string.translate_settings_target)) }
                }
            }

            header(R.string.translate_settings_page)
            item { PageOptions(current, viewModel) }

            header(R.string.translate_settings_keys)
            items(viewModel.services, key = { it.id }) { service ->
                ServiceCard(
                    service = service,
                    saved = savedKeys[service.id].orEmpty(),
                    test = tests[service.id],
                    onSave = { viewModel.saveKeys(service, it) },
                    onTest = { viewModel.test(service, it) },
                )
            }

            header(R.string.translate_settings_cache)
            item {
                Segment(segmentShape(0, 1)) {
                    ListItem(
                        supportingContent = {
                            Text(
                                cached?.let { pluralStringResource(R.plurals.translate_settings_cache_count, it, it) }.orEmpty(),
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = viewModel::clearCache, enabled = (cached ?: 0) > 0) {
                                Text(stringResource(R.string.translate_settings_cache_clear))
                            }
                        },
                        colors = segmentColors(),
                    ) { Text(stringResource(R.string.translate_settings_cache_title)) }
                }
            }
        }
    }

    if (pickingLanguage) {
        LanguageDialog(
            selected = settings?.targetLanguage,
            onPick = {
                viewModel.setTarget(it)
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false },
        )
    }
}

private fun LazyListScope.header(title: Int) {
    item(contentType = "header") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun ServiceRow(
    service: TranslatorDescriptor,
    selected: Boolean,
    keySet: Boolean,
    shape: RoundedCornerShape,
    onSelect: () -> Unit,
) {
    Segment(shape) {
        ListItem(
            modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            supportingContent = {
                Text(
                    stringResource(
                        when {
                            !service.requiresCredentials -> R.string.translate_settings_free
                            keySet -> R.string.translate_settings_key_set
                            else -> R.string.translate_settings_key_missing
                        },
                    ),
                    color = if (service.requiresCredentials && !keySet) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = segmentColors(),
        ) { Text(stringResource(service.label)) }
    }
}

@Composable
private fun PageOptions(settings: TranslateSettings, viewModel: TranslateSettingsViewModel) {
    Segment(segmentShape(0, 1)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.translate_settings_page_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Choice(
                label = R.string.translate_settings_page_mode,
                options = PageTranslationMode.entries,
                selected = settings.pageMode,
                text = {
                    when (it) {
                        PageTranslationMode.BILINGUAL -> R.string.translate_settings_mode_bilingual
                        PageTranslationMode.TRANSLATION_ONLY -> R.string.translate_settings_mode_translation
                    }
                },
                onSelect = viewModel::setPageMode,
            )
            Choice(
                label = R.string.translate_settings_page_color,
                options = TranslationColor.entries,
                selected = settings.pageColor,
                text = {
                    when (it) {
                        TranslationColor.TEXT -> R.string.translate_settings_color_text
                        TranslationColor.MUTED -> R.string.translate_settings_color_muted
                        TranslationColor.ACCENT -> R.string.translate_settings_color_accent
                    }
                },
                onSelect = viewModel::setPageColor,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = settings.pageItalic, role = Role.Switch, onValueChange = viewModel::setPageItalic),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.translate_settings_page_italic), Modifier.weight(1f))
                Switch(checked = settings.pageItalic, onCheckedChange = null)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(
                    label = R.string.translate_settings_prefetch,
                    options = (0..TranslateSettings.MAX_PREFETCH_PAGES).toList(),
                    selected = settings.prefetchPages,
                    label2 = { if (it == 0) stringResource(R.string.translate_settings_prefetch_off) else it.toString() },
                    onSelect = viewModel::setPrefetchPages,
                )
                Text(
                    stringResource(R.string.translate_settings_prefetch_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun <T> Choice(
    label: Int,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    text: ((T) -> Int)? = null,
    label2: (@Composable (T) -> String)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(label2?.invoke(option) ?: stringResource(text!!(option)), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(
    service: TranslatorDescriptor,
    saved: Map<String, String>,
    test: ServiceTestState?,
    onSave: (Map<String, String>) -> Unit,
    onTest: (Map<String, String>?) -> Unit,
) {
    val edits = remember(service.id, saved) { mutableStateMapOf<String, String>().apply { putAll(saved) } }
    val values = { service.credentialFields.associate { it.name to edits[it.name].orEmpty() } }
    Segment(RoundedCornerShape(24.dp), Modifier.padding(bottom = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(service.label), style = MaterialTheme.typography.titleMedium)
            if (service.credentialFields.isEmpty()) {
                Text(
                    stringResource(R.string.translate_settings_unofficial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            service.credentialFields.forEach { field ->
                CredentialInput(field, edits[field.name].orEmpty()) { edits[field.name] = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onTest(values().takeIf { service.credentialFields.isNotEmpty() }) },
                    enabled = test != ServiceTestState.Running,
                ) { Text(stringResource(R.string.translate_settings_test)) }
                if (service.credentialFields.isNotEmpty()) {
                    Button(onClick = { onSave(values()) }, enabled = values() != saved) {
                        Text(stringResource(R.string.translate_settings_save))
                    }
                }
            }
            TestResult(test)
        }
    }
}

@Composable
private fun CredentialInput(field: CredentialField, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(!field.secret) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(field.label)) },
        supportingText = field.hint?.let { { Text(stringResource(it)) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Ascii,
            autoCorrectEnabled = false,
        ),
        trailingIcon = if (field.secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        stringResource(if (visible) R.string.translate_settings_hide_key else R.string.translate_settings_show_key),
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TestResult(test: ServiceTestState?) {
    when (test) {
        null -> Unit
        ServiceTestState.Running -> LoadingIndicator(Modifier.size(32.dp))
        is ServiceTestState.Passed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text(test.result.text, style = MaterialTheme.typography.bodyMedium)
        }
        is ServiceTestState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Text(stringResource(test.reason.messageRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun LanguageDialog(selected: Language?, onPick: (Language?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate_settings_target)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp).selectableGroup()) {
                item { LanguageOption(targetLabel(null), selected == null) { onPick(null) } }
                items(Language.entries) { language ->
                    LanguageOption(language.nativeName, selected == language) { onPick(language) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.translate_settings_cancel)) } },
    )
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun targetLabel(language: Language?): String = language?.nativeName
    ?: stringResource(
        R.string.translate_settings_target_system,
        (Language.fromLocale(Locale.getDefault()) ?: Language.ENGLISH).nativeName,
    )

@Composable
private fun Segment(shape: RoundedCornerShape, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier, content = content)
}

@Composable
private fun segmentColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** Expressive grouped-list shape: large outer corners, small inner corners. */
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

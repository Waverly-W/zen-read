package com.zenread.feature.settings.appearance

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenread.core.designsystem.motion.ZenMotion
import com.zenread.core.designsystem.theme.accentColorScheme
import com.zenread.core.designsystem.theme.supportsDynamicColor
import com.zenread.core.model.AppLanguage
import com.zenread.core.model.AppearanceSettings
import com.zenread.core.model.ThemeAccent
import com.zenread.core.model.ThemeMode
import com.zenread.feature.settings.R

/** Settings › Appearance: dark mode, dynamic color, theme color, pure black, app language. */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: AppearanceSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_appearance)) },
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { ThemeGroup(current, viewModel::update) }
            item { LanguageGroup() }
        }
    }
}

@Composable
private fun ThemeGroup(settings: AppearanceSettings, onChange: ((AppearanceSettings) -> AppearanceSettings) -> Unit) {
    val dynamicAvailable = supportsDynamicColor()
    Group(stringResource(R.string.appearance_section_theme)) {
        Label(R.string.appearance_theme_mode)
        ThemeModeSelector(settings.themeMode) { mode -> onChange { it.copy(themeMode = mode) } }
        if (dynamicAvailable) {
            Toggle(
                title = R.string.appearance_dynamic_color,
                summary = R.string.appearance_dynamic_color_summary,
                checked = settings.dynamicColor,
            ) { on -> onChange { it.copy(dynamicColor = on) } }
        }
        AnimatedVisibility(
            visible = !dynamicAvailable || !settings.dynamicColor,
            enter = ZenMotion.expand,
            exit = ZenMotion.collapse,
        ) {
            Column {
                Label(R.string.appearance_accent)
                AccentPicker(settings.accent) { accent -> onChange { it.copy(accent = accent) } }
            }
        }
        Toggle(
            title = R.string.appearance_pure_black,
            summary = R.string.appearance_pure_black_summary,
            checked = settings.pureBlack,
        ) { on -> onChange { it.copy(pureBlack = on) } }
    }
}

@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to R.string.appearance_theme_system,
        ThemeMode.LIGHT to R.string.appearance_theme_light,
        ThemeMode.DARK to R.string.appearance_theme_dark,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(stringResource(label), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun AccentPicker(selected: ThemeAccent, onSelect: (ThemeAccent) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .selectableGroup(),
    ) {
        ThemeAccent.entries.forEach { accent ->
            AccentSwatch(accent, selected = accent == selected, onClick = { onSelect(accent) })
        }
    }
}

/**
 * One preset, drawn like the system wallpaper-color picker: primary on top, secondary and tertiary
 * below. Fixed roles are used because they are the same in light and dark schemes.
 */
@Composable
private fun AccentSwatch(accent: ThemeAccent, selected: Boolean, onClick: () -> Unit) {
    val scheme = accentColorScheme(accent, dark = false)
    val name = stringResource(accent.labelRes())
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .border(2.dp, ring, CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val half = size.height / 2
            drawRect(scheme.primaryFixedDim, size = Size(size.width, half))
            drawRect(scheme.secondaryFixed, topLeft = Offset(0f, half), size = Size(size.width / 2, half))
            drawRect(scheme.tertiaryFixedDim, topLeft = Offset(size.width / 2, half), size = Size(size.width / 2, half))
        }
        if (selected) {
            Surface(shape = CircleShape, color = scheme.primary, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.padding(3.dp))
            }
        }
    }
}

private fun ThemeAccent.labelRes(): Int = when (this) {
    ThemeAccent.TEAL -> R.string.appearance_accent_teal
    ThemeAccent.BLUE -> R.string.appearance_accent_blue
    ThemeAccent.VIOLET -> R.string.appearance_accent_violet
    ThemeAccent.ROSE -> R.string.appearance_accent_rose
    ThemeAccent.AMBER -> R.string.appearance_accent_amber
    ThemeAccent.GREEN -> R.string.appearance_accent_green
}

@Composable
private fun LanguageGroup() {
    val context = LocalContext.current
    // Re-read after a configuration change: on Android 13+ the language can also change in system settings.
    var selected by remember(LocalConfiguration.current) { mutableStateOf(AppLanguages.current()) }
    val options = listOf(
        AppLanguage.SYSTEM to R.string.appearance_language_system,
        AppLanguage.SIMPLIFIED_CHINESE to R.string.appearance_language_zh_cn,
        AppLanguage.ENGLISH to R.string.appearance_language_en,
    )
    Group(stringResource(R.string.appearance_section_language)) {
        Column(Modifier.selectableGroup()) {
            options.forEach { (language, label) ->
                ListItem(
                    modifier = Modifier.selectable(
                        selected = language == selected,
                        role = Role.RadioButton,
                        onClick = {
                            selected = language
                            AppLanguages.set(language)
                        },
                    ),
                    leadingContent = { RadioButton(selected = language == selected, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(label)) },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ListItem(
                modifier = Modifier.clickable(role = Role.Button) {
                    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // Some ROMs hide the per-app language screen; the choices above still work.
                    }
                },
                headlineContent = { Text(stringResource(R.string.appearance_language_more)) },
                supportingContent = { Text(stringResource(R.string.appearance_language_more_summary)) },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 8.dp), content = content)
        }
    }
}

@Composable
private fun Label(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun Toggle(
    @StringRes title: Int,
    @StringRes summary: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

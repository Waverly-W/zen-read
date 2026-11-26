package com.zenread.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zenread.core.designsystem.component.EmptyState

/** Shown for settings categories that have no page yet. */
@Composable
fun PlaceholderSettingsScreen(category: SettingsCategory, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(category.titleRes())) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        EmptyState(
            icon = Icons.Rounded.Construction,
            title = stringResource(R.string.settings_coming_soon),
            body = stringResource(R.string.settings_coming_soon_body),
            modifier = Modifier.padding(padding),
        )
    }
}

fun SettingsCategory.titleRes(): Int = when (this) {
    SettingsCategory.APPEARANCE -> R.string.settings_appearance
    SettingsCategory.READING -> R.string.settings_reading
    SettingsCategory.SYNC -> R.string.settings_sync
    SettingsCategory.AI -> R.string.settings_ai
    SettingsCategory.TTS -> R.string.settings_tts
    SettingsCategory.TRANSLATE -> R.string.settings_translate
    SettingsCategory.ABOUT -> R.string.settings_about
}

package com.zenread.app.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.zenread.app.R
import com.zenread.feature.library.LibraryKey
import com.zenread.feature.library.LibraryScreen
import com.zenread.feature.notes.NotesKey
import com.zenread.feature.reader.ReaderKey
import com.zenread.feature.reader.ReaderScreen
import com.zenread.feature.notes.NotesScreen
import com.zenread.feature.settings.PlaceholderSettingsScreen
import com.zenread.feature.settings.SettingsCategory
import com.zenread.feature.settings.SettingsCategoryKey
import com.zenread.feature.settings.SettingsKey
import com.zenread.feature.settings.SettingsScreen
import com.zenread.feature.settings.translate.TranslateSettingsScreen
import com.zenread.feature.settings.tts.TtsSettingsScreen
import com.zenread.feature.settings.ai.AiSettingsScreen
import com.zenread.feature.settings.sync.SyncSettingsScreen
import com.zenread.feature.settings.reading.ReadingSettingsScreen

private enum class TopLevelDestination(
    val key: NavKey,
    @param:StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    LIBRARY(LibraryKey, R.string.nav_library, Icons.Rounded.AutoStories, Icons.Outlined.AutoStories),
    NOTES(NotesKey, R.string.nav_notes, Icons.Rounded.EditNote, Icons.Outlined.EditNote),
    SETTINGS(SettingsKey, R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
}

/**
 * Root shell. A single back stack whose first entry is the current top-level tab;
 * detail screens (reader, settings pages) are pushed on top of it.
 * NavigationSuiteScaffold switches between bottom bar and rail by window size.
 */
@Composable
fun ZenReadApp() {
    val backStack = rememberNavBackStack(LibraryKey)
    val currentTab = TopLevelDestination.entries.first { it.key == backStack.first() }
    // The reader is full-screen: no bottom bar or rail.
    val inReader = backStack.lastOrNull() is ReaderKey
    val adaptiveType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())

    NavigationSuiteScaffold(
        layoutType = if (inReader) NavigationSuiteType.None else adaptiveType,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == currentTab
                item(
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            backStack.clear()
                            backStack.add(destination.key)
                        }
                    },
                    icon = {
                        Icon(
                            if (selected) destination.selectedIcon else destination.unselectedIcon,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<LibraryKey> { LibraryScreen(onOpenBook = { backStack.add(ReaderKey(it)) }) }
                entry<ReaderKey> { key ->
                    ReaderScreen(bookId = key.bookId, openAt = key.cfi, onBack = { backStack.removeLastOrNull() })
                }
                entry<NotesKey> { NotesScreen(onOpen = { bookId, cfi -> backStack.add(ReaderKey(bookId, cfi)) }) }
                entry<SettingsKey> { SettingsScreen(onOpenCategory = { backStack.add(SettingsCategoryKey(it)) }) }
                entry<SettingsCategoryKey> { key ->
                    val back = { backStack.removeLastOrNull(); Unit }
                    // Each phase adds its category here, e.g. SettingsCategory.TTS -> TtsSettingsScreen(onBack = back).
                    when (key.category) {
                        SettingsCategory.TRANSLATE -> TranslateSettingsScreen(onBack = back)
                        SettingsCategory.TTS -> TtsSettingsScreen(onBack = back)
                        SettingsCategory.AI -> AiSettingsScreen(onBack = back)
                        SettingsCategory.SYNC -> SyncSettingsScreen(onBack = back)
                        SettingsCategory.READING -> ReadingSettingsScreen(onBack = back)
                        else -> PlaceholderSettingsScreen(key.category, onBack = back)
                    }
                }
            },
        )
    }
}

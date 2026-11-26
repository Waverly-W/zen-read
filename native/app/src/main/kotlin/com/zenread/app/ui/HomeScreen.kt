package com.zenread.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.zenread.app.R
import com.zenread.core.designsystem.motion.ZenMotion
import com.zenread.feature.library.LibraryScreen
import com.zenread.feature.notes.NotesScreen
import com.zenread.feature.settings.SettingsCategory
import com.zenread.feature.settings.SettingsScreen

private enum class HomeTab(
    @param:StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    LIBRARY(R.string.nav_library, Icons.Rounded.AutoStories, Icons.Outlined.AutoStories),
    NOTES(R.string.nav_notes, Icons.Rounded.EditNote, Icons.Outlined.EditNote),
    SETTINGS(R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
}

/**
 * Library, notes and settings behind a navigation bar (rail on wide windows). Tabs switch
 * with a fade through and keep their scroll position; Back on another tab returns to the
 * library before leaving the app.
 */
@Composable
internal fun HomeScreen(
    onOpenBook: (bookId: Long, cfi: String?) -> Unit,
    onOpenSettings: (SettingsCategory) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    val tabStates = rememberSaveableStateHolder()
    val motion = MaterialTheme.motionScheme

    BackHandler(enabled = tab != HomeTab.LIBRARY) { tab = HomeTab.LIBRARY }

    NavigationSuiteScaffold(
        layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2()),
        navigationSuiteItems = {
            HomeTab.entries.forEach { destination ->
                val selected = destination == tab
                item(
                    selected = selected,
                    onClick = { tab = destination },
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
        AnimatedContent(
            targetState = tab,
            transitionSpec = { ZenMotion.fadeThrough(motion) },
            label = "home-tab",
        ) { current ->
            tabStates.SaveableStateProvider(current.name) {
                when (current) {
                    HomeTab.LIBRARY -> LibraryScreen(onOpenBook = { onOpenBook(it, null) })
                    HomeTab.NOTES -> NotesScreen(onOpen = { bookId, cfi -> onOpenBook(bookId, cfi) })
                    HomeTab.SETTINGS -> SettingsScreen(onOpenCategory = onOpenSettings)
                }
            }
        }
    }
}

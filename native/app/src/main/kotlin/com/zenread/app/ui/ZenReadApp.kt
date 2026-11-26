package com.zenread.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.zenread.core.designsystem.motion.ZenMotion
import com.zenread.feature.reader.ReaderKey
import com.zenread.feature.reader.ReaderScreen
import com.zenread.feature.settings.PlaceholderSettingsScreen
import com.zenread.feature.settings.SettingsCategory
import com.zenread.feature.settings.SettingsCategoryKey
import com.zenread.feature.settings.ai.AiSettingsScreen
import com.zenread.feature.settings.reading.ReadingSettingsScreen
import com.zenread.feature.settings.sync.SyncSettingsScreen
import com.zenread.feature.settings.translate.TranslateSettingsScreen
import com.zenread.feature.settings.tts.TtsSettingsScreen
import kotlinx.serialization.Serializable

/** The tabbed home (library, notes, settings); always the bottom of the back stack. */
@Serializable
data object HomeKey : NavKey

/**
 * Root shell. The back stack is Home plus any full-screen pages pushed on top of it (reader,
 * settings pages). The navigation bar belongs to Home, so it slides away together with Home
 * instead of vanishing mid-transition, and the reader never resizes while it opens.
 */
@Composable
fun ZenReadApp() {
    val backStack = rememberNavBackStack(HomeKey)
    val back = { backStack.removeLastOrNull(); Unit }
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }

    // Back on another tab returns to the library before leaving the app, but only while Home
    // is the whole back stack. Activity BackHandlers and NavDisplay's own handler are ordered
    // by when they were enabled, so a handler that stayed enabled under a pushed page could
    // swallow that page's back gesture (seen on Android 17).
    BackHandler(enabled = backStack.size == 1 && tab != HomeTab.LIBRARY) { tab = HomeTab.LIBRARY }
    val motion = MaterialTheme.motionScheme
    val slide = with(LocalDensity.current) { SLIDE_DISTANCE.roundToPx() }

    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = { ZenMotion.forward(motion, slide) },
        popTransitionSpec = { ZenMotion.back(motion, slide) },
        predictivePopTransitionSpec = { ZenMotion.predictiveBack(motion, slide) },
        entryProvider = entryProvider {
            entry<HomeKey> {
                HomeScreen(
                    tab = tab,
                    onSelectTab = { tab = it },
                    onOpenBook = { bookId, cfi -> backStack.add(ReaderKey(bookId, cfi)) },
                    onOpenSettings = { backStack.add(SettingsCategoryKey(it)) },
                )
            }
            entry<ReaderKey> { key ->
                ReaderScreen(bookId = key.bookId, openAt = key.cfi, onBack = back)
            }
            entry<SettingsCategoryKey> { key ->
                // Each phase adds its category here.
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

/** Material shared-axis distance. */
private val SLIDE_DISTANCE = 30.dp

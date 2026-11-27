package com.zenread.app

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenread.app.ui.ZenReadApp
import com.zenread.core.datastore.AppearanceSettingsRepository
import com.zenread.core.designsystem.theme.ZenReadTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The only activity. It is an [AppCompatActivity] so that the in-app language
 * (`AppCompatDelegate.setApplicationLocales`) also works before Android 13.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var appearanceRepository: AppearanceSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read the appearance before the first frame so the app never flashes the default theme.
        // The file holds four values; after the first read DataStore serves it from memory.
        val initial = runBlocking { appearanceRepository.settings.first() }
        applySystemBarStyle(initial.themeMode.isDark(systemDark()))

        setContent {
            val appearance by appearanceRepository.settings.collectAsStateWithLifecycle(initial)
            val darkTheme = appearance.themeMode.isDark(isSystemInDarkTheme())
            // Status and navigation bar icons follow the app's light/dark choice, not the system's.
            DisposableEffect(darkTheme) {
                applySystemBarStyle(darkTheme)
                onDispose {}
            }
            ZenReadTheme(
                darkTheme = darkTheme,
                dynamicColor = appearance.dynamicColor,
                pureBlack = appearance.pureBlack,
                accent = appearance.accent,
            ) {
                ZenReadApp()
            }
        }
    }

    private fun systemDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun applySystemBarStyle(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            // Same scrims as enableEdgeToEdge's defaults (3-button navigation only).
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

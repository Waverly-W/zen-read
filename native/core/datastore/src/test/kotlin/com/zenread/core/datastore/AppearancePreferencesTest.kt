package com.zenread.core.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zenread.core.model.AppearanceSettings
import com.zenread.core.model.ThemeAccent
import com.zenread.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearancePreferencesTest {
    @Test
    fun emptyStoreGivesDefaults() {
        assertEquals(AppearanceSettings(), AppearancePreferences.read(emptyPreferences()))
    }

    @Test
    fun everySettingRoundTrips() {
        val variants = ThemeMode.entries.flatMap { mode ->
            ThemeAccent.entries.flatMap { accent ->
                listOf(true, false).flatMap { dynamic ->
                    listOf(true, false).map { black -> AppearanceSettings(mode, dynamic, accent, black) }
                }
            }
        }
        variants.forEach { settings ->
            val prefs = mutablePreferencesOf()
            AppearancePreferences.write(prefs, settings)
            assertEquals(settings, AppearancePreferences.read(prefs))
        }
    }

    @Test
    fun storedNamesAreStable() {
        val prefs = mutablePreferencesOf()
        AppearancePreferences.write(prefs, AppearanceSettings(ThemeMode.DARK, false, ThemeAccent.ROSE, true))
        assertEquals("DARK", prefs[stringPreferencesKey("theme_mode")])
        assertEquals("ROSE", prefs[stringPreferencesKey("accent")])
        assertEquals(false, prefs[booleanPreferencesKey("dynamic_color")])
        assertEquals(true, prefs[booleanPreferencesKey("pure_black")])
    }

    @Test
    fun unknownValuesFallBackToDefaults() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("theme_mode") to "SEPIA",
            stringPreferencesKey("accent") to "",
            booleanPreferencesKey("pure_black") to true,
        )
        val read = AppearancePreferences.read(prefs)
        assertEquals(ThemeMode.SYSTEM, read.themeMode)
        assertEquals(ThemeAccent.TEAL, read.accent)
        assertEquals(true, read.pureBlack)
        assertEquals(true, read.dynamicColor)
    }
}

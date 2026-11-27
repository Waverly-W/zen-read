package com.zenread.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zenread.core.model.AppearanceSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "appearance_settings")

/** Settings → Appearance: theme mode, dynamic color, accent, pure black. */
@Singleton
class AppearanceSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.appearanceDataStore

    val settings: Flow<AppearanceSettings> = store.data.map(AppearancePreferences::read).distinctUntilChanged()

    suspend fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        store.edit { prefs -> AppearancePreferences.write(prefs, transform(AppearancePreferences.read(prefs))) }
    }
}

/** Mapping between [AppearanceSettings] and its DataStore keys; unknown or missing values fall back to defaults. */
internal object AppearancePreferences {
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    private val ACCENT = stringPreferencesKey("accent")
    private val PURE_BLACK = booleanPreferencesKey("pure_black")

    fun read(prefs: Preferences): AppearanceSettings {
        val defaults = AppearanceSettings()
        return AppearanceSettings(
            themeMode = prefs[THEME_MODE].toEnum(defaults.themeMode),
            dynamicColor = prefs[DYNAMIC_COLOR] ?: defaults.dynamicColor,
            accent = prefs[ACCENT].toEnum(defaults.accent),
            pureBlack = prefs[PURE_BLACK] ?: defaults.pureBlack,
        )
    }

    fun write(prefs: MutablePreferences, settings: AppearanceSettings) {
        prefs[THEME_MODE] = settings.themeMode.name
        prefs[DYNAMIC_COLOR] = settings.dynamicColor
        prefs[ACCENT] = settings.accent.name
        prefs[PURE_BLACK] = settings.pureBlack
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}

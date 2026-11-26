package com.zenread.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zenread.core.model.PageTurnStyle
import com.zenread.core.model.ReaderSettings
import com.zenread.core.model.ReadingTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerDataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

@Singleton
class ReaderSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.readerDataStore

    val settings: Flow<ReaderSettings> = store.data.map { it.toSettings() }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[FONT_SCALE] = next.fontScale
            prefs[LINE_HEIGHT] = next.lineHeight
            prefs[SIDE_MARGIN] = next.sideMargin
            prefs[PAGE_TURN] = next.pageTurn.name
            prefs[THEME] = next.theme.name
        }
    }

    private fun Preferences.toSettings(): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            fontScale = this[FONT_SCALE] ?: defaults.fontScale,
            lineHeight = this[LINE_HEIGHT] ?: defaults.lineHeight,
            sideMargin = this[SIDE_MARGIN] ?: defaults.sideMargin,
            pageTurn = this[PAGE_TURN].toEnum(defaults.pageTurn),
            theme = this[THEME].toEnum(defaults.theme),
        )
    }

    private companion object {
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val SIDE_MARGIN = floatPreferencesKey("side_margin")
        val PAGE_TURN = stringPreferencesKey("page_turn")
        val THEME = stringPreferencesKey("theme")
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

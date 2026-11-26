package com.zenread.data.tts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zenread.core.datastore.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ttsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tts_settings")

/** Read-aloud preferences (own DataStore file); the OpenAI key lives in [SecretStore]. */
@Singleton
class TtsSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore,
) {
    private val store = context.ttsDataStore

    val settings: Flow<TtsSettings> = store.data.map { it.toSettings() }

    suspend fun current(): TtsSettings = settings.first()

    suspend fun update(transform: (TtsSettings) -> TtsSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[ENGINE] = next.engine.name
            if (next.systemVoice == null) prefs.remove(SYSTEM_VOICE) else prefs[SYSTEM_VOICE] = next.systemVoice
            prefs[EDGE_VOICE] = next.edgeVoice
            prefs[OPENAI_BASE_URL] = next.openAiBaseUrl
            prefs[OPENAI_MODEL] = next.openAiModel
            prefs[OPENAI_VOICE] = next.openAiVoice
            prefs[RATE] = next.rate.coerceIn(TtsSettings.RATE_RANGE)
            prefs[PITCH] = next.pitch.coerceIn(TtsSettings.PITCH_RANGE)
            prefs[SLEEP_MINUTES] = next.sleepTimerMinutes
        }
    }

    suspend fun openAiKey(): String? = secrets.get(OPENAI_KEY)

    suspend fun hasOpenAiKey(): Boolean = secrets.contains(OPENAI_KEY)

    /** Null or blank removes the key. */
    suspend fun setOpenAiKey(key: String?) = secrets.set(OPENAI_KEY, key?.trim())

    private fun Preferences.toSettings(): TtsSettings {
        val defaults = TtsSettings()
        return TtsSettings(
            engine = this[ENGINE]?.let { name -> TtsEngineType.entries.firstOrNull { it.name == name } } ?: defaults.engine,
            systemVoice = this[SYSTEM_VOICE],
            edgeVoice = this[EDGE_VOICE] ?: defaults.edgeVoice,
            openAiBaseUrl = this[OPENAI_BASE_URL] ?: defaults.openAiBaseUrl,
            openAiModel = this[OPENAI_MODEL] ?: defaults.openAiModel,
            openAiVoice = this[OPENAI_VOICE] ?: defaults.openAiVoice,
            rate = this[RATE] ?: defaults.rate,
            pitch = this[PITCH] ?: defaults.pitch,
            sleepTimerMinutes = this[SLEEP_MINUTES] ?: defaults.sleepTimerMinutes,
        )
    }

    private companion object {
        const val OPENAI_KEY = "tts.openai.apiKey"
        val ENGINE = stringPreferencesKey("engine")
        val SYSTEM_VOICE = stringPreferencesKey("system_voice")
        val EDGE_VOICE = stringPreferencesKey("edge_voice")
        val OPENAI_BASE_URL = stringPreferencesKey("openai_base_url")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val OPENAI_VOICE = stringPreferencesKey("openai_voice")
        val RATE = floatPreferencesKey("rate")
        val PITCH = floatPreferencesKey("pitch")
        val SLEEP_MINUTES = intPreferencesKey("sleep_minutes")
    }
}

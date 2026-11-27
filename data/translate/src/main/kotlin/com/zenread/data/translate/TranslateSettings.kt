package com.zenread.data.translate

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zenread.core.datastore.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How translated paragraphs appear in the page. Values match foliate-js `TranslationMode`. */
enum class PageTranslationMode(val jsName: String) {
    BILINGUAL("bilingual"),
    TRANSLATION_ONLY("translation-only"),
}

/** Color of translated paragraphs, resolved against the reading theme by the reader. */
enum class TranslationColor { TEXT, MUTED, ACCENT }

data class TranslateSettings(
    /** Default [TranslatorDescriptor.id] for both selection and page translation. */
    val serviceId: String = TranslatorRegistry.DEFAULT_ID,
    /** Null follows the system language. */
    val targetLanguage: Language? = null,
    val pageMode: PageTranslationMode = PageTranslationMode.BILINGUAL,
    val pageColor: TranslationColor = TranslationColor.MUTED,
    val pageItalic: Boolean = false,
    /** Pages ahead foliate-js pre-translates while page translation is on (0–3). */
    val prefetchPages: Int = 0,
) {
    /** The effective target: the chosen language, else the system language, else English. */
    fun resolvedTarget(locale: Locale = Locale.getDefault()): Language =
        targetLanguage ?: Language.fromLocale(locale) ?: Language.ENGLISH

    companion object {
        const val MAX_PREFETCH_PAGES = 3
    }
}

private val Context.translateDataStore: DataStore<Preferences> by preferencesDataStore(name = "translate_settings")

@Singleton
class TranslateSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.translateDataStore

    val settings: Flow<TranslateSettings> = store.data.map { it.toSettings() }

    suspend fun current(): TranslateSettings = settings.first()

    suspend fun update(transform: (TranslateSettings) -> TranslateSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[SERVICE] = next.serviceId
            if (next.targetLanguage == null) prefs.remove(TARGET) else prefs[TARGET] = next.targetLanguage.code
            prefs[PAGE_MODE] = next.pageMode.name
            prefs[PAGE_COLOR] = next.pageColor.name
            prefs[PAGE_ITALIC] = next.pageItalic
            prefs[PREFETCH] = next.prefetchPages.coerceIn(0, TranslateSettings.MAX_PREFETCH_PAGES)
        }
    }

    private fun Preferences.toSettings(): TranslateSettings {
        val defaults = TranslateSettings()
        return TranslateSettings(
            serviceId = this[SERVICE] ?: defaults.serviceId,
            targetLanguage = this[TARGET]?.let { code -> Language.entries.firstOrNull { it.code == code } },
            pageMode = this[PAGE_MODE].toEnum(defaults.pageMode),
            pageColor = this[PAGE_COLOR].toEnum(defaults.pageColor),
            pageItalic = this[PAGE_ITALIC] ?: defaults.pageItalic,
            prefetchPages = (this[PREFETCH] ?: defaults.prefetchPages).coerceIn(0, TranslateSettings.MAX_PREFETCH_PAGES),
        )
    }

    private companion object {
        val SERVICE = stringPreferencesKey("service")
        val TARGET = stringPreferencesKey("target_language")
        val PAGE_MODE = stringPreferencesKey("page_mode")
        val PAGE_COLOR = stringPreferencesKey("page_color")
        val PAGE_ITALIC = booleanPreferencesKey("page_italic")
        val PREFETCH = intPreferencesKey("prefetch_pages")
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

/** API keys per translator, encrypted in [SecretStore] as `translate.<translatorId>.<field>`. */
@Singleton
class TranslateCredentials @Inject constructor(
    private val secrets: SecretStore,
) {
    suspend fun load(descriptor: TranslatorDescriptor): Credentials =
        if (descriptor.credentialFields.isEmpty()) {
            Credentials.NONE
        } else {
            Credentials(
                descriptor.credentialFields.mapNotNull { field ->
                    secrets.get(secretName(descriptor.id, field.name))?.let { field.name to it }
                }.toMap(),
            )
        }

    /** Saves [values] (field name → value); blank values remove the entry. */
    suspend fun save(descriptor: TranslatorDescriptor, values: Map<String, String>) {
        descriptor.credentialFields.forEach { field ->
            values[field.name]?.let { secrets.set(secretName(descriptor.id, field.name), it.trim()) }
        }
    }

    companion object {
        fun secretName(translatorId: String, field: String) = "translate.$translatorId.$field"
    }
}

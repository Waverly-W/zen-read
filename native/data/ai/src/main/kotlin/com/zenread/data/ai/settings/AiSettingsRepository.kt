package com.zenread.data.ai.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zenread.core.datastore.SecretStore
import com.zenread.data.ai.chat.ModelRef
import com.zenread.data.ai.provider.ProviderConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.aiSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "ai_settings")

/** Book-index (RAG) options. */
@Serializable
data class IndexOptions(
    /** Target characters per chunk. */
    val chunkSize: Int = 800,
    /** Characters repeated between neighbouring chunks of one chapter. */
    val chunkOverlap: Int = 120,
    /** Passages retrieved per question. */
    val topK: Int = 6,
    /** Use the book index (when built) to answer free-form questions. */
    val useForQuestions: Boolean = true,
    /** Only index on Wi-Fi / unmetered networks. */
    val unmeteredOnly: Boolean = false,
) {
    fun sanitized() = copy(
        chunkSize = chunkSize.coerceIn(200, 4000),
        chunkOverlap = chunkOverlap.coerceIn(0, chunkSize.coerceIn(200, 4000) / 2),
        topK = topK.coerceIn(1, 20),
    )
}

@Serializable
data class AiSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val chatModel: ModelRef? = null,
    val embeddingModel: ModelRef? = null,
    val index: IndexOptions = IndexOptions(),
) {
    fun provider(id: String): ProviderConfig? = providers.firstOrNull { it.id == id }

    /** The default chat model if its provider still exists and is enabled. */
    val resolvedChatModel: ModelRef?
        get() = chatModel?.takeIf { ref -> provider(ref.providerId)?.enabled == true && ref.model.isNotBlank() }

    val resolvedEmbeddingModel: ModelRef?
        get() = embeddingModel?.takeIf { ref -> provider(ref.providerId)?.enabled == true && ref.model.isNotBlank() }
}

/**
 * Provider list and AI preferences (DataStore file `ai_settings`). API keys are kept
 * separately in [SecretStore]; nothing here is secret.
 */
@Singleton
class AiSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore,
) {
    private val store = context.aiSettingsStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    val settings: Flow<AiSettings> = store.data.map { prefs -> decode(prefs[KEY]) }

    suspend fun current(): AiSettings = settings.first()

    suspend fun update(transform: (AiSettings) -> AiSettings) {
        store.edit { prefs -> prefs[KEY] = json.encodeToString(AiSettings.serializer(), transform(decode(prefs[KEY]))) }
    }

    /** Adds or replaces [provider]; a non-null [apiKey] replaces the stored key (blank clears it). */
    suspend fun saveProvider(provider: ProviderConfig, apiKey: String?) {
        if (apiKey != null) secrets.set(ProviderConfig.apiKeySecretName(provider.id), apiKey.trim())
        update { settings ->
            val exists = settings.providers.any { it.id == provider.id }
            val providers = if (exists) {
                settings.providers.map { if (it.id == provider.id) provider else it }
            } else {
                settings.providers + provider
            }
            // The first usable provider becomes the default so a fresh setup just works.
            settings.copy(
                providers = providers,
                chatModel = settings.chatModel ?: provider.models.firstOrNull()?.let { ModelRef(provider.id, it) },
                embeddingModel = settings.embeddingModel
                    ?: provider.embeddingModels.firstOrNull()?.let { ModelRef(provider.id, it) },
            )
        }
    }

    suspend fun deleteProvider(id: String) {
        secrets.set(ProviderConfig.apiKeySecretName(id), null)
        update { settings ->
            settings.copy(
                providers = settings.providers.filterNot { it.id == id },
                chatModel = settings.chatModel?.takeIf { it.providerId != id },
                embeddingModel = settings.embeddingModel?.takeIf { it.providerId != id },
            )
        }
    }

    /** The raw key field (possibly several keys). Never log it. */
    suspend fun apiKeyField(providerId: String): String? = secrets.get(ProviderConfig.apiKeySecretName(providerId))

    private fun decode(raw: String?): AiSettings =
        raw?.let { runCatching { json.decodeFromString(AiSettings.serializer(), it) }.getOrNull() } ?: AiSettings()

    private companion object {
        val KEY = stringPreferencesKey("settings_json")
    }
}

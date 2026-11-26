package com.zenread.data.ai.provider

import kotlinx.serialization.Serializable

/** Wire protocol of a provider. Brand (DeepSeek, OpenRouter, Ollama…) is just a base URL. */
@Serializable
enum class ProviderKind { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

/** Saved sampling parameters for one model; null means "provider default". */
@Serializable
data class ModelParams(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/**
 * A configured provider. The API key is not part of this object: it lives encrypted
 * in `SecretStore` under [apiKeySecretName]. A key field may hold several keys
 * (one per line or comma separated); they are used in rotation.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    /** Chat models offered in pickers (fetched from the provider or typed in). */
    val models: List<String> = emptyList(),
    /** Embedding models offered in the embedding picker. */
    val embeddingModels: List<String> = emptyList(),
    val modelParams: Map<String, ModelParams> = emptyMap(),
    val enabled: Boolean = true,
) {
    fun paramsFor(model: String): ModelParams = modelParams[model] ?: ModelParams()

    companion object {
        fun apiKeySecretName(providerId: String) = "ai.provider.$providerId.apiKey"
    }
}

/** Starting points for the "add provider" form. */
enum class ProviderPreset(
    val displayName: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val suggestedEmbeddingModel: String? = null,
    /** False for local servers such as Ollama. */
    val needsKey: Boolean = true,
) {
    OPENAI("OpenAI", ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "text-embedding-3-small"),
    ANTHROPIC("Anthropic", ProviderKind.ANTHROPIC, "https://api.anthropic.com"),
    GEMINI("Google Gemini", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com", "gemini-embedding-001"),
    DEEPSEEK("DeepSeek", ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1"),
    OPENROUTER("OpenRouter", ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1"),
    SILICONFLOW("SiliconFlow", ProviderKind.OPENAI_COMPATIBLE, "https://api.siliconflow.cn/v1", "BAAI/bge-m3"),
    OLLAMA("Ollama", ProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.2:11434/v1", "nomic-embed-text", needsKey = false),
    CUSTOM("OpenAI-compatible", ProviderKind.OPENAI_COMPATIBLE, "https://"),
}

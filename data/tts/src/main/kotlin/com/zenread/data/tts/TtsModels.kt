package com.zenread.data.tts

/** Speech engines. [online] engines synthesize audio over the network and benefit from prefetching. */
enum class TtsEngineType(val online: Boolean) {
    SYSTEM(online = false),
    EDGE(online = true),
    OPENAI(online = true),
}

data class TtsVoice(
    /** Engine-specific identifier passed back to the engine (Edge `ShortName`, system voice name…). */
    val id: String,
    val name: String,
    /** BCP-47 language tag, may be empty. */
    val locale: String,
    val gender: String? = null,
    /** System voices that need a network connection. */
    val requiresNetwork: Boolean = false,
)

/** One unit of speech produced by the text source, usually a sentence. */
data class TtsSentence(
    val text: String,
    /** Position in the book; used to highlight the sentence and follow it with the page. */
    val cfi: String? = null,
    /** Section (spine item) the sentence belongs to; drives the end-of-chapter sleep timer. */
    val section: Int? = null,
)

/** Sleep timer choices. [minutes] of 0 means off; [END_OF_CHAPTER] stops when the chapter ends. */
data class SleepTimerOption(val minutes: Int) {
    companion object {
        const val OFF = 0
        const val END_OF_CHAPTER = -1
        val choices = listOf(OFF, 15, 30, 45, 60, 90, END_OF_CHAPTER)
    }
}

data class TtsSettings(
    val engine: TtsEngineType = TtsEngineType.SYSTEM,
    /** System voice name; null follows the system default. */
    val systemVoice: String? = null,
    val edgeVoice: String = DEFAULT_EDGE_VOICE,
    /** Base URL of an OpenAI-compatible API, e.g. `https://api.openai.com/v1`. */
    val openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL,
    val openAiModel: String = DEFAULT_OPENAI_MODEL,
    val openAiVoice: String = DEFAULT_OPENAI_VOICE,
    /** 1.0 is normal speed. */
    val rate: Float = 1f,
    /** 1.0 is normal pitch. Not every engine supports it, see [supportsPitch]. */
    val pitch: Float = 1f,
    /** Default sleep timer, see [SleepTimerOption]. */
    val sleepTimerMinutes: Int = SleepTimerOption.OFF,
) {
    /** Voice for the selected engine; null means the engine default. */
    val voice: String?
        get() = when (engine) {
            TtsEngineType.SYSTEM -> systemVoice
            TtsEngineType.EDGE -> edgeVoice
            TtsEngineType.OPENAI -> openAiVoice
        }

    companion object {
        const val DEFAULT_EDGE_VOICE = "zh-CN-XiaoxiaoNeural"
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini-tts"
        const val DEFAULT_OPENAI_VOICE = "alloy"
        val RATE_RANGE = 0.5f..3f
        val PITCH_RANGE = 0.5f..2f

        /** OpenAI only follows pitch through model instructions, which legacy models ignore. */
        fun supportsPitch(engine: TtsEngineType, openAiModel: String): Boolean = when (engine) {
            TtsEngineType.SYSTEM, TtsEngineType.EDGE -> true
            TtsEngineType.OPENAI -> !OpenAiTtsRequest.isLegacyModel(openAiModel)
        }
    }
}

/** Raised for failures the user can act on (missing key, HTTP errors, no voice data). */
class TtsException(val reason: Reason, message: String, val statusCode: Int? = null) : Exception(message) {
    enum class Reason { CONFIGURATION, NETWORK, HTTP, ENGINE_UNAVAILABLE, INVALID_AUDIO }
}

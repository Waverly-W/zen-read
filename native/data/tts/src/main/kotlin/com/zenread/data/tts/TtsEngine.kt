package com.zenread.data.tts

/** Voice parameters for one utterance. */
data class SpeechParams(val voice: String?, val rate: Float, val pitch: Float)

/**
 * Where the engine is inside the current sentence, for following it on the page.
 * System engines report a character [offset]; audio-clip engines a playback [fraction].
 */
data class SpeechProgress(val offset: Int? = null, val fraction: Float? = null)

/**
 * A swappable speech engine. Every call happens on the main thread; [speak] suspends until the
 * sentence has been spoken and cancelling it silences the engine immediately.
 */
interface TtsEngine {
    val type: TtsEngineType

    suspend fun speak(sentence: TtsSentence, params: SpeechParams, onProgress: (SpeechProgress) -> Unit)

    /** True when the engine benefits from [prefetch] (online engines). */
    val wantsPrefetch: Boolean get() = false

    /** Upcoming sentences, the current one first. Engines may synthesize them ahead of time. */
    fun prefetch(upcoming: List<TtsSentence>, params: SpeechParams) = Unit

    /**
     * Pauses in the middle of the sentence. Returns false when unsupported; the caller then
     * cancels [speak] and restarts the sentence on resume.
     */
    fun pause(): Boolean = false

    fun resume() = Unit

    suspend fun voices(): List<TtsVoice>

    fun release()
}

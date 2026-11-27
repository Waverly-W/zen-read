package com.zenread.data.tts

/** An online engine that turns text into an audio clip (MP3). */
interface SpeechSynthesizer {
    val type: TtsEngineType

    /** Returns encoded audio. Throws [TtsException] for failures worth showing to the user. */
    suspend fun synthesize(text: String, voice: String?, rate: Float, pitch: Float): ByteArray

    suspend fun voices(): List<TtsVoice>
}

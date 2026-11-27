package com.zenread.data.tts

import android.content.Context
import com.zenread.data.tts.edge.EdgeTtsClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Creates engines by type; each playback session owns (and releases) its engine. */
@Singleton
class TtsEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val edge: EdgeTtsClient,
    private val openAi: OpenAiTtsClient,
) {
    fun create(type: TtsEngineType): TtsEngine = when (type) {
        TtsEngineType.SYSTEM -> SystemTtsEngine(context)
        TtsEngineType.EDGE -> OnlineTtsEngine(edge, ClipPlayer(context))
        TtsEngineType.OPENAI -> OnlineTtsEngine(openAi, ClipPlayer(context))
    }

    /** Voices for the settings page, without starting a playback session. */
    suspend fun voices(type: TtsEngineType): List<TtsVoice> = when (type) {
        TtsEngineType.EDGE -> edge.voices()
        TtsEngineType.OPENAI -> openAi.voices()
        TtsEngineType.SYSTEM -> {
            val engine = SystemTtsEngine(context)
            try {
                engine.voices()
            } finally {
                engine.release()
            }
        }
    }
}

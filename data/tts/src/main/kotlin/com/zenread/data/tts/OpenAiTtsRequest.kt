// Request shape ported from Modu lib/service/tts/openai_tts_backend.dart.
package com.zenread.data.tts

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pure request building for OpenAI-compatible `POST {base}/audio/speech`. */
object OpenAiTtsRequest {
    /** Voices of OpenAI's own API; compatible providers may accept others, typed in settings. */
    val builtInVoices = listOf(
        "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse",
    )

    fun isLegacyModel(model: String): Boolean = model.trim() in setOf("tts-1", "tts-1-hd")

    /**
     * Resolves the speech endpoint from a base URL. Accepts `https://host/v1`,
     * `https://host/v1/` and a full `…/audio/speech` URL (the upstream setting's format).
     */
    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) { "Base URL must be http(s)" }
        return if (base.endsWith("/audio/speech")) base else "$base/audio/speech"
    }

    fun body(text: String, model: String, voice: String, rate: Float, pitch: Float): JsonObject = buildJsonObject {
        put("model", model.trim())
        put("voice", voice.trim())
        put("input", text)
        val instructions = if (isLegacyModel(model)) "" else pitchInstructions(pitch)
        if (instructions.isNotEmpty()) put("instructions", instructions)
        // A neutral rate must not override a provider's pacing; other values use `speed`.
        if (rate.isFinite() && rate != 1f) put("speed", rate.coerceIn(0.25f, 4f))
        put("response_format", "mp3")
    }

    private fun pitchInstructions(pitch: Float): String {
        if (!pitch.isFinite() || pitch == 1f) return ""
        val factor = String.format(Locale.ROOT, "%.2f", pitch.coerceIn(0.5f, 2f))
        val direction = if (pitch > 1f) "higher" else "lower"
        return "Use a $direction pitch, at $factor times the default pitch. Keep the speaking pace and volume unchanged."
    }
}

package com.zenread.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Android's built-in `TextToSpeech`, which plays audio itself (no prefetch, no mid-sentence pause). */
class SystemTtsEngine(private val context: Context) : TtsEngine {
    override val type = TtsEngineType.SYSTEM

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<TextToSpeech>? = null

    private class Pending(val continuation: CancellableContinuation<Unit>, val onRange: (Int) -> Unit)

    private val pending = ConcurrentHashMap<String, Pending>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
            pending.remove(utteranceId)?.continuation?.takeIf { it.isActive }?.resume(Unit)
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            pending.remove(utteranceId)?.continuation?.cancel()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String, errorCode: Int) {
            // Only the numeric code: never the book's text.
            Log.w(TAG, "System TTS error $errorCode")
            val reason = if (errorCode == TextToSpeech.ERROR_NETWORK || errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT) {
                TtsException.Reason.NETWORK
            } else {
                TtsException.Reason.ENGINE_UNAVAILABLE
            }
            pending.remove(utteranceId)?.continuation?.takeIf { it.isActive }
                ?.resumeWithException(TtsException(reason, "System TTS error $errorCode"))
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            val entry = pending[utteranceId] ?: return
            main.post { entry.onRange(start) }
        }
    }

    private suspend fun engine(): TextToSpeech {
        ready?.let { return it.await() }
        val deferred = CompletableDeferred<TextToSpeech>()
        ready = deferred
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            val engine = created
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.setOnUtteranceProgressListener(listener)
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                deferred.complete(engine)
            } else {
                deferred.completeExceptionally(
                    TtsException(TtsException.Reason.ENGINE_UNAVAILABLE, "No system TTS engine ($status)"),
                )
            }
        }
        tts = created
        return try {
            withTimeout(INIT_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            ready = null
            tts?.shutdown()
            tts = null
            throw e as? TtsException ?: TtsException(TtsException.Reason.ENGINE_UNAVAILABLE, "System TTS did not start")
        }
    }

    override suspend fun speak(sentence: TtsSentence, params: SpeechParams, onProgress: (SpeechProgress) -> Unit) {
        val engine = engine()
        engine.setSpeechRate(params.rate)
        engine.setPitch(params.pitch)
        selectVoice(engine, params.voice)
        val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(MAX_UTTERANCE)
        var base = 0
        for (piece in TtsText.split(sentence.text, limit)) {
            val offset = sentence.text.indexOf(piece, base).coerceAtLeast(base)
            speakPiece(engine, piece) { start -> onProgress(SpeechProgress(offset = offset + start)) }
            base = offset + piece.length
        }
    }

    private suspend fun speakPiece(engine: TextToSpeech, text: String, onRange: (Int) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            val id = UUID.randomUUID().toString()
            pending[id] = Pending(continuation, onRange)
            continuation.invokeOnCancellation {
                if (pending.remove(id) != null) engine.stop()
            }
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result != TextToSpeech.SUCCESS) {
                pending.remove(id)
                continuation.resumeWithException(TtsException(TtsException.Reason.ENGINE_UNAVAILABLE, "System TTS rejected text"))
            }
        }

    private var appliedVoice: String? = null

    private fun selectVoice(engine: TextToSpeech, name: String?) {
        if (name == appliedVoice) return
        val voice = name?.let { wanted -> runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == wanted } }
        if (voice != null) {
            engine.voice = voice
        } else if (name == null) {
            runCatching { engine.defaultVoice }.getOrNull()?.let { engine.voice = it }
        }
        appliedVoice = name
    }

    override suspend fun voices(): List<TtsVoice> {
        val engine = engine()
        val voices: Set<Voice> = runCatching { engine.voices }.getOrNull().orEmpty()
        return voices
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .map { voice ->
                TtsVoice(
                    id = voice.name,
                    name = voice.locale.getDisplayName(Locale.getDefault()),
                    locale = voice.locale.toLanguageTag(),
                    requiresNetwork = voice.isNetworkConnectionRequired,
                )
            }
            .sortedWith(compareBy({ it.locale }, { it.requiresNetwork }, { it.id }))
    }

    override fun release() {
        pending.values.forEach { it.continuation.cancel() }
        pending.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = null
        appliedVoice = null
    }

    private companion object {
        const val TAG = "ZenTts"
        const val INIT_TIMEOUT_MS = 10_000L
        const val MAX_UTTERANCE = 1_000
    }
}

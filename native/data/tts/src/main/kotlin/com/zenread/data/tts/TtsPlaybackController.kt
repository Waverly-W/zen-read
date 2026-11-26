package com.zenread.data.tts

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TtsStatus { IDLE, PREPARING, PLAYING, PAUSED }

data class TtsPlaybackState(
    val status: TtsStatus = TtsStatus.IDLE,
    val book: TtsBookInfo? = null,
    /** Chapter of the sentence being read (from foliate-js `onTtsChapter`). */
    val chapter: String? = null,
    val sentence: TtsSentence? = null,
    val engine: TtsEngineType = TtsEngineType.SYSTEM,
    val rate: Float = 1f,
    /** Armed sleep timer, see [SleepTimerOption]. */
    val sleepTimerMinutes: Int = SleepTimerOption.OFF,
    val sleepRemainingMillis: Long? = null,
    /** Last failure; playback is paused on the sentence that failed so play retries it. */
    val error: TtsException? = null,
) {
    val active: Boolean get() = status != TtsStatus.IDLE
    val playing: Boolean get() = status == TtsStatus.PLAYING || status == TtsStatus.PREPARING
}

/**
 * The read-aloud session: pulls sentences from a [TtsTextSource] (the reader's WebView), speaks
 * them with the configured [TtsEngine], and publishes state for the reader UI and the media
 * session ([TtsPlaybackService]). One session at a time; everything runs on the main thread.
 *
 * Transport commands are serialized with a mutex. The playback loop itself is not under the
 * mutex: commands cancel and join it, then move the cursor and relaunch it.
 */
@Singleton
class TtsPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: TtsSettingsRepository,
    private val engines: TtsEngineFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

    private var settings = TtsSettings()
    private var source: TtsTextSource? = null
    private var engine: TtsEngine? = null
    private var loop: Job? = null
    private var current: TtsSentence? = null

    /** The engine paused mid-sentence and [loop] is still suspended in `speak`. */
    private var pausedInEngine = false
    private var resumeOnFocusGain = false

    private val sleepTimer = SleepTimer()
    private var sleepTicker: Job? = null

    /** A session whose WebView is being rebuilt (configuration change). */
    private data class Detached(val bookId: Long, val cfi: String?, val wasPlaying: Boolean)

    private var detached: Detached? = null

    private val environment by lazy {
        PlaybackEnvironment(
            context,
            onFocusChange = ::onFocusChange,
            onNoisy = { pause() },
        )
    }

    init {
        scope.launch {
            settingsRepository.settings.collect { next ->
                settings = next
                _state.update { it.copy(rate = next.rate) }
            }
        }
    }

    // --- Commands ------------------------------------------------------------------------------

    /**
     * Starts reading [book] from [fromCfi] (or the visible page), replacing any other session.
     * Call from the foreground: it starts [TtsPlaybackService].
     */
    fun start(source: TtsTextSource, book: TtsBookInfo, fromCfi: String? = null) = command {
        stopLocked()
        settings = settingsRepository.current()
        val engine = engines.create(settings.engine).also { engine = it }
        this.source = source
        resumeOnFocusGain = false
        sleepTimer.set(settings.sleepTimerMinutes, now(), playing = true, chapter = null)
        _state.value = TtsPlaybackState(
            status = TtsStatus.PREPARING,
            book = book,
            engine = engine.type,
            rate = settings.rate,
            sleepTimerMinutes = settings.sleepTimerMinutes,
            sleepRemainingMillis = sleepTimer.remaining(now()),
        )
        startService()
        updateSleepTicker()
        // Denied during a call, for example: place the cursor and wait for play.
        val focused = environment.requestFocus()
        if (!placeCursor(source, fromCfi, engine)) return@command
        if (focused) {
            launchLoop()
        } else {
            Log.w(TAG, "Audio focus denied")
            sleepTimer.onPause(now())
            _state.update { it.copy(status = TtsStatus.PAUSED) }
        }
    }

    /**
     * Puts the page's speech cursor at [fromCfi] (or the visible page) and makes it [current].
     * On failure the session stays paused with an error, so play retries and stop dismisses.
     */
    private suspend fun placeCursor(source: TtsTextSource, fromCfi: String?, engine: TtsEngine): Boolean {
        val first = try {
            source.start(fromCfi, paragraphMode = engine.type.online)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not place the read-aloud cursor: ${e.javaClass.simpleName}")
            null
        }
        current = first
        if (first == null) {
            sleepTimer.onPause(now())
            _state.update {
                it.copy(status = TtsStatus.PAUSED, error = TtsException(TtsException.Reason.ENGINE_UNAVAILABLE, "No text"))
            }
        }
        return first != null
    }

    fun play() = command { playLocked() }

    fun pause() = command {
        resumeOnFocusGain = false
        pauseLocked(abandonFocus = true)
    }

    fun togglePlayPause() = command {
        resumeOnFocusGain = false
        if (_state.value.playing) pauseLocked(abandonFocus = true) else playLocked()
    }

    fun next() = command { moveLocked(forward = true) }

    fun previous() = command { moveLocked(forward = false) }

    fun stop() = command { stopLocked() }

    fun setRate(rate: Float) {
        val value = rate.coerceIn(TtsSettings.RATE_RANGE)
        _state.update { it.copy(rate = value) }
        scope.launch { settingsRepository.update { it.copy(rate = value) } }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimer.set(minutes, now(), playing = _state.value.status == TtsStatus.PLAYING, chapter = current?.section?.toString())
        _state.update { it.copy(sleepTimerMinutes = minutes, sleepRemainingMillis = sleepTimer.remaining(now())) }
        updateSleepTicker()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Chapter title reported by the page for the sentence being read. */
    fun onChapter(bookId: Long, title: String) {
        if (_state.value.book?.bookId == bookId && _state.value.active) _state.update { it.copy(chapter = title.ifBlank { null }) }
    }

    /** True when [source]'s book is the one being read (playing or paused). */
    fun isReading(bookId: Long): Boolean = _state.value.active && _state.value.book?.bookId == bookId

    /**
     * The page behind [source] is going away. With [resumable] (a configuration change) the
     * session waits briefly for [attach] with a new page of the same book; otherwise it stops.
     */
    fun detach(source: TtsTextSource, resumable: Boolean) = command {
        if (this.source !== source) return@command
        val status = _state.value.status
        if (!resumable || status == TtsStatus.IDLE) {
            stopLocked()
            return@command
        }
        val marker = Detached(source.bookId, current?.cfi, wasPlaying = status == TtsStatus.PLAYING || status == TtsStatus.PREPARING)
        detached = marker
        loop?.cancelAndJoin()
        loop = null
        pausedInEngine = false
        this.source = null
        environment.onNotPlaying()
        sleepTimer.onPause(now())
        _state.update { it.copy(status = TtsStatus.PAUSED) }
        scope.launch {
            delay(REATTACH_TIMEOUT_MS)
            mutex.withLock { if (detached === marker) stopLocked() }
        }
    }

    /** Re-binds a session detached by a configuration change to the rebuilt page. */
    fun attach(source: TtsTextSource) = command {
        val marker = detached ?: return@command
        if (marker.bookId != source.bookId) return@command
        detached = null
        this.source = source
        val engine = engine ?: return@command stopLocked()
        val first = try {
            source.start(marker.cfi, paragraphMode = engine.type.online)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not resume read-aloud: ${e.javaClass.simpleName}")
            null
        }
        if (first == null) return@command stopLocked()
        current = first
        if (marker.wasPlaying && environment.requestFocus()) {
            engine.resume()
            launchLoop()
        } else {
            _state.update { it.copy(status = TtsStatus.PAUSED, sentence = first) }
        }
    }

    // --- Internals -----------------------------------------------------------------------------

    private fun command(block: suspend () -> Unit) {
        scope.launch { mutex.withLock { block() } }
    }

    private fun launchLoop() {
        pausedInEngine = false
        loop = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        try {
            while (true) {
                val src = source ?: return
                val eng = engine ?: return
                val sentence = current ?: run {
                    // End of the book. Stop outside this job: stop() joins the loop.
                    scope.launch(Dispatchers.Main) { stop() }
                    return
                }
                if (sleepTimer.onChapter(sentence.section?.toString())) {
                    // End-of-chapter timer: hold on the first sentence of the next chapter.
                    sleepTimer.cancel()
                    finishBySleepTimer(sentence)
                    return
                }
                if (!TtsText.isSpeakable(sentence.text)) {
                    current = src.next()
                    continue
                }
                _state.update { it.copy(status = TtsStatus.PLAYING, sentence = sentence, error = null) }
                environment.onPlaying()
                sleepTimer.onPlay(now())
                val params = SpeechParams(voiceFor(eng.type), settings.rate, settings.pitch)
                if (eng.wantsPrefetch) {
                    val upcoming = try {
                        src.peek(PREFETCH_COUNT)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        listOf(sentence)
                    }
                    eng.prefetch(upcoming.ifEmpty { listOf(sentence) }, params)
                }
                var lastFollow = 0L
                eng.speak(sentence, params) { progress ->
                    // Word callbacks can be very frequent; the page only needs a few per second.
                    val time = now()
                    if (time - lastFollow >= FOLLOW_INTERVAL_MS) {
                        lastFollow = time
                        src.followProgress(sentence, progress)
                    }
                }
                current = src.next()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Read-aloud paused after ${e.javaClass.simpleName}: ${(e as? TtsException)?.reason}")
            pausedInEngine = false
            environment.onNotPlaying()
            sleepTimer.onPause(now())
            val error = e as? TtsException ?: TtsException(TtsException.Reason.ENGINE_UNAVAILABLE, e.javaClass.simpleName)
            _state.update { it.copy(status = TtsStatus.PAUSED, error = error) }
        }
    }

    private fun finishBySleepTimer(sentence: TtsSentence) {
        pausedInEngine = false
        environment.onNotPlaying()
        environment.abandonFocus()
        _state.update {
            it.copy(status = TtsStatus.PAUSED, sentence = sentence, sleepTimerMinutes = SleepTimerOption.OFF, sleepRemainingMillis = null)
        }
        updateSleepTicker()
    }

    private suspend fun playLocked() {
        if (_state.value.status != TtsStatus.PAUSED || source == null) return
        if (!environment.requestFocus()) {
            Log.w(TAG, "Audio focus denied")
            return
        }
        val eng = engine ?: return
        val src = source ?: return
        // A failed start left no cursor: try placing it again from the visible page.
        if (current == null && !placeCursor(src, null, eng)) return
        sleepTimer.onPlay(now())
        if (pausedInEngine && loop?.isActive == true) {
            pausedInEngine = false
            eng.resume()
            environment.onPlaying()
            _state.update { it.copy(status = TtsStatus.PLAYING, error = null) }
        } else {
            eng.resume()
            loop?.cancelAndJoin()
            launchLoop()
        }
    }

    private suspend fun pauseLocked(abandonFocus: Boolean) {
        if (!_state.value.playing) return
        if (loop?.isActive == true && engine?.pause() == true) {
            pausedInEngine = true
        } else {
            loop?.cancelAndJoin()
            loop = null
            pausedInEngine = false
        }
        sleepTimer.onPause(now())
        environment.onNotPlaying()
        if (abandonFocus) environment.abandonFocus()
        _state.update { it.copy(status = TtsStatus.PAUSED, sleepRemainingMillis = sleepTimer.remaining(now())) }
    }

    private suspend fun moveLocked(forward: Boolean) {
        val src = source ?: return
        if (!_state.value.active) return
        val wasPlaying = _state.value.playing
        loop?.cancelAndJoin()
        loop = null
        pausedInEngine = false
        val moved = try {
            if (forward) src.next() else src.prev()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Read-aloud navigation failed: ${e.javaClass.simpleName}")
            null
        }
        if (moved == null && forward) {
            stopLocked()
            return
        }
        if (moved != null) current = moved
        if (wasPlaying) {
            engine?.resume()
            launchLoop()
        } else {
            _state.update { it.copy(status = TtsStatus.PAUSED, sentence = current) }
        }
    }

    private suspend fun stopLocked() {
        loop?.cancelAndJoin()
        loop = null
        pausedInEngine = false
        engine?.release()
        engine = null
        source?.stop()
        source = null
        current = null
        detached = null
        environment.onNotPlaying()
        environment.abandonFocus()
        sleepTimer.cancel()
        _state.value = TtsPlaybackState(rate = settings.rate, error = _state.value.error)
        updateSleepTicker()
    }

    private fun onFocusChange(change: FocusChange) = command {
        when (change) {
            FocusChange.LOSS -> {
                resumeOnFocusGain = false
                pauseLocked(abandonFocus = true)
            }
            FocusChange.LOSS_TRANSIENT -> if (_state.value.playing) {
                resumeOnFocusGain = true
                pauseLocked(abandonFocus = false)
            }
            FocusChange.GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                playLocked()
            }
        }
    }

    private fun updateSleepTicker() {
        if (!sleepTimer.armed || sleepTimer.endOfChapter || !_state.value.active) {
            sleepTicker?.cancel()
            sleepTicker = null
            return
        }
        if (sleepTicker?.isActive == true) return
        sleepTicker = scope.launch {
            while (isActive) {
                delay(1_000)
                val remaining = sleepTimer.remaining(now())
                _state.update { it.copy(sleepRemainingMillis = remaining) }
                if (remaining == 0L) {
                    sleepTimer.cancel()
                    _state.update { it.copy(sleepTimerMinutes = SleepTimerOption.OFF, sleepRemainingMillis = null) }
                    pause()
                    break
                }
            }
        }
    }

    private fun voiceFor(type: TtsEngineType): String? = when (type) {
        TtsEngineType.SYSTEM -> settings.systemVoice
        TtsEngineType.EDGE -> settings.edgeVoice
        TtsEngineType.OPENAI -> settings.openAiVoice
    }

    private fun startService() {
        try {
            context.startService(Intent(context, TtsPlaybackService::class.java))
        } catch (e: IllegalStateException) {
            // Background start restrictions; the session still works while the app is visible.
            Log.w(TAG, "Could not start playback service: ${e.javaClass.simpleName}")
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "ZenTts"
        const val PREFETCH_COUNT = 4
        const val REATTACH_TIMEOUT_MS = 10_000L
        const val FOLLOW_INTERVAL_MS = 400L
    }
}

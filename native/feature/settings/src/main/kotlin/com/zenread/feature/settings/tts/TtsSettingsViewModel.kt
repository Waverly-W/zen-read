package com.zenread.feature.settings.tts

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.data.tts.SpeechParams
import com.zenread.data.tts.TtsEngine
import com.zenread.data.tts.TtsEngineFactory
import com.zenread.data.tts.TtsEngineType
import com.zenread.data.tts.TtsException
import com.zenread.data.tts.TtsSentence
import com.zenread.data.tts.TtsSettings
import com.zenread.data.tts.TtsSettingsRepository
import com.zenread.data.tts.TtsVoice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceListState(
    val engine: TtsEngineType? = null,
    val loading: Boolean = false,
    val voices: List<TtsVoice> = emptyList(),
    val failed: Boolean = false,
)

enum class PreviewState { IDLE, PLAYING, FAILED }

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    private val repository: TtsSettingsRepository,
    private val engines: TtsEngineFactory,
) : ViewModel() {
    val settings: StateFlow<TtsSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _voices = MutableStateFlow(VoiceListState())
    val voices: StateFlow<VoiceListState> = _voices.asStateFlow()

    private val _hasKey = MutableStateFlow(false)
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    private val _preview = MutableStateFlow(PreviewState.IDLE)
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    private val voiceCache = mutableMapOf<TtsEngineType, List<TtsVoice>>()
    private var voiceJob: Job? = null
    private var previewJob: Job? = null
    private var previewEngine: TtsEngine? = null

    init {
        viewModelScope.launch { _hasKey.value = repository.hasOpenAiKey() }
    }

    fun update(transform: (TtsSettings) -> TtsSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun loadVoices(engine: TtsEngineType, force: Boolean = false) {
        if (!force) {
            voiceCache[engine]?.let { cached ->
                _voices.value = VoiceListState(engine, voices = cached)
                return
            }
            if (_voices.value.engine == engine && _voices.value.loading) return
        }
        voiceJob?.cancel()
        _voices.value = VoiceListState(engine, loading = true)
        voiceJob = viewModelScope.launch {
            val result = try {
                engines.voices(engine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Voice list failed: ${e.javaClass.simpleName}")
                null
            }
            if (result != null) voiceCache[engine] = result
            _voices.value = VoiceListState(engine, voices = result.orEmpty(), failed = result == null)
        }
    }

    fun saveKey(key: String) {
        viewModelScope.launch {
            repository.setOpenAiKey(key)
            _hasKey.value = repository.hasOpenAiKey()
        }
    }

    fun clearKey() = saveKey("")

    /** Speaks [sample] with the current engine, voice, speed and pitch. */
    fun preview(sample: String) {
        stopPreview()
        previewJob = viewModelScope.launch {
            val config = repository.current()
            val engine = engines.create(config.engine).also { previewEngine = it }
            _preview.value = PreviewState.PLAYING
            try {
                engine.speak(TtsSentence(sample), SpeechParams(config.voice, config.rate, config.pitch)) {}
                _preview.value = PreviewState.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Preview failed: ${(e as? TtsException)?.reason ?: e.javaClass.simpleName}")
                _preview.value = PreviewState.FAILED
            } finally {
                engine.release()
                if (previewEngine === engine) previewEngine = null
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        _preview.update { if (it == PreviewState.PLAYING) PreviewState.IDLE else it }
    }

    override fun onCleared() {
        previewEngine?.release()
    }

    private companion object {
        const val TAG = "ZenTtsSettings"
    }
}

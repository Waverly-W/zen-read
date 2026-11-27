package com.zenread.feature.settings.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.data.translate.Language
import com.zenread.data.translate.PageTranslationMode
import com.zenread.data.translate.SelectionTranslation
import com.zenread.data.translate.TranslateCredentials
import com.zenread.data.translate.TranslateException
import com.zenread.data.translate.TranslateRepository
import com.zenread.data.translate.TranslateSettings
import com.zenread.data.translate.TranslateSettingsRepository
import com.zenread.data.translate.TranslationColor
import com.zenread.data.translate.TranslatorDescriptor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ServiceTestState {
    data object Running : ServiceTestState
    data class Passed(val result: SelectionTranslation) : ServiceTestState
    data class Failed(val reason: TranslateException.Reason) : ServiceTestState
}

@HiltViewModel
class TranslateSettingsViewModel @Inject constructor(
    private val repository: TranslateRepository,
    private val settingsRepository: TranslateSettingsRepository,
    private val credentials: TranslateCredentials,
) : ViewModel() {

    val services: List<TranslatorDescriptor> = repository.translators.map { it.descriptor }

    val settings: StateFlow<TranslateSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Saved credential values: translator id → field name → value. */
    private val _savedKeys = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val savedKeys: StateFlow<Map<String, Map<String, String>>> = _savedKeys.asStateFlow()

    private val _tests = MutableStateFlow<Map<String, ServiceTestState>>(emptyMap())
    val tests: StateFlow<Map<String, ServiceTestState>> = _tests.asStateFlow()

    private val _cached = MutableStateFlow<Int?>(null)
    val cached: StateFlow<Int?> = _cached.asStateFlow()

    init {
        viewModelScope.launch {
            _savedKeys.value = services.filter { it.credentialFields.isNotEmpty() }.associate { descriptor ->
                val creds = credentials.load(descriptor)
                descriptor.id to descriptor.credentialFields.associate { it.name to creds[it.name].orEmpty() }
            }
            _cached.value = repository.cachedCount()
        }
    }

    fun setService(id: String) = update { it.copy(serviceId = id) }

    fun setTarget(language: Language?) = update { it.copy(targetLanguage = language) }

    fun setPageMode(mode: PageTranslationMode) = update { it.copy(pageMode = mode) }

    fun setPageColor(color: TranslationColor) = update { it.copy(pageColor = color) }

    fun setPageItalic(italic: Boolean) = update { it.copy(pageItalic = italic) }

    fun setPrefetchPages(pages: Int) = update { it.copy(prefetchPages = pages) }

    private fun update(transform: (TranslateSettings) -> TranslateSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun saveKeys(descriptor: TranslatorDescriptor, values: Map<String, String>) {
        viewModelScope.launch {
            credentials.save(descriptor, values)
            _savedKeys.update { it + (descriptor.id to values.mapValues { (_, v) -> v.trim() }) }
            _tests.update { it - descriptor.id }
        }
    }

    /**
     * Saves [values] (when the service has key fields), then translates a short fixed
     * sample with the service. Only runs when the user taps Test.
     */
    fun test(descriptor: TranslatorDescriptor, values: Map<String, String>?) {
        viewModelScope.launch {
            if (values != null) {
                credentials.save(descriptor, values)
                _savedKeys.update { it + (descriptor.id to values.mapValues { (_, v) -> v.trim() }) }
            }
            _tests.update { it + (descriptor.id to ServiceTestState.Running) }
            val target = settingsRepository.current().resolvedTarget()
            val sample = if (target.sameFamily(Language.ENGLISH)) SAMPLE_ZH else SAMPLE_EN
            val state = try {
                ServiceTestState.Passed(repository.translateSelection(sample, descriptor.id))
            } catch (e: TranslateException) {
                ServiceTestState.Failed(e.reason)
            }
            _tests.update { it + (descriptor.id to state) }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearCache()
            _cached.value = repository.cachedCount()
        }
    }

    private companion object {
        const val SAMPLE_EN = "Reading is to the mind what exercise is to the body."
        const val SAMPLE_ZH = "读书之于心灵，犹如运动之于身体。"
    }
}

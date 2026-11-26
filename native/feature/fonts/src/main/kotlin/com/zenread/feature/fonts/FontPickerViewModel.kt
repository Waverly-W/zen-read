package com.zenread.feature.fonts

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.core.datastore.ReaderSettingsRepository
import com.zenread.core.model.ReaderFont
import com.zenread.core.model.ReaderSettings
import com.zenread.data.fonts.DownloadState
import com.zenread.data.fonts.FontRepository
import com.zenread.data.fonts.FontTarget
import com.zenread.data.fonts.ImportResult
import com.zenread.data.fonts.InstalledFont
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FontPickerState(
    val settings: ReaderSettings,
    val installed: List<InstalledFont>,
    val downloads: Map<String, DownloadState>,
) {
    fun selected(target: FontTarget): ReaderFont? = when (target) {
        FontTarget.BODY -> settings.font
        FontTarget.LATIN -> settings.latinFont
    }
}

@HiltViewModel
class FontPickerViewModel @Inject constructor(
    private val fonts: FontRepository,
    settings: ReaderSettingsRepository,
) : ViewModel() {
    val state: StateFlow<FontPickerState?> =
        combine(settings.settings, fonts.installed, fonts.downloads, ::FontPickerState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(target: FontTarget, font: ReaderFont?) {
        viewModelScope.launch { fonts.apply(target, font) }
    }

    fun setWeight(weight: Int) {
        viewModelScope.launch { fonts.setFontWeight(weight) }
    }

    fun import(uris: List<Uri>, target: FontTarget, onResult: (ImportResult) -> Unit) {
        viewModelScope.launch { onResult(fonts.import(uris, applyTo = target)) }
    }

    fun download(id: String, target: FontTarget) = fonts.download(id, applyTo = target)

    fun cancelDownload(id: String) = fonts.cancelDownload(id)

    fun delete(fileName: String) {
        viewModelScope.launch { fonts.delete(fileName) }
    }

    fun installedFile(fileName: String): File? = fonts.installedFile(fileName)
}

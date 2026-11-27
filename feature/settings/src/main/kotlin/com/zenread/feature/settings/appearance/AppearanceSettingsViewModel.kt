package com.zenread.feature.settings.appearance

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zenread.core.datastore.AppearanceSettingsRepository
import com.zenread.core.model.AppLanguage
import com.zenread.core.model.AppearanceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppearanceSettingsViewModel @Inject constructor(
    private val repository: AppearanceSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppearanceSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}

/**
 * The per-app language. The platform owns it (Android 13+ keeps it in system settings, where the
 * user can also change it; before that AppCompat stores it), so it is not duplicated in DataStore.
 * Changing it recreates the activity.
 */
internal object AppLanguages {
    fun current(): AppLanguage = AppLanguage.fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())

    fun set(language: AppLanguage) {
        if (language == current()) return
        val locales = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

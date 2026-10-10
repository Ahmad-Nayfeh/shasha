package com.ahmadnayfeh.shasha.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.data.AppLanguage
import com.ahmadnayfeh.shasha.mobile.data.AppSettings
import com.ahmadnayfeh.shasha.mobile.data.AppTheme
import com.ahmadnayfeh.shasha.mobile.data.SettingsRepository
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorCategory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings: every change is stored at once, and every group has its own «Reset» (and one for all of them). */
class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setLanguage(language: AppLanguage) = launch { repository.setLanguage(language) }
    fun resetLanguage() = launch { repository.resetLanguage() }

    fun setTheme(theme: AppTheme) = launch { repository.setTheme(theme) }
    fun resetTheme() = launch { repository.resetTheme() }

    fun setDefaultQuality(height: Int) = launch { repository.setDefaultQuality(height) }
    fun resetDefaultQuality() = launch { repository.resetDefaultQuality() }

    fun setDownloadQuality(height: Int) = launch { repository.setDownloadQuality(height) }
    fun resetDownloadQuality() = launch { repository.resetDownloadQuality() }

    fun setSponsorBlockEnabled(enabled: Boolean) = launch { repository.setSponsorBlockEnabled(enabled) }
    fun setSponsorCategory(category: SponsorCategory, on: Boolean) = launch { repository.setSponsorCategory(category, on) }
    fun resetSponsorBlock() = launch { repository.resetSponsorBlock() }

    fun setBackgroundPlayback(enabled: Boolean) = launch { repository.setBackgroundPlayback(enabled) }
    fun setPictureInPicture(enabled: Boolean) = launch { repository.setPictureInPicture(enabled) }
    fun resetPlayback() = launch { repository.resetPlayback() }

    fun setShowShorts(enabled: Boolean) = launch { repository.setShowShorts(enabled) }
    fun resetShorts() = launch { repository.resetShorts() }

    fun setShowComments(enabled: Boolean) = launch { repository.setShowComments(enabled) }
    fun resetComments() = launch { repository.resetComments() }

    fun resetAll() = launch { repository.resetAll() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SettingsViewModel(container.settings) }
        }
    }
}

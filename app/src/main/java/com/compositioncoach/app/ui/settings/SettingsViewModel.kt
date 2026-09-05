package com.compositioncoach.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.settings.SettingsRepository
import com.compositioncoach.composition.model.GuidanceLevel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Thin wrapper over [SettingsRepository] so [com.compositioncoach.app.ui.settings.SettingsScreen] never touches DataStore directly. */
class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<CoachSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CoachSettings(),
    )

    fun setGuidanceEnabled(enabled: Boolean) = launch { repository.setGuidanceEnabled(enabled) }
    fun setShowScore(enabled: Boolean) = launch { repository.setShowScore(enabled) }
    fun setShowThirdsGrid(enabled: Boolean) = launch { repository.setShowThirdsGrid(enabled) }
    fun setGuidanceLevel(level: GuidanceLevel) = launch { repository.setGuidanceLevel(level) }
    fun setPoseDetectionEnabled(enabled: Boolean) = launch { repository.setPoseDetectionEnabled(enabled) }
    fun setBatterySaver(enabled: Boolean) = launch { repository.setBatterySaver(enabled) }
    fun setDebugMode(enabled: Boolean) = launch { repository.setDebugMode(enabled) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
                return SettingsViewModel(container.settingsRepository) as T
            }
        }
    }
}

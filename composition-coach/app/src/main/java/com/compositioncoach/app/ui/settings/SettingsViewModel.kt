package com.compositioncoach.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.settings.SettingsRepository
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thin wrapper over [SettingsRepository] and [UiPreferencesStore] so the settings screen never touches DataStore
 * directly. `capture_enhancement` is the one capture key not written here: the Enhancement row calls
 * `CameraViewModel.onEnhancementSelected`, which persists it and owns the camera rebind it needs.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val uiStore: UiPreferencesStore,
) : ViewModel() {

    val settings: StateFlow<CoachSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CoachSettings(),
    )

    /** Easy/Coach, Pro, heads-up guides; see [UiPreferences] for why these live in their own store for now. */
    val uiPreferences: StateFlow<UiPreferences> = uiStore.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiPreferences(),
    )

    fun setGuidanceEnabled(enabled: Boolean) = launch { repository.setGuidanceEnabled(enabled) }
    fun setShowScore(enabled: Boolean) = launch { repository.setShowScore(enabled) }
    fun setShowThirdsGrid(enabled: Boolean) = launch { repository.setShowThirdsGrid(enabled) }
    fun setGuidanceLevel(level: GuidanceLevel) = launch { repository.setGuidanceLevel(level) }
    fun setPoseDetectionEnabled(enabled: Boolean) = launch { repository.setPoseDetectionEnabled(enabled) }
    fun setBatterySaver(enabled: Boolean) = launch { repository.setBatterySaver(enabled) }
    fun setDebugMode(enabled: Boolean) = launch { repository.setDebugMode(enabled) }
    fun setSceneIntent(intent: SceneIntent) = launch { repository.setSceneIntent(intent) }
    fun setDetectObjectsEnabled(enabled: Boolean) = launch { repository.setDetectObjectsEnabled(enabled) }
    fun setSubjectMaskEnabled(enabled: Boolean) = launch { repository.setSubjectMaskEnabled(enabled) }

    fun setBurstSharpest(enabled: Boolean) = launch { repository.setBurstSharpest(enabled) }
    fun setAutoLevel(enabled: Boolean) = launch { repository.setAutoLevel(enabled) }
    fun setAutoShoot(enabled: Boolean) = launch { repository.setAutoShoot(enabled) }

    fun setExperienceMode(mode: ExperienceMode) = launch { uiStore.setExperienceMode(mode) }
    fun setProControls(enabled: Boolean) = launch { uiStore.setProControls(enabled) }
    fun setGuideByVibration(enabled: Boolean) = launch { uiStore.setGuideByVibration(enabled) }
    fun setGuideByVoice(enabled: Boolean) = launch { uiStore.setGuideByVoice(enabled) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun factory(container: AppContainer, uiStore: UiPreferencesStore): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
                return SettingsViewModel(container.settingsRepository, uiStore) as T
            }
        }
    }
}

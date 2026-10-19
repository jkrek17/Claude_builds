package com.compositioncoach.app.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.compositioncoach.app.outcome.ExperienceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Wave 2 presentation preferences Settings writes and the camera reads: Easy/Coach, the Pro switch and the
 * two heads-up guides. All default off/Easy (`docs/PRODUCT.md`).
 *
 * INTEGRATION SEAM: `docs/WAVE2.md` puts `experience_mode` and `pro_controls` in `CoachSettings`, which the
 * state engineer owns and which does not have them in this worktree. Until it does, they live in this small
 * UI-owned DataStore file (`ui_preferences`) under the *same key names*. To move them: make
 * [UiPreferencesStore] delegate to `SettingsRepository` (or delete it and read `CoachSettings`), keep
 * [UiPreferences] as the UI's view of them. [effectiveExperience] lets either source switch Coach on, so the
 * camera already follows `CameraUiState.experienceMode` once the view model fills it.
 */
data class UiPreferences(
    val experienceMode: ExperienceMode = ExperienceMode.EASY,
    val proControls: Boolean = false,
    val guideByVibration: Boolean = false,
    val guideByVoice: Boolean = false,
) {
    companion object {
        /** Coach when either the camera state (state engineer's `experience_mode`) or this store says Coach. */
        fun effectiveExperience(fromState: ExperienceMode, fromPreferences: ExperienceMode): ExperienceMode =
            if (fromState == ExperienceMode.COACH || fromPreferences == ExperienceMode.COACH) ExperienceMode.COACH else ExperienceMode.EASY

        fun decodeExperience(raw: String?): ExperienceMode =
            ExperienceMode.entries.firstOrNull { it.name == raw } ?: ExperienceMode.EASY
    }
}

private val Context.uiPreferencesStore by preferencesDataStore(name = "ui_preferences")

/** DataStore-backed [UiPreferences]; cheap to construct, the underlying DataStore is a per-process singleton. */
class UiPreferencesStore(context: Context) {
    private val dataStore = context.applicationContext.uiPreferencesStore

    val preferences: Flow<UiPreferences> = dataStore.data.map { it.toUiPreferences() }

    suspend fun setExperienceMode(mode: ExperienceMode) = dataStore.edit { it[Keys.EXPERIENCE_MODE] = mode.name }
    suspend fun setProControls(enabled: Boolean) = dataStore.edit { it[Keys.PRO_CONTROLS] = enabled }
    suspend fun setGuideByVibration(enabled: Boolean) = dataStore.edit { it[Keys.GUIDE_BY_VIBRATION] = enabled }
    suspend fun setGuideByVoice(enabled: Boolean) = dataStore.edit { it[Keys.GUIDE_BY_VOICE] = enabled }

    /** Key names match the ones `docs/WAVE2.md` gives `CoachSettings`, so a later migration is a copy. */
    object Keys {
        val EXPERIENCE_MODE = stringPreferencesKey("experience_mode")
        val PRO_CONTROLS = booleanPreferencesKey("pro_controls")
        val GUIDE_BY_VIBRATION = booleanPreferencesKey("guide_by_vibration")
        val GUIDE_BY_VOICE = booleanPreferencesKey("guide_by_voice")
    }
}

internal fun Preferences.toUiPreferences() = UiPreferences(
    experienceMode = UiPreferences.decodeExperience(this[UiPreferencesStore.Keys.EXPERIENCE_MODE]),
    proControls = this[UiPreferencesStore.Keys.PRO_CONTROLS] ?: false,
    guideByVibration = this[UiPreferencesStore.Keys.GUIDE_BY_VIBRATION] ?: false,
    guideByVoice = this[UiPreferencesStore.Keys.GUIDE_BY_VOICE] ?: false,
)

package com.compositioncoach.app.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "coach_settings")

/**
 * Persists [CoachSettings] via Jetpack DataStore Preferences.
 *
 * Preference keys (see [Keys]) are the stable on-disk contract; renaming a key silently resets that
 * one setting to its default instead of crashing, which is the DataStore-idiomatic way to evolve settings.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val settings: Flow<CoachSettings> = dataStore.data.map { it.toCoachSettings() }

    /** Reads the current settings, applies [transform], and persists the whole result in one DataStore transaction. */
    suspend fun update(transform: (CoachSettings) -> CoachSettings) {
        dataStore.edit { prefs ->
            val current = prefs.toCoachSettings()
            val updated = transform(current)
            prefs[Keys.GUIDANCE_ENABLED] = updated.guidanceEnabled
            prefs[Keys.SHOW_SCORE] = updated.showScore
            prefs[Keys.SHOW_THIRDS_GRID] = updated.showThirdsGrid
            prefs[Keys.GUIDANCE_LEVEL] = GuidanceLevelCodec.encode(updated.guidanceLevel)
            prefs[Keys.POSE_DETECTION_ENABLED] = updated.poseDetectionEnabled
            prefs[Keys.BATTERY_SAVER] = updated.batterySaver
            prefs[Keys.DEBUG_MODE] = updated.debugMode
            prefs[Keys.SCENE_INTENT] = SceneIntentCodec.encode(updated.sceneIntent)
        }
    }

    suspend fun setGuidanceEnabled(enabled: Boolean) = update { it.copy(guidanceEnabled = enabled) }
    suspend fun setShowScore(enabled: Boolean) = update { it.copy(showScore = enabled) }
    suspend fun setShowThirdsGrid(enabled: Boolean) = update { it.copy(showThirdsGrid = enabled) }
    suspend fun setGuidanceLevel(level: GuidanceLevel) = update { it.copy(guidanceLevel = level) }
    suspend fun setPoseDetectionEnabled(enabled: Boolean) = update { it.copy(poseDetectionEnabled = enabled) }
    suspend fun setBatterySaver(enabled: Boolean) = update { it.copy(batterySaver = enabled) }
    suspend fun setDebugMode(enabled: Boolean) = update { it.copy(debugMode = enabled) }
    suspend fun setSceneIntent(intent: SceneIntent) = update { it.copy(sceneIntent = intent) }

    private fun Preferences.toCoachSettings() = CoachSettings(
        guidanceEnabled = this[Keys.GUIDANCE_ENABLED] ?: true,
        showScore = this[Keys.SHOW_SCORE] ?: true,
        showThirdsGrid = this[Keys.SHOW_THIRDS_GRID] ?: false,
        guidanceLevel = GuidanceLevelCodec.decode(this[Keys.GUIDANCE_LEVEL]),
        poseDetectionEnabled = this[Keys.POSE_DETECTION_ENABLED] ?: true,
        batterySaver = this[Keys.BATTERY_SAVER] ?: false,
        debugMode = this[Keys.DEBUG_MODE] ?: false,
        sceneIntent = SceneIntentCodec.decode(this[Keys.SCENE_INTENT]),
    )

    /** Preference key names. Exposed for tests; do not rename in place without a migration. */
    object Keys {
        val GUIDANCE_ENABLED = booleanPreferencesKey("guidance_enabled")
        val SHOW_SCORE = booleanPreferencesKey("show_score")
        val SHOW_THIRDS_GRID = booleanPreferencesKey("show_thirds_grid")
        val GUIDANCE_LEVEL = stringPreferencesKey("guidance_level")
        val POSE_DETECTION_ENABLED = booleanPreferencesKey("pose_detection_enabled")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val SCENE_INTENT = stringPreferencesKey("scene_intent")
    }
}

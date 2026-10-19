package com.compositioncoach.app.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.compositioncoach.app.camera.CaptureEnhancement
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
            prefs.writeCoachSettings(transform(prefs.toCoachSettings()))
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
    suspend fun setDetectObjectsEnabled(enabled: Boolean) = update { it.copy(detectObjectsEnabled = enabled) }
    suspend fun setSubjectMaskEnabled(enabled: Boolean) = update { it.copy(subjectMaskEnabled = enabled) }
    suspend fun setOnboardingSeen(seen: Boolean) = update { it.copy(onboardingSeen = seen) }
    suspend fun setCaptureEnhancement(enhancement: CaptureEnhancement) = update { it.copy(captureEnhancement = enhancement) }
    suspend fun setBurstSharpest(enabled: Boolean) = update { it.copy(burstSharpest = enabled) }
    suspend fun setAutoLevel(enabled: Boolean) = update { it.copy(autoLevel = enabled) }
    suspend fun setAutoShoot(enabled: Boolean) = update { it.copy(autoShoot = enabled) }

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
        val DETECT_OBJECTS = booleanPreferencesKey("detect_objects")
        val SUBJECT_MASK = booleanPreferencesKey("subject_mask")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val CAPTURE_ENHANCEMENT = stringPreferencesKey("capture_enhancement")
        val BURST_SHARPEST = booleanPreferencesKey("burst_sharpest")
        val AUTO_LEVEL = booleanPreferencesKey("auto_level")
        val AUTO_SHOOT = booleanPreferencesKey("auto_shoot")
    }
}

/**
 * Decodes [CoachSettings] from a DataStore snapshot; every missing key falls back to its documented default.
 * Pure (no Context/DataStore), so the full round-trip is unit-testable with `mutablePreferencesOf()`.
 */
internal fun Preferences.toCoachSettings() = CoachSettings(
    guidanceEnabled = this[SettingsRepository.Keys.GUIDANCE_ENABLED] ?: true,
    showScore = this[SettingsRepository.Keys.SHOW_SCORE] ?: true,
    showThirdsGrid = this[SettingsRepository.Keys.SHOW_THIRDS_GRID] ?: false,
    guidanceLevel = GuidanceLevelCodec.decode(this[SettingsRepository.Keys.GUIDANCE_LEVEL]),
    poseDetectionEnabled = this[SettingsRepository.Keys.POSE_DETECTION_ENABLED] ?: true,
    batterySaver = this[SettingsRepository.Keys.BATTERY_SAVER] ?: false,
    debugMode = this[SettingsRepository.Keys.DEBUG_MODE] ?: false,
    sceneIntent = SceneIntentCodec.decode(this[SettingsRepository.Keys.SCENE_INTENT]),
    detectObjectsEnabled = this[SettingsRepository.Keys.DETECT_OBJECTS] ?: true,
    subjectMaskEnabled = this[SettingsRepository.Keys.SUBJECT_MASK] ?: true,
    onboardingSeen = this[SettingsRepository.Keys.ONBOARDING_SEEN] ?: false,
    captureEnhancement = CaptureEnhancementCodec.decode(this[SettingsRepository.Keys.CAPTURE_ENHANCEMENT]),
    burstSharpest = this[SettingsRepository.Keys.BURST_SHARPEST] ?: true,
    autoLevel = this[SettingsRepository.Keys.AUTO_LEVEL] ?: false,
    autoShoot = this[SettingsRepository.Keys.AUTO_SHOOT] ?: false,
)

/** Writes every field of [settings] into this snapshot; the inverse of [toCoachSettings]. */
internal fun MutablePreferences.writeCoachSettings(settings: CoachSettings) {
    this[SettingsRepository.Keys.GUIDANCE_ENABLED] = settings.guidanceEnabled
    this[SettingsRepository.Keys.SHOW_SCORE] = settings.showScore
    this[SettingsRepository.Keys.SHOW_THIRDS_GRID] = settings.showThirdsGrid
    this[SettingsRepository.Keys.GUIDANCE_LEVEL] = GuidanceLevelCodec.encode(settings.guidanceLevel)
    this[SettingsRepository.Keys.POSE_DETECTION_ENABLED] = settings.poseDetectionEnabled
    this[SettingsRepository.Keys.BATTERY_SAVER] = settings.batterySaver
    this[SettingsRepository.Keys.DEBUG_MODE] = settings.debugMode
    this[SettingsRepository.Keys.SCENE_INTENT] = SceneIntentCodec.encode(settings.sceneIntent)
    this[SettingsRepository.Keys.DETECT_OBJECTS] = settings.detectObjectsEnabled
    this[SettingsRepository.Keys.SUBJECT_MASK] = settings.subjectMaskEnabled
    this[SettingsRepository.Keys.ONBOARDING_SEEN] = settings.onboardingSeen
    this[SettingsRepository.Keys.CAPTURE_ENHANCEMENT] = CaptureEnhancementCodec.encode(settings.captureEnhancement)
    this[SettingsRepository.Keys.BURST_SHARPEST] = settings.burstSharpest
    this[SettingsRepository.Keys.AUTO_LEVEL] = settings.autoLevel
    this[SettingsRepository.Keys.AUTO_SHOOT] = settings.autoShoot
}

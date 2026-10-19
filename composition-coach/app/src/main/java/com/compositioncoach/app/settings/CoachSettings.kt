package com.compositioncoach.app.settings

import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent

/** User-configurable behavior of the coaching pipeline and overlay UI. Persisted via [SettingsRepository]. */
data class CoachSettings(
    val guidanceEnabled: Boolean = true,
    val showScore: Boolean = true,
    val showThirdsGrid: Boolean = false,
    val guidanceLevel: GuidanceLevel = GuidanceLevel.BALANCED,
    val poseDetectionEnabled: Boolean = true,
    val batterySaver: Boolean = false,
    val debugMode: Boolean = false,
    /** What the photographer told us they're shooting; forwarded to `CompositionCoach.process`/`evaluateOnce`. */
    val sceneIntent: SceneIntent = SceneIntent.AUTO,
    /** Forwarded to `VisionFeatureToggles.setObjectDetectionEnabled` whenever it changes. */
    val detectObjectsEnabled: Boolean = true,
    /** User's own preference for subject-mask segmentation; see [effectiveSubjectMaskEnabled] for what's actually applied. */
    val subjectMaskEnabled: Boolean = true,
    /** Whether the first-launch onboarding card has been dismissed. */
    val onboardingSeen: Boolean = false,
) {
    /** Target interval between vision analyses, see [FrameAnalysisSource.setTargetIntervalMs]. */
    val analysisIntervalMs: Long get() = if (batterySaver) BATTERY_SAVER_INTERVAL_MS else DEFAULT_INTERVAL_MS

    /**
     * The subject-mask toggle actually applied to the vision pipeline: segmentation is the most
     * expensive detector (`:vision`'s README), so battery saver forces it off regardless of the user's
     * own [subjectMaskEnabled] preference — see [subjectMaskSubtitle] for the UI-facing explanation.
     */
    val effectiveSubjectMaskEnabled: Boolean get() = subjectMaskEnabled && !batterySaver

    companion object {
        const val DEFAULT_INTERVAL_MS = 100L
        const val BATTERY_SAVER_INTERVAL_MS = 200L
    }
}

/** Subtitle for the "Subject mask" row in Settings; explains the battery-saver override when it applies. */
fun CoachSettings.subjectMaskSubtitle(): String =
    if (batterySaver) {
        "Better background and separation advice for people; off while battery saver is on"
    } else {
        "Better background and separation advice for people; uses more battery"
    }

/** One-line descriptions shown next to each [GuidanceLevel] choice in Settings. */
fun GuidanceLevel.description(): String = when (this) {
    GuidanceLevel.MINIMAL -> "Only the score and a shoot-ready cue. No instructions."
    GuidanceLevel.BALANCED -> "One clear instruction at a time. Good for everyday shooting."
    GuidanceLevel.COACH -> "Instructions plus the reasoning behind them, for learning."
}

fun GuidanceLevel.label(): String = when (this) {
    GuidanceLevel.MINIMAL -> "Minimal"
    GuidanceLevel.BALANCED -> "Balanced"
    GuidanceLevel.COACH -> "Coach"
}

/**
 * Serializes [GuidanceLevel] to/from the plain string DataStore stores it as. Pulled out of
 * [SettingsRepository] (which needs a real DataStore/Context) so the round-trip and the "unknown or
 * missing value falls back to BALANCED" behavior are unit-testable without Robolectric.
 */
object GuidanceLevelCodec {
    fun encode(level: GuidanceLevel): String = level.name

    fun decode(raw: String?): GuidanceLevel =
        raw?.let { runCatching { GuidanceLevel.valueOf(it) }.getOrNull() } ?: GuidanceLevel.BALANCED
}

/**
 * Serializes [SceneIntent] to/from the plain string DataStore stores it as, same rationale as
 * [GuidanceLevelCodec]: an unknown or legacy value falls back to [SceneIntent.AUTO] rather than crashing.
 */
object SceneIntentCodec {
    fun encode(intent: SceneIntent): String = intent.name

    fun decode(raw: String?): SceneIntent =
        raw?.let { runCatching { SceneIntent.valueOf(it) }.getOrNull() } ?: SceneIntent.AUTO
}

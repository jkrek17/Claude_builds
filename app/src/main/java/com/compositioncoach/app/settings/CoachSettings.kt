package com.compositioncoach.app.settings

import com.compositioncoach.app.camera.CaptureEnhancement
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
    /**
     * The CameraX Extensions pipeline the user wants captures routed through (`capture_enhancement`).
     * Defaults to AUTO: on a phone whose vendor ships an extensions library, AUTO lets the vendor's own
     * multi-frame pipeline pick HDR or night processing per scene, which is the closest a third-party app
     * can get to the stock camera's merge. When the bound lens does not offer the requested mode, the
     * camera falls back to OFF for that bind (the stored preference is kept, so it applies again on a lens
     * that does offer it).
     */
    val captureEnhancement: CaptureEnhancement = CaptureEnhancement.AUTO,
    /**
     * Lucky imaging (`burst_sharpest`): capture three frames and keep the sharpest. The stored value is
     * the user's preference; [burstSharpestEffective] is what a given capture actually does, because an
     * extension's own multi-frame merge already does better than picking one of three frames.
     */
    val burstSharpest: Boolean = true,
    /** Straighten small (0.7-8 degree) horizon tilts after saving (`auto_level`). */
    val autoLevel: Boolean = true,
) {
    /**
     * Whether a capture taken with [activeEnhancement] (what the bound session actually uses, not the
     * request) should be a three-frame sharpest-of burst: only when the user wants it, no extension is
     * handling the capture (the vendor pipeline already merges frames; a burst of extension captures would
     * take seconds), and the device is not conserving power (three full-resolution captures are three times
     * the ISP and encoder work).
     */
    fun burstSharpestEffective(activeEnhancement: CaptureEnhancement, conservingPower: Boolean): Boolean =
        burstSharpest && activeEnhancement == CaptureEnhancement.OFF && !conservingPower

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

/**
 * Serializes [CaptureEnhancement] to/from the plain string DataStore stores it as (`capture_enhancement`).
 * A missing or unrecognized value decodes to [CaptureEnhancement.AUTO], the documented default.
 */
object CaptureEnhancementCodec {
    fun encode(enhancement: CaptureEnhancement): String = enhancement.name

    fun decode(raw: String?): CaptureEnhancement =
        raw?.let { runCatching { CaptureEnhancement.valueOf(it) }.getOrNull() } ?: CaptureEnhancement.AUTO
}

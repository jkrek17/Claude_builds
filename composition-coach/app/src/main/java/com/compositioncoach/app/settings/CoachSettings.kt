package com.compositioncoach.app.settings

import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.outcome.ReviewCardMode
import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent

/**
 * User-configurable behavior of the coaching pipeline and overlay UI. Persisted via [SettingsRepository].
 * Defaults are the Easy experience of `docs/PRODUCT.md`: auto shoot, auto-level, sharpest-of-burst and the
 * best enhancement on; the score number off (see [effectiveShowScore]).
 */
data class CoachSettings(
    val guidanceEnabled: Boolean = true,
    /**
     * The user's stored "show the score number" preference. Easy mode never shows the number whatever this
     * says: read [effectiveShowScore] for what the screen should do, so the stored value survives a trip
     * through Easy and back to Coach.
     */
    val showScore: Boolean = true,
    val showThirdsGrid: Boolean = false,
    val guidanceLevel: GuidanceLevel = GuidanceLevel.BALANCED,
    val poseDetectionEnabled: Boolean = true,
    val batterySaver: Boolean = false,
    val debugMode: Boolean = false,
    /**
     * The photograph the user asked for (`outcome`): drives the scene intent, the enhancement, the capture
     * pipeline and the hold instruction. Change it with [withOutcome] so [sceneIntent] follows.
     */
    val outcome: Outcome = Outcome.DEFAULT,
    /**
     * Derived from [outcome] (`outcome.spec.sceneIntent`); no longer user-set. Forwarded to
     * `CompositionCoach.process`/`evaluateOnce`. [SettingsRepository] always reads and writes it from the
     * outcome; the parameter only exists so older call sites keep compiling.
     */
    val sceneIntent: SceneIntent = outcome.spec.sceneIntent,
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
    /**
     * Straighten small (0.7-8 degree) horizon tilts after saving (`auto_level`). On by default (Easy defaults,
     * "fix what can be fixed silently"); the review's Compare chip shows the photo as captured.
     */
    val autoLevel: Boolean = true,
    /**
     * "Auto shoot when ready" (`auto_shoot`): every shutter tap arms shoot-at-the-peak (as a long-press does)
     * instead of firing at once. On by default (Easy: "tap = capture at the best moment within 4 s").
     */
    val autoShoot: Boolean = true,
    /** Easy (default) or Coach (`experience_mode`). */
    val experienceMode: ExperienceMode = ExperienceMode.EASY,
    /** Reveals exposure, zoom presets, enhancement chips, grid, guidance level, detection toggles (`pro_controls`). */
    val proControls: Boolean = false,
    /** When the after-action card is shown (`review_card_mode`). */
    val reviewCardMode: ReviewCardMode = ReviewCardMode.ALWAYS,
    /** "Guide by vibration" (`haptic_guidance`). */
    val hapticGuidance: Boolean = false,
    /** "Guide by voice" (`voice_guidance`). */
    val voiceGuidance: Boolean = false,
    /** Self-timer length in seconds (`timer_seconds`): 0 (off), 3 or 10; see [TimerSecondsCodec]. */
    val timerSeconds: Int = 0,
    /** The taste model (`adaptive_weights`) in `AdaptiveWeights.serialize()` form; empty = NEUTRAL. See [adaptive]. */
    val adaptiveWeights: String = "",
    /** Shutter sound policy (`shutter_sound`); see [ShutterSoundMode]. */
    val shutterSound: ShutterSoundMode = ShutterSoundMode.AUTO,
) {
    /** The score number is a Coach-mode thing: Easy shows the ring only. The stored [showScore] is kept. */
    fun effectiveShowScore(mode: ExperienceMode = experienceMode): Boolean = mode == ExperienceMode.COACH && showScore

    /** The parsed taste model; never throws (a corrupt string degrades to NEUTRAL). */
    val adaptive: AdaptiveWeights get() = AdaptiveWeights.parse(adaptiveWeights)

    /** Selects [outcome] and the scene intent derived from it. */
    fun withOutcome(outcome: Outcome): CoachSettings = copy(outcome = outcome, sceneIntent = outcome.spec.sceneIntent)

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

/**
 * Shutter sound (`shutter_sound`). [AUTO] plays `MediaActionSound` only where the device reports the sound as
 * mandatory (some regions require it by law); [ALWAYS] plays it on every capture. There is deliberately no
 * "never": a region that requires the sound always gets it.
 */
enum class ShutterSoundMode { AUTO, ALWAYS }

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

/**
 * `outcome`. A missing value falls back to the Wave 1 `scene_intent` (migration: someone who shot in
 * Portrait keeps Portrait), then to [Outcome.DEFAULT]; an unknown name decodes to [Outcome.DEFAULT].
 */
object OutcomeCodec {
    fun encode(outcome: Outcome): String = outcome.name

    fun decode(raw: String?, legacySceneIntent: String? = null): Outcome = when {
        raw != null -> Outcome.fromName(raw)
        legacySceneIntent != null -> Outcome.forSceneIntent(SceneIntentCodec.decode(legacySceneIntent))
        else -> Outcome.DEFAULT
    }
}

/** Name-based codec for the small Wave 2 enums; a missing or unknown name decodes to [fallback]. */
class EnumCodec<E : Enum<E>>(private val values: Array<E>, val fallback: E) {
    fun encode(value: E): String = value.name
    fun decode(raw: String?): E = raw?.let { name -> values.firstOrNull { it.name == name } } ?: fallback

    companion object {
        val EXPERIENCE_MODE = EnumCodec(ExperienceMode.entries.toTypedArray(), ExperienceMode.EASY)
        val REVIEW_CARD_MODE = EnumCodec(ReviewCardMode.entries.toTypedArray(), ReviewCardMode.ALWAYS)
        val SHUTTER_SOUND = EnumCodec(ShutterSoundMode.entries.toTypedArray(), ShutterSoundMode.AUTO)
    }
}

/** `timer_seconds`: only 0, 3 and 10 are offered; anything else (corrupt, legacy) snaps to the nearest offered value. */
object TimerSecondsCodec {
    val OPTIONS = listOf(0, 3, 10)

    fun decode(raw: Int?): Int = when {
        raw == null || raw <= 0 -> 0
        else -> OPTIONS.minBy { kotlin.math.abs(it - raw) }
    }
}

/** `adaptive_weights`: normalises through [AdaptiveWeights] so a corrupt string is stored back clean. */
object AdaptiveWeightsCodec {
    fun encode(weights: AdaptiveWeights): String = weights.serialize()
    fun decode(raw: String?): AdaptiveWeights = AdaptiveWeights.parse(raw)
}

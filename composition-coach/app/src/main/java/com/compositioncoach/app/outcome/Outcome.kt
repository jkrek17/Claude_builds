package com.compositioncoach.app.outcome

import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.SceneIntent

/**
 * How a capture is produced for an outcome.
 *
 * - SINGLE: one capture. BURST_SHARPEST: three in-memory frames, keep the sharpest.
 * - BURST_MERGE: a full-resolution burst aligned and averaged with motion rejection (clean, sharp; Wave 3).
 * - NIGHT_STACK: BURST_MERGE with more frames and the longest handheld shutter the gyro allows (Wave 3).
 * - EXPOSURE_BRACKET: full-resolution exposure bracket fused for dynamic range, combined with the focus stack
 *   when the lens can focus manually (Wave 3).
 * - GROUP_COMPOSITE: a burst in which every face is taken from its own best frame (eyes open, sharpest) (Wave 3).
 * - MOTION_*: the live analysis stream stacked over an adaptive hold. FOCUS_STACK: three focus distances merged.
 */
enum class CapturePipeline {
    SINGLE, BURST_SHARPEST, BURST_MERGE, NIGHT_STACK, EXPOSURE_BRACKET, GROUP_COMPOSITE,
    MOTION_AVERAGE, MOTION_LIGHTEN, MOTION_MEDIAN, FOCUS_STACK;

    /** Pipelines that need the plain (non-extension) session: lucky imaging, merges and manual control cannot run under a vendor extension. */
    val needsPlainSession: Boolean get() = this != SINGLE
    val isMotion: Boolean get() = this == MOTION_AVERAGE || this == MOTION_LIGHTEN || this == MOTION_MEDIAN
}

/** Post-processing applied on review (never automatic, never destructive). */
enum class PostProcess { NONE, BOKEH_MASK }

/**
 * Everything an [Outcome] decides on the user's behalf. See `docs/PRODUCT.md` "Outcomes, not modes".
 *
 * @param enhancementPreference enhancements to try in order; the first the lens offers wins, else OFF.
 * @param holdSeconds how long the user must hold still for Motion/Night pipelines (0 = none).
 * @param instruction the chip shown while the outcome needs something from the user (null = none).
 * @param coachingFocus categories whose weight is raised for this outcome.
 * @param preferFrontCamera the outcome lives on the front lens (Selfie).
 */
data class OutcomeSpec(
    val sceneIntent: SceneIntent,
    val enhancementPreference: List<CaptureEnhancement>,
    val pipeline: CapturePipeline,
    val holdSeconds: Int,
    val postProcess: PostProcess,
    val instruction: String?,
    val coachingFocus: Set<MetricCategory>,
    val preferFrontCamera: Boolean = false,
)

/** The photograph the user wants. The strip above the shutter lists these in plain words. */
enum class Outcome(val label: String, val spec: OutcomeSpec) {
    ANYTHING("Anything", OutcomeSpec(SceneIntent.AUTO, listOf(CaptureEnhancement.AUTO, CaptureEnhancement.OFF), CapturePipeline.BURST_SHARPEST, 0, PostProcess.NONE, null, emptySet())),
    PORTRAIT("A person", OutcomeSpec(SceneIntent.PORTRAIT, listOf(CaptureEnhancement.BOKEH, CaptureEnhancement.AUTO, CaptureEnhancement.OFF), CapturePipeline.BURST_SHARPEST, 0, PostProcess.BOKEH_MASK, null, setOf(MetricCategory.LIGHT, MetricCategory.HEADROOM, MetricCategory.LOOKING_ROOM))),
    GROUP("A group", OutcomeSpec(SceneIntent.GROUP_PORTRAIT, listOf(CaptureEnhancement.AUTO, CaptureEnhancement.OFF), CapturePipeline.BURST_SHARPEST, 0, PostProcess.NONE, null, setOf(MetricCategory.EDGE_TENSION, MetricCategory.LIGHT))),
    LANDSCAPE("A view", OutcomeSpec(SceneIntent.LANDSCAPE, listOf(CaptureEnhancement.HDR, CaptureEnhancement.AUTO, CaptureEnhancement.OFF), CapturePipeline.FOCUS_STACK, 0, PostProcess.NONE, null, setOf(MetricCategory.HORIZON, MetricCategory.LEADING_LINES))),
    NIGHT("Night", OutcomeSpec(SceneIntent.AUTO, listOf(CaptureEnhancement.NIGHT, CaptureEnhancement.AUTO, CaptureEnhancement.OFF), CapturePipeline.SINGLE, 2, PostProcess.NONE, "Hold still", emptySet())),
    LIGHT_TRAILS("Light trails", OutcomeSpec(SceneIntent.AUTO, listOf(CaptureEnhancement.OFF), CapturePipeline.MOTION_LIGHTEN, 4, PostProcess.NONE, "Hold still for 4 seconds", emptySet())),
    SILKY_WATER("Silky water", OutcomeSpec(SceneIntent.LANDSCAPE, listOf(CaptureEnhancement.OFF), CapturePipeline.MOTION_AVERAGE, 3, PostProcess.NONE, "Hold still for 3 seconds", setOf(MetricCategory.HORIZON))),
    CLEAR_CROWD("Clear the crowd", OutcomeSpec(SceneIntent.ARCHITECTURE, listOf(CaptureEnhancement.OFF), CapturePipeline.MOTION_MEDIAN, 5, PostProcess.NONE, "Hold still for 5 seconds", setOf(MetricCategory.SYMMETRY, MetricCategory.HORIZON))),
    FOOD("Food & things", OutcomeSpec(SceneIntent.OBJECT, listOf(CaptureEnhancement.AUTO, CaptureEnhancement.OFF), CapturePipeline.BURST_SHARPEST, 0, PostProcess.NONE, null, setOf(MetricCategory.LIGHT, MetricCategory.BACKGROUND_DISTRACTION))),
    SELFIE("Selfie", OutcomeSpec(SceneIntent.SELFIE, listOf(CaptureEnhancement.BOKEH, CaptureEnhancement.AUTO, CaptureEnhancement.OFF), CapturePipeline.BURST_SHARPEST, 0, PostProcess.BOKEH_MASK, null, setOf(MetricCategory.LIGHT, MetricCategory.HEADROOM), preferFrontCamera = true));

    val needsHold: Boolean get() = spec.holdSeconds > 0
    val isMotion: Boolean get() = spec.pipeline.isMotion

    companion object {
        val DEFAULT = ANYTHING
        fun fromName(name: String?): Outcome = entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * The outcome a legacy scene-intent choice maps to (Wave 1 `scene_intent` migration and the old scene
         * strip). Intents with no outcome of their own (ARCHITECTURE) map to [DEFAULT].
         */
        fun forSceneIntent(intent: SceneIntent): Outcome = when (intent) {
            SceneIntent.AUTO -> ANYTHING
            SceneIntent.PORTRAIT -> PORTRAIT
            SceneIntent.GROUP_PORTRAIT -> GROUP
            SceneIntent.LANDSCAPE -> LANDSCAPE
            SceneIntent.OBJECT -> FOOD
            SceneIntent.SELFIE -> SELFIE
            SceneIntent.ARCHITECTURE -> DEFAULT
        }
    }
}

/** Easy is the default experience; Coach adds the number, a secondary icon row and one-line reasons. */
enum class ExperienceMode { EASY, COACH }

/** When the after-action card is shown after a capture. */
enum class ReviewCardMode { ALWAYS, ONLY_WHEN_USEFUL }

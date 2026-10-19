package com.compositioncoach.app.camera

import com.compositioncoach.app.outcome.CapturePipeline as Pipeline
import com.compositioncoach.app.outcome.Outcome

/**
 * What the device can do beyond a plain capture, for [OutcomeCapturePlanner].
 *
 * @param motionStacking live analysis frames are flowing into a `FrameStackingSource` (vision pipeline present,
 *   not paused by an extension).
 * @param focusStacking the bound lens takes manual focus distances ([LensControl.supportsFocusStack]),
 *   whatever extension is bound now (the plan decides the extension).
 */
data class PipelineSupport(val motionStacking: Boolean, val focusStacking: Boolean)

/**
 * How an outcome will actually be captured on this device.
 *
 * @param enhancement the extension to bind for the outcome: the first of `spec.enhancementPreference` the lens
 *   offers (OFF when none). Pass it to [CameraController.setEnhancementOverride] before (re)binding.
 * @param pipeline the pipeline that will run (may be a fallback of [requested]).
 * @param missingEnhancement the outcome's first-choice enhancement when the lens lacks it (null otherwise).
 * @param degradedNote one plain sentence for the user when the outcome is not getting what it asked for.
 */
data class CapturePlan(
    val enhancement: CaptureEnhancement,
    val pipeline: Pipeline,
    val requested: Pipeline,
    val missingEnhancement: CaptureEnhancement? = null,
    val degradedNote: String? = null,
) {
    val degraded: Boolean get() = degradedNote != null
}

/**
 * Pure mapping from an [Outcome] plus device capabilities to a [CapturePlan]. Rules:
 *  - Enhancement: first available of the preference list wins. Missing a *named* first choice (Night,
 *    Portrait) is reported; missing AUTO is not (AUTO is "let the phone decide", OFF is a fine answer), and
 *    neither is a missing HDR extension: phones without one (Pixels among them) run multi-frame HDR in the
 *    standard pipeline anyway, so the photo is not degraded and there is nothing for the user to do.
 *  - BURST_SHARPEST and FOCUS_STACK need the plain (non-extension) session: lucky imaging and manual focus
 *    cannot run under a vendor extension, so with an extension bound they become SINGLE (the extension does
 *    its own multi-frame work, so this is not reported as degraded).
 *  - FOCUS_STACK without manual focus falls back to BURST_SHARPEST (one photo, sharpest of three). When the
 *    lens can focus-stack and the preference resolves to AUTO, OFF is bound instead: AUTO means "let the phone
 *    pick" and the stack is the better pick; a *named* first choice (HDR) still wins over the stack.
 *  - MOTION_* without live frames falls back to BURST_SHARPEST, and says so.
 */
object OutcomeCapturePlanner {

    fun plan(outcome: Outcome, available: Set<CaptureEnhancement>, support: PipelineSupport): CapturePlan {
        val spec = outcome.spec
        val preference = spec.enhancementPreference.ifEmpty { listOf(CaptureEnhancement.OFF) }
        val preferred = preference.firstOrNull { it == CaptureEnhancement.OFF || it in available } ?: CaptureEnhancement.OFF
        val stackWins = spec.pipeline == Pipeline.FOCUS_STACK && support.focusStacking && preferred == CaptureEnhancement.AUTO
        val enhancement = if (stackWins) CaptureEnhancement.OFF else preferred
        val first = preference.first()
        val missing = first.takeIf { it != enhancement && it !in SILENT_FALLBACKS }
        val requested = spec.pipeline
        val plain = enhancement == CaptureEnhancement.OFF
        var pipelineNote: String? = null
        val pipeline = when (requested) {
            Pipeline.SINGLE -> Pipeline.SINGLE
            Pipeline.BURST_SHARPEST -> if (plain) Pipeline.BURST_SHARPEST else Pipeline.SINGLE
            Pipeline.FOCUS_STACK -> when {
                !plain -> Pipeline.SINGLE
                support.focusStacking -> Pipeline.FOCUS_STACK
                else -> Pipeline.BURST_SHARPEST.also { pipelineNote = FOCUS_STACK_UNAVAILABLE }
            }
            Pipeline.MOTION_AVERAGE, Pipeline.MOTION_LIGHTEN, Pipeline.MOTION_MEDIAN ->
                if (support.motionStacking) requested else Pipeline.BURST_SHARPEST.also { pipelineNote = MOTION_UNAVAILABLE }
        }
        val note = missing?.let { enhancementNote(it) } ?: pipelineNote
        return CapturePlan(enhancement, pipeline, requested, missing, note)
    }

    /** First choices whose absence is never reported: OFF and AUTO by definition, HDR because the standard pipeline has it. */
    private val SILENT_FALLBACKS = setOf(CaptureEnhancement.OFF, CaptureEnhancement.AUTO, CaptureEnhancement.HDR)

    /** The sentence for a missing first-choice enhancement. */
    fun enhancementNote(missing: CaptureEnhancement): String = when (missing) {
        CaptureEnhancement.BOKEH -> "Portrait blur isn't built into this phone's camera; you can add it after the shot"
        else -> "${missing.label} isn't available on this phone, using the best we can"
    }

    const val FOCUS_STACK_UNAVAILABLE = "This lens can't focus-stack, so this is one sharp photo"
    const val MOTION_UNAVAILABLE = "Motion needs live analysis, which isn't running; took a normal photo"
}

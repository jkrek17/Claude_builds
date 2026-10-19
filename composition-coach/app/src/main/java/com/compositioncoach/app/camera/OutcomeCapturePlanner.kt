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
 *  - Enhancement: first available of the preference list wins. Missing a *named* first choice (Portrait's
 *    blur) is reported; missing AUTO is not (AUTO is "let the phone decide", OFF is a fine answer), and
 *    neither is a missing HDR extension: phones without one (Pixels among them) run multi-frame HDR in the
 *    standard pipeline anyway, so the photo is not degraded and there is nothing for the user to do.
 *  - BURST_SHARPEST and every Wave 3 merge (BURST_MERGE, NIGHT_STACK, EXPOSURE_BRACKET, GROUP_COMPOSITE) need
 *    the plain (non-extension) session: they cannot run under a vendor extension, so with an extension bound
 *    they become SINGLE (the extension does its own multi-frame work, so this is not reported as degraded).
 *    The vendor's AUTO never displaces a merge (BURST_MERGE, GROUP_COMPOSITE bind OFF when AUTO would have been
 *    the pick); a named extension (BOKEH for A person and Selfie) still wins over the merge.
 *  - NIGHT_STACK: with the NIGHT extension available it is bound and the capture is SINGLE (the extension does
 *    the stacking); without it OFF is bound and NIGHT_STACK runs. That is the better path, not a degradation,
 *    so a missing NIGHT extension is not reported.
 *  - EXPOSURE_BRACKET (which folds in the focus stack when the lens can) needs the plain session, so OFF is
 *    bound whatever the preference says (AUTO or a named HDR): the standard pipeline does HDR anyway. Plain
 *    capture is always possible, so an HDR extension is never the choice for the outcome; a user who picks one
 *    on the Pro pill still gets it, and the capture then runs SINGLE.
 *  - FOCUS_STACK (Food & things, near distances): bound plain when the lens can focus-stack; without manual
 *    focus the fallback is BURST_MERGE, silently (nothing is lost that the outcome promised).
 *  - MOTION_* without live frames falls back to BURST_SHARPEST, and says so.
 */
object OutcomeCapturePlanner {

    fun plan(outcome: Outcome, available: Set<CaptureEnhancement>, support: PipelineSupport): CapturePlan {
        val spec = outcome.spec
        val requested = spec.pipeline
        val preference = spec.enhancementPreference.ifEmpty { listOf(CaptureEnhancement.OFF) }
        val preferred = preference.firstOrNull { it == CaptureEnhancement.OFF || it in available } ?: CaptureEnhancement.OFF
        val enhancement = when (requested) {
            Pipeline.EXPOSURE_BRACKET -> CaptureEnhancement.OFF
            Pipeline.FOCUS_STACK -> if (support.focusStacking) CaptureEnhancement.OFF else preferred
            Pipeline.NIGHT_STACK -> if (CaptureEnhancement.NIGHT in available) CaptureEnhancement.NIGHT else CaptureEnhancement.OFF
            // A merge is the outcome's point: the vendor's AUTO never displaces it (a named extension such as
            // BOKEH still does, because true depth bokeh beats a mask blur).
            Pipeline.BURST_MERGE, Pipeline.GROUP_COMPOSITE -> if (preferred == CaptureEnhancement.AUTO) CaptureEnhancement.OFF else preferred
            else -> preferred
        }
        val first = preference.first()
        // Night's stack stands in for a missing NIGHT extension and is the better path: nothing to report.
        val stackInstead = requested == Pipeline.NIGHT_STACK && enhancement == CaptureEnhancement.OFF
        val missing = first.takeIf { it != enhancement && it !in SILENT_FALLBACKS && !stackInstead }
        val plain = enhancement == CaptureEnhancement.OFF
        var pipelineNote: String? = null
        val pipeline = when (requested) {
            Pipeline.SINGLE -> Pipeline.SINGLE
            Pipeline.BURST_SHARPEST -> if (plain) Pipeline.BURST_SHARPEST else Pipeline.SINGLE
            Pipeline.BURST_MERGE, Pipeline.NIGHT_STACK, Pipeline.GROUP_COMPOSITE, Pipeline.EXPOSURE_BRACKET ->
                if (plain) requested else Pipeline.SINGLE
            Pipeline.FOCUS_STACK -> when {
                !plain -> Pipeline.SINGLE
                support.focusStacking -> Pipeline.FOCUS_STACK
                else -> Pipeline.BURST_MERGE
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

    const val MOTION_UNAVAILABLE = "Motion needs live analysis, which isn't running; took a normal photo"
}

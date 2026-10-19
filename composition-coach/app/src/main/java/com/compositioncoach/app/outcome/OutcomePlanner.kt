package com.compositioncoach.app.outcome

import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.composition.engine.AdaptiveWeights

/**
 * What selecting an outcome does on this phone: the enhancement to request and whether the outcome had to
 * fall back (shown once as [note], e.g. "Night mode isn't available on this phone").
 */
data class OutcomePlan(
    val enhancement: CaptureEnhancement,
    val degraded: Boolean,
    val note: String?,
)

/** Pure decisions behind an [Outcome]; unit-tested in `OutcomePlannerTest`. */
object OutcomePlanner {

    /** How much an outcome's [OutcomeSpec.coachingFocus] categories are weighted up (on top of the taste model). */
    const val FOCUS_BOOST = 1.15f

    /**
     * The first of [OutcomeSpec.enhancementPreference] the bound lens offers ([available]; OFF always counts as
     * available), and whether the outcome is degraded: its first-choice enhancement is missing (AUTO excepted:
     * "best available" is never a promise), or its capture [pipeline][OutcomeSpec.pipeline] is not available
     * ([pipelineAvailable], from the capture port).
     */
    fun plan(
        outcome: Outcome,
        available: Set<CaptureEnhancement>,
        pipelineAvailable: (CapturePipeline) -> Boolean = { true },
    ): OutcomePlan {
        val prefs = outcome.spec.enhancementPreference
        val chosen = prefs.firstOrNull { it == CaptureEnhancement.OFF || it in available } ?: CaptureEnhancement.OFF
        val first = prefs.firstOrNull()
        val enhancementDegraded = first != null && first != CaptureEnhancement.AUTO && first != chosen
        val pipelineDegraded = !pipelineAvailable(outcome.spec.pipeline)
        val note = when {
            pipelineDegraded -> "${outcome.label} isn't available on this phone yet, so this takes a normal photo"
            enhancementDegraded -> enhancementNote(outcome, first!!)
            else -> null
        }
        return OutcomePlan(chosen, enhancementDegraded || pipelineDegraded, note)
    }

    private fun enhancementNote(outcome: Outcome, missing: CaptureEnhancement): String = when {
        missing == CaptureEnhancement.BOKEH && outcome.spec.postProcess == PostProcess.BOKEH_MASK ->
            "This phone has no portrait mode; the background blur is added on review"
        missing == CaptureEnhancement.NIGHT -> "Night mode isn't available on this phone"
        missing == CaptureEnhancement.HDR -> "HDR isn't available on this phone"
        else -> "${missing.label} isn't available on this phone"
    }

    /**
     * The taste model the engine sees for [outcome]: the user's [weights] with the outcome's coaching-focus
     * categories weighted up by [FOCUS_BOOST] (still clamped to the model's bounds). Returns [weights] itself
     * when the outcome has no focus. Crop history is not needed for scoring and is not carried over.
     */
    fun effectiveWeights(weights: AdaptiveWeights, outcome: Outcome): AdaptiveWeights {
        val focus = outcome.spec.coachingFocus
        if (focus.isEmpty()) return weights
        return AdaptiveWeights.of(weights.allMultipliers().mapValues { (category, m) -> if (category in focus) m * FOCUS_BOOST else m })
    }

    /** The chip while a hold is pending: the outcome's instruction, only for outcomes that need a hold. */
    fun holdInstruction(outcome: Outcome, holdPending: Boolean): String? =
        if (holdPending && outcome.needsHold) outcome.spec.instruction else null
}

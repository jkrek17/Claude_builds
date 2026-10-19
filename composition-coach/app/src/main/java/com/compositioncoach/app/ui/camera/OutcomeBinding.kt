package com.compositioncoach.app.ui.camera

import android.util.Size
import com.compositioncoach.app.camera.CameraBindResult
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.CaptureQuality
import com.compositioncoach.app.camera.CaptureSessionInfo
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.MotionCapture
import com.compositioncoach.app.camera.OutcomeCapturePlanner
import com.compositioncoach.app.di.CaptureOrchestrationPort
import com.compositioncoach.app.outcome.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pure decisions behind [OutcomeBinding], unit-tested in `OutcomeBindingRulesTest`. */
object OutcomeBindingRules {

    /**
     * The enhancement a bind ends up requesting on a lens offering [available]: the override when there is one,
     * else the persisted preference, falling back to OFF when the lens lacks it. Comparing this for the next bind
     * with the current bind's ([CaptureSessionInfo.outcomeEnhancement] / `requestedEnhancement`) decides whether
     * a rebind is needed, so an override equal to what already runs never costs a bind.
     */
    fun bindTarget(override: CaptureEnhancement?, persisted: CaptureEnhancement, available: Set<CaptureEnhancement>): CaptureEnhancement =
        CaptureQuality.resolveEnhancement(override ?: persisted, available)

    /**
     * Motion outcomes stack the analysis stream, so they bind it at [MotionCapture.analysisResolution] (1920x1440,
     * or 1280x960 once a device has shown it cannot sustain the larger size); everything else at the default.
     */
    fun analysisResolutionHint(outcome: Outcome): Size? =
        if (outcome.isMotion) MotionCapture.analysisResolution() else null
}

/**
 * The outcome half of [CameraViewModel]: applying an [Outcome] (scene intent via settings, the front lens for
 * Selfie, group mode) and planning its capture on the bound lens with [OutcomeCapturePlanner]: the plan's
 * enhancement goes to [CameraController.setEnhancementOverride], Motion outcomes raise the analysis stream with
 * [CameraController.setAnalysisResolutionHint], and the camera rebinds when either changes what the next bind
 * would do. The plan's degradation becomes `outcomeDegraded` / `outcomeDegradedNote`.
 *
 * Planning runs when the outcome changes and on every bind result (the lens decides what is available). The
 * screen calls [configure] on a fresh controller before binding, so returning to the camera never binds twice.
 *
 * Enhancement precedence: a real outcome change makes the outcome's enhancement the override (and persists it
 * as `capture_enhancement`, as the settings table documents); the user's own pick on the pill or in Settings
 * ([onUserPickedEnhancement]) becomes the override until the next outcome change. On first launch there is no
 * override and the persisted preference stands.
 */
internal class OutcomeBinding(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CameraUiState>,
    private val port: CaptureOrchestrationPort,
    private val controller: () -> CameraController?,
    private val persistEnhancement: suspend (CaptureEnhancement) -> Unit,
    private val persistOutcome: suspend (Outcome) -> Unit,
    private val switchLens: (LensFacing) -> Unit,
    private val onOutcomeApplied: (previous: Outcome?, outcome: Outcome) -> Unit,
    private val onRebound: (CameraBindResult) -> Unit,
) {
    private enum class OverrideSource { NONE, OUTCOME, USER }

    /** The outcome whose side effects are in force; null before settings load. */
    var applied: Outcome? = null
        private set

    /** An outcome the user just picked that DataStore has not echoed back yet; stale emissions must not undo it. */
    private var pending: Outcome? = null

    /** Who decides the enhancement override: nobody yet (first launch), the outcome's plan, or the user's pick. */
    private var source = OverrideSource.NONE
    private var override: CaptureEnhancement? = null
    private var persistPlanPending = false

    private var rebindPending = false
    private var rebindJob: Job? = null
    private var rebindAttemptedFor: CaptureEnhancement? = null
    private var settingsLoaded = false

    /** The user picked an outcome on the strip: shown at once, persisted, applied. */
    fun select(outcome: Outcome) {
        if (applied == outcome && pending == null) return
        pending = outcome
        apply(outcome)
        scope.launch { persistOutcome(outcome) }
    }

    /** A settings emission: applies its outcome unless a newer pick is still being saved. */
    fun onSettings(outcome: Outcome) {
        val waiting = pending
        if (waiting == null || waiting == outcome) {
            pending = null
            apply(outcome)
        }
        settingsLoaded = true
        rebindIfNeeded()
    }

    private fun apply(outcome: Outcome) {
        val previous = applied
        if (previous == outcome) return
        applied = outcome
        state.update { it.withOutcome(outcome) }
        port.setGroupModeEnabled(outcome == Outcome.GROUP)
        onOutcomeApplied(previous, outcome)
        if (previous != null) {
            source = OverrideSource.OUTCOME
            persistPlanPending = true
        }
        val lens = state.value.lensFacing
        when {
            outcome.spec.preferFrontCamera && lens == LensFacing.BACK -> switchLens(LensFacing.FRONT)
            previous?.spec?.preferFrontCamera == true && !outcome.spec.preferFrontCamera && lens == LensFacing.FRONT ->
                switchLens(LensFacing.BACK)
        }
        // A lens switch rebinds from the screen; the new configuration rides on that bind instead of a second one.
        plan(rebind = state.value.lensFacing == lens)
    }

    /** The user chose [enhancement] themselves (pill or Settings): it wins until the next outcome change. */
    fun onUserPickedEnhancement(enhancement: CaptureEnhancement) {
        source = OverrideSource.USER
        override = enhancement
        persistPlanPending = false
        controller()?.setEnhancementOverride(enhancement)
        rebindIfNeeded()
    }

    /** A (re)bind succeeded with what the lens offers: plan again, rebinding if the plan asks for more. */
    fun onBound() = plan(rebind = true)

    /** Hands the current plan's configuration to a controller about to bind (the screen's fresh controller). */
    fun configure(target: CameraController) {
        target.setEnhancementOverride(override)
        target.setAnalysisResolutionHint(OutcomeBindingRules.analysisResolutionHint(state.value.outcome))
    }

    private fun plan(rebind: Boolean) {
        val current = state.value
        val outcome = current.outcome
        val bound = controller()
        val hintChanged = bound?.setAnalysisResolutionHint(OutcomeBindingRules.analysisResolutionHint(outcome)) == true
        if (hintChanged && rebind) rebindPending = true
        val available = current.availableEnhancements
        if (available.isNotEmpty()) {
            // Motion needs live frames: plan as if analysis runs, then again if the chosen enhancement would pause it.
            val pausing = current.captureSession?.enhancementsPausingCoaching.orEmpty()
            val first = OutcomeCapturePlanner.plan(outcome, available, port.pipelineSupport(bound, analysisPaused = false))
            val effective = if (source == OverrideSource.USER) override ?: current.enhancement else first.enhancement
            val plan = if (effective in pausing) {
                OutcomeCapturePlanner.plan(outcome, available, port.pipelineSupport(bound, analysisPaused = true))
            } else {
                first
            }
            state.update { it.withOutcomePlan(plan) }
            if (source == OverrideSource.OUTCOME) override = plan.enhancement
            if (persistPlanPending) {
                persistPlanPending = false
                if (plan.enhancement != current.settings.captureEnhancement) scope.launch { persistEnhancement(plan.enhancement) }
            }
            bound?.setEnhancementOverride(override)
        }
        // With nothing bound yet the bind result plans again.
        if (rebind) rebindIfNeeded()
    }

    /**
     * Rebinds when the next bind would request a different enhancement than the current one
     * ([OutcomeBindingRules.bindTarget]: the outcome's or the user's override, else the persisted
     * `capture_enhancement`) or when the analysis resolution changed. Deferred while a capture runs (a rebind
     * would abort it); the shutter calls this again when the capture settles, and every bind result does too, so
     * a change is never lost.
     */
    fun rebindIfNeeded() {
        if (!settingsLoaded) return
        val bound = controller() ?: return
        val session = bound.session ?: return
        val target = OutcomeBindingRules.bindTarget(override, state.value.settings.captureEnhancement, session.availableEnhancements)
        if (target != boundTarget(session)) {
            // One rebind per distinct target: if the bind still reports another request, do not loop.
            if (rebindAttemptedFor != target) {
                rebindAttemptedFor = target
                rebindPending = true
            }
        } else {
            rebindAttemptedFor = null
        }
        if (!rebindPending || state.value.isCapturing || rebindJob?.isActive == true) return
        rebindPending = false
        rebindJob = scope.launch {
            val result = bound.rebind() ?: return@launch
            onRebound(result)
        }
    }

    private fun boundTarget(session: CaptureSessionInfo): CaptureEnhancement =
        OutcomeBindingRules.bindTarget(session.outcomeEnhancement, session.requestedEnhancement, session.availableEnhancements)
}

package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.di.ShutterSoundRule
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.camera.CapturePlan
import com.compositioncoach.app.outcome.CapturePipeline
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.settings.ShutterSoundMode
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.GroupStatus
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SmoothedComposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wave 2 state reducers: outcome, holds, timer, group readiness, ghost, Easy score. */
class CameraUiStateWave2Test {

    private fun composition(ready: Boolean, group: GroupStatus? = null) = SmoothedComposition(
        displayScore = 80,
        activeRecommendations = emptyList(),
        isShootReady = ready,
        scene = SceneClassification.UNKNOWN,
        primarySubject = null,
        raw = CompositionResult.empty().copy(groupStatus = group),
    )

    private fun CameraUiState.frame(c: SmoothedComposition) = withFrameUpdate(c, 0, 0f, 100, 0)

    @Test
    fun `settings carry the experience mode and timer, and Easy hides the score number`() {
        val state = CameraUiState().withSettings(CoachSettings(experienceMode = ExperienceMode.COACH, timerSeconds = 3, outcome = Outcome.NIGHT))
        assertEquals(ExperienceMode.COACH, state.experienceMode)
        assertEquals(3, state.timerSeconds)
        assertTrue(state.showScoreNumber)
        // The outcome is applied by the view model, not by a settings emission.
        assertEquals(Outcome.DEFAULT, state.outcome)
        assertFalse(CameraUiState().withSettings(CoachSettings()).showScoreNumber)
    }

    @Test
    fun `group status follows each frame and group scenes wait for everyone`() {
        val notReady = GroupStatus(ready = false, facesTotal = 3, facesReady = 2, blockers = listOf("Eyes closed"))
        val s1 = CameraUiState().frame(composition(ready = true, group = notReady))
        assertEquals(notReady, s1.groupStatus)
        assertFalse(s1.peakReady)
        val s2 = s1.frame(composition(ready = false, group = notReady.copy(ready = true, facesReady = 3, blockers = emptyList())))
        assertTrue(s2.peakReady)
        assertTrue(s2.justEnteredShootReady(s1))
        // Outside a group scene the usual shoot-ready applies.
        val solo = CameraUiState().frame(composition(ready = true))
        assertNull(solo.groupStatus)
        assertTrue(solo.peakReady)
        // The Group outcome with nobody in frame yet never fires.
        assertFalse(solo.withOutcome(Outcome.GROUP).peakReady)
    }

    @Test
    fun `a hold outcome shows its instruction from the shutter until the file is saved`() {
        val selected = CameraUiState().withOutcome(Outcome.LIGHT_TRAILS)
        assertNull(selected.holdInstruction)
        val capturing = selected.withCaptureStarted()
        assertEquals("Hold still for 4 seconds", capturing.holdInstruction)
        val holding = capturing.withHoldProgress(0.5f)
        assertEquals(0.5f, holding.holdProgress)
        assertEquals("Hold still for 4 seconds", holding.holdInstruction)
        val done = holding.withCaptureFinished()
        assertNull(done.holdProgress)
        assertNull(done.holdInstruction)
        assertNull(CameraUiState().withOutcome(Outcome.PORTRAIT).withCaptureStarted().holdInstruction)
        assertEquals(1f, selected.withHoldProgress(3f).holdProgress)
        assertNull(holding.withCaptureError("x").holdInstruction)
    }

    @Test
    fun `degradation, timer countdown and ghost`() {
        val plan = CapturePlan(
            enhancement = CaptureEnhancement.AUTO,
            pipeline = CapturePipeline.SINGLE,
            requested = CapturePipeline.SINGLE,
            missingEnhancement = CaptureEnhancement.NIGHT,
            degradedNote = "Night isn't available on this phone, using the best we can",
        )
        val degraded = CameraUiState().withOutcomePlan(plan)
        assertTrue(degraded.outcomeDegraded)
        assertEquals(DegradedNote.NO_NIGHT, degraded.outcomeDegradedNote)
        val fine = degraded.withOutcomePlan(plan.copy(missingEnhancement = null, degradedNote = null))
        assertFalse(fine.outcomeDegraded)
        assertNull(fine.outcomeDegradedNote)
        assertEquals(2, CameraUiState().withTimerCountdown(2).timerCountdown)
        val ghost = NormalizedRect(0.3f, 0.2f, 0.7f, 0.9f)
        val withGhost = CameraUiState().withGhost(ghost)
        assertEquals(ghost, withGhost.ghostRect)
        // The next capture clears it.
        assertNull(withGhost.withCaptureStarted().withCaptureFinished().ghostRect)
    }

    @Test
    fun `the port adds a click only for ALWAYS where the pipeline did not already play the mandatory one`() {
        assertTrue(ShutterSoundRule.portShouldPlay(ShutterSoundMode.ALWAYS, mandatoryInRegion = false))
        // The capture pipeline already plays it where it is mandatory: never twice.
        assertFalse(ShutterSoundRule.portShouldPlay(ShutterSoundMode.ALWAYS, mandatoryInRegion = true))
        assertFalse(ShutterSoundRule.portShouldPlay(ShutterSoundMode.AUTO, mandatoryInRegion = true))
        assertFalse(ShutterSoundRule.portShouldPlay(ShutterSoundMode.AUTO, mandatoryInRegion = false))
    }
}

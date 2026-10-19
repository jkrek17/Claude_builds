package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.di.ShutterSoundRule
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.outcome.OutcomePlan
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
        val degraded = CameraUiState().withOutcomePlan(OutcomePlan(CaptureEnhancement.OFF, true, "Night mode isn't available on this phone"))
        assertTrue(degraded.outcomeDegraded)
        assertEquals("Night mode isn't available on this phone", degraded.outcomeDegradedNote)
        assertEquals(2, CameraUiState().withTimerCountdown(2).timerCountdown)
        val ghost = NormalizedRect(0.3f, 0.2f, 0.7f, 0.9f)
        val withGhost = CameraUiState().withGhost(ghost)
        assertEquals(ghost, withGhost.ghostRect)
        // The next capture clears it.
        assertNull(withGhost.withCaptureStarted().withCaptureFinished().ghostRect)
    }

    @Test
    fun `shutter sound plays when asked or required`() {
        assertTrue(ShutterSoundRule.shouldPlay(ShutterSoundMode.ALWAYS, mandatoryInRegion = false))
        assertTrue(ShutterSoundRule.shouldPlay(ShutterSoundMode.AUTO, mandatoryInRegion = true))
        assertFalse(ShutterSoundRule.shouldPlay(ShutterSoundMode.AUTO, mandatoryInRegion = false))
    }
}

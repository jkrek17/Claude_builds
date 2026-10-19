package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.composition.engine.GroupReadiness
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.GroupStatus
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.composition.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timer cycle and labels, Selfie's default countdown, group dots and chip, the arm-out cue, chip priority. */
class LiveCuesTest {

    @Test
    fun `the clock cycles Off, 3, 10 and back`() {
        assertEquals(3, TimerLogic.next(0))
        assertEquals(10, TimerLogic.next(3))
        assertEquals(0, TimerLogic.next(10))
        assertEquals(0, TimerLogic.next(7))
    }

    @Test
    fun `in Group the timer label says it waits for everyone`() {
        assertEquals(TimerLogic.LabelKind.OFF, TimerLogic.labelKind(0, Outcome.GROUP))
        assertEquals(TimerLogic.LabelKind.WHEN_EVERYONE_READY, TimerLogic.labelKind(3, Outcome.GROUP))
        assertEquals(TimerLogic.LabelKind.SECONDS, TimerLogic.labelKind(10, Outcome.ANYTHING))
    }

    @Test
    fun `Selfie arms a 3 second countdown and gives it back on leaving`() {
        assertEquals(TimerLogic.SelfieTimerAction.ARM, TimerLogic.selfieTimer(Outcome.ANYTHING, Outcome.SELFIE, 0, autoArmed = false))
        assertEquals(TimerLogic.SelfieTimerAction.ARM, TimerLogic.selfieTimer(null, Outcome.SELFIE, 0, autoArmed = false))
        assertEquals(TimerLogic.SelfieTimerAction.DISARM, TimerLogic.selfieTimer(Outcome.SELFIE, Outcome.PORTRAIT, 3, autoArmed = true))
    }

    @Test
    fun `Selfie never overrides a timer the user set`() {
        assertEquals(TimerLogic.SelfieTimerAction.NONE, TimerLogic.selfieTimer(Outcome.ANYTHING, Outcome.SELFIE, 10, autoArmed = false))
        assertEquals(TimerLogic.SelfieTimerAction.NONE, TimerLogic.selfieTimer(Outcome.SELFIE, Outcome.ANYTHING, 10, autoArmed = true))
        assertEquals(TimerLogic.SelfieTimerAction.NONE, TimerLogic.selfieTimer(Outcome.SELFIE, Outcome.ANYTHING, 3, autoArmed = false))
    }

    @Test
    fun `one dot per face, the ready ones filled, capped`() {
        assertEquals(listOf(true, true, false), GroupCues.dots(GroupStatus(false, facesTotal = 3, facesReady = 2)))
        assertEquals(GroupCues.MAX_DOTS, GroupCues.dots(GroupStatus(false, facesTotal = 30, facesReady = 30)).size)
        assertTrue(GroupCues.dots(GroupStatus(false, 0, 0)).isEmpty())
    }

    @Test
    fun `dots cluster symmetrically over 12 o'clock`() {
        assertEquals(-90f, GroupCues.dotAngleDegrees(0, 1), 0.001f)
        assertEquals(-99f, GroupCues.dotAngleDegrees(0, 2), 0.001f)
        assertEquals(-81f, GroupCues.dotAngleDegrees(1, 2), 0.001f)
    }

    @Test
    fun `the group chip is the first blocker, and silent when ready`() {
        val blocked = GroupStatus(false, 4, 3, listOf(GroupReadiness.BLOCKER_EYES_CLOSED, GroupReadiness.BLOCKER_TOO_SMALL))
        assertEquals("Eyes closed", GroupCues.chip(blocked)?.text)
        assertNull(GroupCues.chip(GroupStatus(true, 4, 4)))
        assertNull(GroupCues.chip(null))
    }

    private fun faceComposition(height: Float): SmoothedComposition {
        val bounds = NormalizedRect(0.3f, 0.1f, 0.7f, 0.1f + height)
        val subject = DetectedSubject(id = 1, kind = SubjectKind.FACE, bounds = bounds, face = DetectedFace(id = 1, bounds = bounds))
        return SmoothedComposition.EMPTY.copy(primarySubject = subject)
    }

    @Test
    fun `Selfie says Arm out a little when the face fills over 45 percent of the height`() {
        assertTrue(SelfieCues.armOut(Outcome.SELFIE, faceComposition(0.5f)))
        assertFalse(SelfieCues.armOut(Outcome.SELFIE, faceComposition(0.4f)))
        assertFalse(SelfieCues.armOut(Outcome.PORTRAIT, faceComposition(0.6f)))
        assertEquals("Arm out a little", SelfieCues.armOutChip().text)
    }

    @Test
    fun `a hold instruction beats a group blocker, which beats the arm-out cue`() {
        val group = GroupStatus(false, 2, 1, listOf(GroupReadiness.BLOCKER_CUT_OFF))
        val state = CameraUiState(outcome = Outcome.SELFIE, composition = faceComposition(0.6f))
        assertEquals("Arm out a little", LiveChipRules.overrideChip(state)?.text)
        assertEquals("Someone's cut off", LiveChipRules.overrideChip(state.copy(groupStatus = group))?.text)
        assertEquals("Hold still", LiveChipRules.overrideChip(state.copy(groupStatus = group, holdInstruction = "Hold still"))?.text)
        assertNull(LiveChipRules.overrideChip(CameraUiState()))
    }
}

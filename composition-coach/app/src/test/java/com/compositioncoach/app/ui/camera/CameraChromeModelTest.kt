package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.ui.settings.UiPreferences
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GroupStatus
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.SmoothedComposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Easy vs Coach, Pro, the hold dim, group readiness and what heads-up guidance hears. */
class CameraChromeModelTest {

    private val moveRight = SmoothedComposition.EMPTY.copy(
        displayScore = 64,
        activeRecommendations = listOf(previewRecommendation(Direction.RIGHT)),
        scene = SceneClassification(SceneType.PORTRAIT, 0.9f),
    )
    private val state = CameraUiState(composition = moveRight, settings = CoachSettings(showScore = true))

    @Test
    fun `Easy shows no number even with show score on`() {
        val model = CameraChromeModel.of(state, UiPreferences())
        assertEquals(ExperienceMode.EASY, model.experience)
        assertFalse(model.showScore)
        assertFalse(model.coach)
        assertFalse(model.pro)
    }

    @Test
    fun `Coach from either the state or the preference shows the number`() {
        assertTrue(CameraChromeModel.of(state, UiPreferences(experienceMode = ExperienceMode.COACH)).showScore)
        assertTrue(CameraChromeModel.of(state.copy(experienceMode = ExperienceMode.COACH), UiPreferences()).showScore)
        assertFalse(CameraChromeModel.of(state.copy(settings = CoachSettings(showScore = false)), UiPreferences(experienceMode = ExperienceMode.COACH)).showScore)
    }

    @Test
    fun `a running hold dims the coaching layer and its instruction becomes the chip`() {
        val model = CameraChromeModel.of(state.copy(holdProgress = 0.3f, holdInstruction = "Hold still for 4 seconds"))
        assertEquals(HOLD_DIMMED_ALPHA, model.coachingAlpha, 0f)
        assertEquals("Hold still for 4 seconds", model.overrideChip?.text)
        assertEquals("Hold still for 4 seconds", model.headsUp.chipText)
        assertNull(model.headsUp.direction)
        assertEquals(1f, CameraChromeModel.of(state).coachingAlpha, 0f)
    }

    @Test
    fun `in a group scene shoot-ready is the group's readiness`() {
        val ready = moveRight.copy(isShootReady = true)
        assertFalse(CameraChromeModel.of(state.copy(composition = ready, groupStatus = GroupStatus(false, 3, 2))).shootReady)
        assertTrue(CameraChromeModel.of(state.copy(groupStatus = GroupStatus(true, 3, 3))).shootReady)
        assertTrue(CameraChromeModel.of(state.copy(composition = ready)).shootReady)
    }

    @Test
    fun `heads-up hears the headline direction and chip words`() {
        val cue = CameraChromeModel.of(state).headsUp
        assertEquals(Direction.RIGHT, cue.direction)
        assertEquals("Slightly right", cue.chipText)
        assertFalse(cue.shootReady)
    }
}

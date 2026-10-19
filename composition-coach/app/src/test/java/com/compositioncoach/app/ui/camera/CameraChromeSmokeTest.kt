package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.CaptureSessionInfo
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.Steadiness
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.app.ComposeHostActivityRule
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole-flow sanity check for the capture/UI integration: composes exactly what [CameraScreen] draws from
 * [CameraUiState] — [CameraPreviewChrome] (coaching layer + top strip) over a 3:4 box, then
 * [CameraBottomChrome] — with a fake state where Night is running and has paused coaching, the steady-shot gate
 * is holding the shutter, and the lens offers 0.6/1/2, with "A group" picked on a 3 s timer. Each piece of wiring
 * must surface in the semantics tree, upright and at `ROTATION_90`; without Pro controls the Pro chrome is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraChromeSmokeTest {

    val rule = createComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(ComposeHostActivityRule()).around(rule)

    private fun state(rotation: Int, pro: Boolean) = CameraUiState(
        composition = SmoothedComposition.EMPTY.copy(displayScore = 70, scene = SceneClassification(SceneType.PORTRAIT, 0.9f)),
        settings = CoachSettings(onboardingSeen = true, proControls = pro),
        availableEnhancements = setOf(CaptureEnhancement.OFF, CaptureEnhancement.NIGHT),
        enhancement = CaptureEnhancement.NIGHT,
        analysisPausedByEnhancement = true,
        isWaitingForSteady = true,
        steadiness = Steadiness.MOVING,
        zoomRatio = 1f,
        zoomPresets = listOf(0.6f, 1f, 2f),
        deviceRotationDegrees = rotation,
        outcome = Outcome.GROUP,
        timerSeconds = 3,
        captureSession = CaptureSessionInfo(
            lensFacing = LensFacing.BACK,
            activeEnhancement = CaptureEnhancement.NIGHT,
            availableEnhancements = setOf(CaptureEnhancement.OFF, CaptureEnhancement.NIGHT),
            enhancementsPausingCoaching = setOf(CaptureEnhancement.NIGHT),
            analysisPausedByEnhancement = true,
        ),
    )

    private fun compose(rotation: Int, pro: Boolean = true) {
        val state = state(rotation, pro)
        val model = CameraChromeModel.of(state)
        rule.setContent {
            CompositionCoachTheme {
                Column(Modifier.size(360.dp, 760.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                        CameraPreviewChrome(
                            state = state,
                            animationsEnabled = false,
                            onFlashClick = {},
                            onOpenSettings = {},
                            onEnhancementSelected = {},
                            onOnboardingDismissed = {},
                            model = model,
                        )
                    }
                    CameraBottomChrome(
                        state = state,
                        displayZoomRatio = state.zoomRatio,
                        zoomChipVisible = false,
                        lastPhotoUri = null,
                        animationsEnabled = false,
                        onShutterClick = {},
                        onSwitchLensClick = {},
                        onOpenGallery = {},
                        onOutcomeSelected = {},
                        onZoomPresetSelected = {},
                        modifier = Modifier.weight(1f),
                        model = model,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertWired() {
        rule.onNodeWithContentDescription("Photo enhancement: Night").assertExists()
        rule.onNodeWithContentDescription("Coaching paused in Night").assertExists()
        rule.onNodeWithContentDescription(SteadyHint.DESCRIPTION).assertExists()
        rule.onNodeWithContentDescription("Zoom 1×").assertIsSelected()
        rule.onNodeWithContentDescription("Zoom 0.6×").assertIsNotSelected()
        rule.onNodeWithContentDescription("Zoom 2×").assertIsNotSelected()
    }

    private fun assertOutcomeStripAndTimer() {
        rule.onNodeWithText("A group").assertIsSelected()
        rule.onNodeWithText("A person").assertIsNotSelected()
        rule.onNodeWithContentDescription("Self-timer: when everyone's ready").assertExists()
    }

    @Test
    fun `upright, the pill, paused chip, steady hint and selected zoom chip all show`() {
        compose(0)
        assertWired()
    }

    @Test
    fun `at rotation 90, the same four pieces show`() {
        compose(90)
        assertWired()
    }

    @Test
    fun `the outcome strip replaces the mode strip and the timer reads the group label, in both holds`() {
        compose(0)
        assertOutcomeStripAndTimer()
    }

    @Test
    fun `rotated, the outcome strip and timer still show`() {
        compose(90)
        assertOutcomeStripAndTimer()
    }

    @Test
    fun `without Pro controls the enhancement pill and zoom presets are not drawn at all`() {
        compose(0, pro = false)
        rule.onNodeWithContentDescription("Photo enhancement: Night").assertDoesNotExist()
        rule.onNodeWithContentDescription("Zoom 1×").assertDoesNotExist()
        rule.onNodeWithContentDescription("Coaching paused in Night").assertExists()
        rule.onNodeWithText("A group").assertIsSelected()
    }
}

package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composes the real coaching layer — the whole of what the camera screen draws over its preview — with a
 * fake `SmoothedComposition` carrying a physical-RIGHT headline, in portrait and at `ROTATION_90`.
 *
 * It drives [CoachingLayer] rather than `CameraScreen` itself because the screen's composition comes from
 * a live CameraX/ML Kit pipeline that cannot be faked under Robolectric (`AppLaunchTest` covers that the
 * whole Activity survives launch); everything a fake composition can exercise lives in this layer.
 *
 * Beyond "it doesn't crash", it checks the thing rotation actually changes: the cue chip travels with the
 * chevron, so at rotation 0 the chip for "move right" sits in the right half of the frame, and at
 * `ROTATION_90` — where the phone's right edge is up and physical right is at the *bottom* of the screen —
 * it sits in the bottom half instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoachingLayerSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private val moveRight = Recommendation(
        id = "subject.right",
        category = MetricCategory.SUBJECT_PLACEMENT,
        priority = Priority.HIGH,
        confidence = 0.9f,
        severity = Severity.MEDIUM,
        title = "Move slightly right",
        instruction = "Move slightly right",
        direction = Direction.RIGHT,
    )

    private val composition = SmoothedComposition(
        displayScore = 74,
        activeRecommendations = listOf(moveRight),
        isShootReady = false,
        scene = SceneClassification(SceneType.PORTRAIT, 0.9f),
        primarySubject = null,
        raw = CompositionResult.empty().copy(recommendations = listOf(moveRight)),
        displayTarget = NormalizedPoint(0.33f, 0.4f),
    )

    private fun setContent(deviceRotationDegrees: Int) {
        rule.setContent {
            CompositionCoachTheme {
                Box(Modifier.size(300.dp, 400.dp)) {
                    CoachingLayer(
                        composition = composition,
                        guidanceLevel = GuidanceLevel.BALANCED,
                        showScore = true,
                        hasScene = true,
                        deviceRotationDegrees = deviceRotationDegrees,
                        animationsEnabled = false,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun chipBounds(): Rect =
        rule.onNodeWithContentDescription("Move slightly right").fetchSemanticsNode().boundsInRoot

    @Test
    fun `portrait composes and shows the score and the cue chip`() {
        setContent(0)
        rule.onNodeWithText("Slightly right", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Composition score 74").assertExists()
    }

    @Test
    fun `portrait puts the move-right chip in the right half of the frame`() {
        setContent(0)
        val bounds = chipBounds()
        assertTrue("chip should sit right of centre, got $bounds", bounds.center.x > 150f)
    }

    @Test
    fun `rotation 90 composes and moves the chip to the bottom, where physical right now is`() {
        setContent(90)
        rule.onNodeWithText("Slightly right", useUnmergedTree = true).assertExists()
        val bounds = chipBounds()
        assertTrue("chip should sit below centre at ROTATION_90, got $bounds", bounds.center.y > 200f)
    }

    @Test
    fun `awaiting a subject shows the find-subject chip instead of a cue`() {
        val findSubject = moveRight.copy(
            id = "intent.no_subject",
            title = "Looking for a face",
            instruction = "Move closer to your subject",
            direction = Direction.NONE,
        )
        rule.setContent {
            CompositionCoachTheme {
                Box(Modifier.size(300.dp, 400.dp)) {
                    CoachingLayer(
                        composition = composition.copy(
                            activeRecommendations = listOf(findSubject),
                            awaitingSubject = true,
                        ),
                        guidanceLevel = GuidanceLevel.BALANCED,
                        showScore = true,
                        hasScene = true,
                        deviceRotationDegrees = 0,
                        animationsEnabled = false,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Looking for a face", useUnmergedTree = true).assertExists()
    }
}

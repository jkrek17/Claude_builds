package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real [RotatedChrome] through Compose (Robolectric, no device) and checks the rotation that
 * actually **reaches the screen**, not just the number a pure function returned.
 *
 * `RotationTruthTableTest` proves `uprightChromeAngleDegrees` is right; this proves the angle survives
 * `rememberControlCounterRotation`'s animation, `RotatedChromeMath`'s width/height swap and the
 * `placeWithLayer { rotationZ = ... }` in between — the three places a "the chrome angle is correct but
 * nothing on screen rotates" bug could hide, and the ones a code reviewer cannot check by reading.
 *
 * The probe is two markers laid out left-to-right. Rotating the pair clockwise by 90 degrees must leave
 * "a" **above** "b"; counter-clockwise would leave "a" below. A bounding box alone cannot tell those
 * apart, which is exactly how a sign error ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RotatedChromeRotationTest {

    @get:Rule
    val rule = createComposeRule()

    private val deviceRotation = mutableIntStateOf(0)

    private fun setContent() {
        rule.setContent {
            Box(Modifier.size(400.dp)) {
                RotatedChrome(deviceRotationDegrees = deviceRotation.intValue) {
                    Row {
                        Box(Modifier.size(40.dp).testTag(LEFT_MARKER))
                        Box(Modifier.size(40.dp).testTag(RIGHT_MARKER))
                    }
                }
            }
        }
    }

    /** Settles composition *and* the 250ms counter-rotation animation before measuring. */
    private fun rotateTo(degrees: Int) {
        deviceRotation.intValue = degrees
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(RotationAnimation.ROTATION_ANIM_MS * 4L)
        rule.waitForIdle()
    }

    private fun boundsOf(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun `portrait leaves chrome unrotated - the trusted anchor`() {
        setContent()
        rotateTo(0)
        val left = boundsOf(LEFT_MARKER)
        val right = boundsOf(RIGHT_MARKER)
        assertTrue("a must stay left of b at rotation 0, got a=$left b=$right", left.left < right.left)
        assertEquals("a and b must stay on the same row", left.top, right.top, 0.5f)
    }

    @Test
    fun `right edge up rotates chrome CLOCKWISE, so the left marker ends up on top`() {
        // The field bug: at ROTATION_90 the guidance text read bottom-to-top (counter-clockwise, its own
        // up pointing at the screen's left). Correct is clockwise: reading top-to-bottom with its up
        // toward the screen's right, which is where physical up is in that hold.
        setContent()
        rotateTo(90)
        val left = boundsOf(LEFT_MARKER)
        val right = boundsOf(RIGHT_MARKER)
        assertTrue(
            "clockwise means the row's left marker moves to the TOP; got a=$left b=$right",
            left.top < right.top,
        )
        assertEquals("the pair must now be stacked vertically", left.left, right.left, 0.5f)
    }

    @Test
    fun `left edge up rotates chrome the other way, so the left marker ends up at the bottom`() {
        setContent()
        rotateTo(270)
        val left = boundsOf(LEFT_MARKER)
        val right = boundsOf(RIGHT_MARKER)
        assertTrue(
            "at ROTATION_270 the row's left marker must move to the BOTTOM; got a=$left b=$right",
            left.top > right.top,
        )
    }

    @Test
    fun `upside down flips the pair left-to-right`() {
        setContent()
        rotateTo(180)
        val left = boundsOf(LEFT_MARKER)
        val right = boundsOf(RIGHT_MARKER)
        assertTrue("a must end up right of b when upside down; got a=$left b=$right", left.left > right.left)
    }

    @Test
    fun `the box reports its rotated footprint, so a 90 degree hold does not overflow its parent`() {
        // RotatedChromeMath's job: a wide, short row rotated 90 degrees occupies a narrow, tall box. The
        // union of the two markers' on-screen bounds is what the parent has to make room for.
        setContent()
        rotateTo(90)
        val union = boundsOf(LEFT_MARKER).let { a -> boundsOf(RIGHT_MARKER).let { b -> a.expandToInclude(b) } }
        assertTrue("rotated content should be taller than wide, got $union", union.height > union.width)
    }

    @Test
    fun `rotating through every hold and back leaves chrome exactly as it started`() {
        // Catches an accumulating-angle bug in rememberControlCounterRotation (the unwrapped total is
        // deliberately allowed to pass 360, but a full turn must land back on the identity).
        setContent()
        rotateTo(0)
        val before = boundsOf(LEFT_MARKER)
        for (degrees in intArrayOf(90, 180, 270, 0)) rotateTo(degrees)
        val after = boundsOf(LEFT_MARKER)
        assertEquals(before.left, after.left, 0.5f)
        assertEquals(before.top, after.top, 0.5f)
    }

    private fun Rect.expandToInclude(other: Rect) = Rect(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    private companion object {
        const val LEFT_MARKER = "chrome-marker-a"
        const val RIGHT_MARKER = "chrome-marker-b"
    }
}

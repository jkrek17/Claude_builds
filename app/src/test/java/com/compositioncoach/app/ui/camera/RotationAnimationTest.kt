package com.compositioncoach.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class RotationAnimationTest {

    @Test
    fun `no change reports zero delta`() {
        assertEquals(0f, RotationAnimation.shortestSignedDelta(90f, 90f), 1e-4f)
    }

    @Test
    fun `a simple forward step within 180 degrees is unchanged`() {
        assertEquals(90f, RotationAnimation.shortestSignedDelta(0f, 90f), 1e-4f)
        assertEquals(-90f, RotationAnimation.shortestSignedDelta(90f, 0f), 1e-4f)
    }

    @Test
    fun `crossing the 270 to 0 boundary takes the short plus-90 hop, not minus-270`() {
        assertEquals(90f, RotationAnimation.shortestSignedDelta(270f, 0f), 1e-4f)
    }

    @Test
    fun `crossing the 0 to 270 boundary takes the short minus-90 hop, not plus-270`() {
        assertEquals(-90f, RotationAnimation.shortestSignedDelta(0f, 270f), 1e-4f)
    }

    @Test
    fun `a 180 degree change is unambiguous either direction`() {
        assertEquals(180f, kotlin.math.abs(RotationAnimation.shortestSignedDelta(0f, 180f)), 1e-4f)
    }

    @Test
    fun `nextUnwrappedRotation keeps a running total that takes the short way at each step`() {
        // A full clockwise sweep through 0 -> 90 -> 180 -> 270 -> 0 should keep climbing by +90 each
        // step (never jumping back down by 270), so the unwrapped total ends at 360, not 0.
        var unwrapped = 0f
        unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 90)
        assertEquals(90f, unwrapped, 1e-4f)
        unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 180)
        assertEquals(180f, unwrapped, 1e-4f)
        unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 270)
        assertEquals(270f, unwrapped, 1e-4f)
        unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 0)
        assertEquals(360f, unwrapped, 1e-4f)
    }

    @Test
    fun `nextUnwrappedRotation takes the short way even from a non-zero-mod-360 starting point`() {
        // Starting already "wrapped around" once (360 + 270 = 630), a new reading of 0 should still be
        // treated as a short +90 hop forward (to 720), not a huge jump back down to 0.
        val unwrapped = RotationAnimation.nextUnwrappedRotation(630f, 0)
        assertEquals(720f, unwrapped, 1e-4f)
    }

    @Test
    fun `repeated identical readings never drift the unwrapped total`() {
        var unwrapped = 90f
        repeat(5) { unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 90) }
        assertEquals(90f, unwrapped, 1e-4f)
    }
}

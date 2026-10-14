package com.compositioncoach.app.ui.camera

import kotlin.math.atan2
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
        unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 90f)
        assertEquals(90f, unwrapped, 1e-4f)
        unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 180f)
        assertEquals(180f, unwrapped, 1e-4f)
        unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 270f)
        assertEquals(270f, unwrapped, 1e-4f)
        unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 0f)
        assertEquals(360f, unwrapped, 1e-4f)
    }

    @Test
    fun `nextUnwrappedRotation takes the short way even from a non-zero-mod-360 starting point`() {
        // Starting already "wrapped around" once (360 + 270 = 630), a new reading of 0 should still be
        // treated as a short +90 hop forward (to 720), not a huge jump back down to 0.
        val unwrapped = RotationAnimation.nextUnwrappedRotation(630f, 0f)
        assertEquals(720f, unwrapped, 1e-4f)
    }

    @Test
    fun `repeated identical readings never drift the unwrapped total`() {
        var unwrapped = 90f
        repeat(5) { unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, 90f) }
        assertEquals(90f, unwrapped, 1e-4f)
    }

    // --- Chrome-angle regressions ------------------------------------------------------------------------
    // Chrome is derived from the same OverlayMapper.rotateVectorToDisplay the directional arrow uses, so
    // the two can never drift apart -- but note they are NOT the same expression (see
    // uprightChromeAngleDegrees' KDoc): the shipped build paired an inverted rotateVectorToDisplay with a
    // negated atan2 here, and the two errors cancelled, so chrome looked right while every arrow and box
    // was 180 degrees out. RotationTruthTableTest pins each half against gravity independently; these
    // tests pin the chrome half's concrete values.

    @Test
    fun `chrome angle matches the concrete field-verified fix at ROTATION_90 (right edge up)`() {
        // The screenshot this task fixes: phone's right edge points up: chrome should rotate +90 degrees
        // clockwise so upright text reads top-to-bottom with its own "up" toward the screen's right,
        // instead of the old -90 (bottom-to-top, up-left).
        assertEquals(90f, OverlayMapper.uprightChromeAngleDegrees(90), 1e-3f)
    }

    @Test
    fun `chrome angle is the identity on deviceRotationDegrees, modulo 360, at every quantized rotation`() {
        // atan2 returns its result in (-180, 180], so 270 comes back as -90 -- the same angle, 90 degrees
        // short of a full turn either way round -- rather than the literal float 270.0.
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val expected = RotationAnimation.shortestSignedDelta(0f, rotation.toFloat())
            val actual = RotationAnimation.shortestSignedDelta(0f, OverlayMapper.uprightChromeAngleDegrees(rotation))
            assertEquals("rotation=$rotation", expected, actual, 1e-3f)
        }
    }

    @Test
    fun `chrome angle is derived from the same rotateVectorToDisplay the arrow uses, for every rotation`() {
        // Reproduce OverlayMapper.uprightChromeAngleDegrees's own arrow-anchored derivation independently
        // here (rather than calling it) so a future edit to that function's internals still gets caught if
        // it drifts from "the clockwise angle whose glyph-up equals physical-up's display vector".
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val (vx, vy) = OverlayMapper.rotateVectorToDisplay(0f, -1f, rotation)
            // A glyph rotated clockwise by phi has its up at (sin phi, -cos phi); it must match where
            // physical up lands on screen, which is (vx, vy). Hence phi = atan2(vx, -vy).
            val expected = Math.toDegrees(atan2(vx, -vy).toDouble()).toFloat()
            assertEquals("rotation=$rotation", expected, OverlayMapper.uprightChromeAngleDegrees(rotation), 1e-3f)
        }
    }

    @Test
    fun `rememberControlCounterRotation's target is no longer the negated deviceRotationDegrees`() {
        // The old, buggy formula: guard against silently reintroducing it.
        for (rotation in intArrayOf(90, 270)) {
            assert(OverlayMapper.uprightChromeAngleDegrees(rotation) != -rotation.toFloat()) {
                "uprightChromeAngleDegrees($rotation) regressed to the old -deviceRotationDegrees sign"
            }
        }
    }
}

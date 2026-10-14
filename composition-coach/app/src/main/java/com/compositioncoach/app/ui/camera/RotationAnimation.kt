package com.compositioncoach.app.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Pixel-style "controls rotate in place" support: [CameraTopBar]/[BottomControlBar]/[ScoreBadge]/
 * [GuidanceBanner] icons and text, wrapped in [RotatedChrome], rotate by
 * [OverlayMapper.uprightChromeAngleDegrees] so they stay upright to the person holding the phone, even
 * though the *layout* (and the live preview content itself) never moves — see
 * `CameraUiState.deviceRotationDegrees`, [OverlayMapper.uprightChromeAngleDegrees]'s KDoc for the exact
 * sign convention (and the bug it fixes), and `app/README.md`'s rotation section for the rest of the fix.
 * This file is the pure math + the one small Compose helper every rotating control shares.
 */
object RotationAnimation {
    const val ROTATION_ANIM_MS = 250

    /**
     * The signed angular difference, in `(-180, 180]` degrees, that represents the *shorter* way around
     * the circle from [fromDegrees] to [toDegrees]. `animateFloatAsState` interpolates linearly between
     * raw target values, so animating directly from a value like 270 to 0 would visually spin
     * three-quarters of the way around instead of the 90-degree hop that's actually happening — this is
     * what [nextUnwrappedRotation] uses to avoid that.
     */
    fun shortestSignedDelta(fromDegrees: Float, toDegrees: Float): Float {
        val raw = (toDegrees - fromDegrees) % 360f
        val normalized = (raw + 360f) % 360f
        return if (normalized > 180f) normalized - 360f else normalized
    }

    /**
     * Adds [shortestSignedDelta] to [currentUnwrapped], an ever-accumulating (never wrapped mod 360)
     * angle. Feeding a sequence of quantized target angles (e.g. every
     * [OverlayMapper.uprightChromeAngleDegrees] of 0/90/180/270) through this repeatedly produces a
     * running total that always takes the shorter turn at each step — including across the 270 -> 0 (or
     * 0 -> 270) boundary — so animating from one call's result to the next never spins the "long way
     * around".
     */
    fun nextUnwrappedRotation(currentUnwrapped: Float, newTargetDegrees: Float): Float =
        currentUnwrapped + shortestSignedDelta(currentUnwrapped, newTargetDegrees)
}

/**
 * Remembers an ever-accumulating target angle tracking [OverlayMapper.uprightChromeAngleDegrees] of
 * [deviceRotationDegrees] via [RotationAnimation.nextUnwrappedRotation], and animates it over
 * [RotationAnimation.ROTATION_ANIM_MS] — the angle Pixel-style chrome should visually rotate by (see
 * [RotatedChrome]) so it counter-rotates against the device and stays upright. Passed straight to
 * [RotatedChrome]'s `placeWithLayer { rotationZ = ... }`.
 *
 * The value is *not* negated — see [OverlayMapper.uprightChromeAngleDegrees]'s KDoc for the derivation
 * and for why `-deviceRotationDegrees` (the original field bug) is the wrong sign.
 *
 * Two details that are load-bearing rather than incidental:
 *  - The [LaunchedEffect] is keyed on the **target angle**, not on [deviceRotationDegrees]. They are 1:1
 *    today, but keying on the value actually consumed means a change to how the angle is derived can
 *    never silently stop re-arming the animation.
 *  - `unwrapped` is seeded from the *current* target, not from zero, so chrome that first enters
 *    composition while the phone is already held sideways renders at the right angle immediately instead
 *    of animating in from upright.
 *
 * That the resulting angle really does reach the screen with the right sign — through this animation,
 * [RotatedChromeMath]'s width/height swap and the graphics layer — is covered end to end by
 * `RotatedChromeRotationTest` (Robolectric + Compose), since none of it is checkable by reading.
 */
@Composable
fun rememberControlCounterRotation(deviceRotationDegrees: Int): Float {
    val target = OverlayMapper.uprightChromeAngleDegrees(deviceRotationDegrees)
    var unwrapped by remember { mutableFloatStateOf(target) }
    LaunchedEffect(target) {
        unwrapped = RotationAnimation.nextUnwrappedRotation(unwrapped, target)
    }
    val animated by animateFloatAsState(
        targetValue = unwrapped,
        animationSpec = tween(RotationAnimation.ROTATION_ANIM_MS),
        label = "controlCounterRotation",
    )
    return animated
}

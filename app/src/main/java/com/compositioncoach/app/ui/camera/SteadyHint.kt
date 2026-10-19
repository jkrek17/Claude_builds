package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.camera.Steadiness

/**
 * When the "Hold still" chip shows and how much the readiness arc dims for movement. Movement only matters
 * at the moment of taking the photo, so the hint never appears during ordinary framing: only while the
 * shutter is engaged (pressed, armed or capturing) or the framing is ready to shoot. Pure, unit-tested in
 * `SteadyHintTest`.
 */
object SteadyHint {
    const val TEXT = "Hold still"
    const val DESCRIPTION = "Hold the phone still"

    /** The arc's alpha while the phone is moving, so the user learns that movement holds the shot back. */
    const val MOVING_ARC_ALPHA = 0.7f

    fun showsHoldStill(steadiness: Steadiness, shutterEngaged: Boolean, shootReady: Boolean): Boolean =
        steadiness == Steadiness.MOVING && (shutterEngaged || shootReady)

    /** The awaiting-subject dim wins (the score is stale); otherwise movement dims to 70%. */
    fun arcAlpha(awaitingSubject: Boolean, steadiness: Steadiness): Float = when {
        awaitingSubject -> GuidanceFormatter.AWAITING_SUBJECT_ARC_ALPHA
        steadiness == Steadiness.MOVING -> MOVING_ARC_ALPHA
        else -> 1f
    }

    /** What "shoot at the peak" waits for: ready framing and a phone that is not moving. */
    fun peakReached(shootReady: Boolean, steadiness: Steadiness): Boolean =
        shootReady && steadiness != Steadiness.MOVING
}

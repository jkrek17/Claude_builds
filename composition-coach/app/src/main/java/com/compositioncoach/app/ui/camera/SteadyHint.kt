package com.compositioncoach.app.ui.camera

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.compositioncoach.app.camera.Steadiness
import kotlinx.coroutines.delay

/**
 * When the "Hold still" chip shows and how much the readiness arc dims for movement. Movement only matters
 * at the moment of taking the photo, so the hint never appears during ordinary framing: only while the
 * pipeline is holding the shutter for the phone to settle, while the shutter is engaged (pressed, armed or
 * capturing) or while the framing is ready to shoot. Once shown it stays at least [MIN_VISIBLE_MS], so a gyro
 * reading that flickers across the MOVING threshold cannot make the chip blink. Pure, unit-tested in
 * `SteadyHintTest`; [rememberHoldStillVisible] is the Compose glue.
 */
object SteadyHint {
    const val TEXT = "Hold still"
    const val DESCRIPTION = "Hold the phone still"

    /** The arc's alpha while the phone is moving, so the user learns that movement holds the shot back. */
    const val MOVING_ARC_ALPHA = 0.7f

    /** Shortest time the hint stays once shown. */
    const val MIN_VISIBLE_MS = 500L

    fun showsHoldStill(steadiness: Steadiness, shutterEngaged: Boolean, shootReady: Boolean): Boolean =
        steadiness == Steadiness.MOVING && (shutterEngaged || shootReady)

    /**
     * Whether the hint is called for right now: the steady-shot gate is holding the shutter
     * (`CameraUiState.isWaitingForSteady`), or the phone is MOVING while the shutter is engaged or the framing
     * is ready ([showsHoldStill]).
     */
    fun wanted(waitingForSteady: Boolean, steadiness: Steadiness, shutterEngaged: Boolean, shootReady: Boolean): Boolean =
        waitingForSteady || showsHoldStill(steadiness, shutterEngaged, shootReady)

    /**
     * How much longer a hint shown at [shownAtMs] must stay before it may hide at [nowMs]: 0 once
     * [MIN_VISIBLE_MS] has passed (or when it was never shown).
     */
    fun remainingHoldMs(shownAtMs: Long?, nowMs: Long): Long =
        if (shownAtMs == null) 0L else (shownAtMs + MIN_VISIBLE_MS - nowMs).coerceAtLeast(0L)

    /** Whether the hint is on screen: [wanted] now, or shown at [shownAtMs] less than [MIN_VISIBLE_MS] ago. */
    fun visible(wanted: Boolean, shownAtMs: Long?, nowMs: Long): Boolean =
        wanted || remainingHoldMs(shownAtMs, nowMs) > 0L

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

/**
 * [wanted] held on screen for at least [SteadyHint.MIN_VISIBLE_MS]: true as soon as it is wanted, and false
 * only once it is no longer wanted *and* the minimum has passed since it appeared.
 */
@Composable
fun rememberHoldStillVisible(wanted: Boolean, clock: () -> Long = SystemClock::uptimeMillis): Boolean {
    var shownAt by remember { mutableStateOf<Long?>(if (wanted) clock() else null) }
    var visible by remember { mutableStateOf(wanted) }
    LaunchedEffect(wanted) {
        if (wanted) {
            if (!visible) shownAt = clock()
            visible = true
            return@LaunchedEffect
        }
        delay(SteadyHint.remainingHoldMs(shownAt, clock()))
        visible = false
        shownAt = null
    }
    return visible || wanted
}

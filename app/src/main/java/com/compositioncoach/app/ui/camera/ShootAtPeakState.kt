package com.compositioncoach.app.ui.camera

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import com.compositioncoach.app.ui.camera.ShootAtPeakController.Effect

/**
 * Compose-facing wrapper around [ShootAtPeakController]: the shutter's gesture calls [press]/[release],
 * a frame loop ticks the controller while a press or an armed shutter is live, and [armed]/[countdown]
 * are the snapshot state the shutter draws from. The controller's [Effect]s go to [onEffect], which the
 * screen turns into haptics and captures.
 */
@Stable
class ShootAtPeakState internal constructor(
    private val controller: ShootAtPeakController,
    private val clock: () -> Long,
) {
    /** True while waiting for the peak; drives the "Auto" label and the inner ring. */
    var armed by mutableStateOf(false)
        private set

    /** 1 -> 0 over the timeout while armed, else null. */
    var countdown by mutableStateOf<Float?>(null)
        private set

    /** True from finger-down until the gesture is fully over; the frame loop runs only while this holds. */
    var engaged by mutableStateOf(false)
        private set

    internal var onEffect: (Effect) -> Unit = {}
    internal var latchOnTap: Boolean = false

    fun press() = handle(controller.onPress(clock(), latchOnTap))

    fun release(cancelled: Boolean) = handle(controller.onRelease(clock(), cancelled))

    /** TalkBack's long-press: arm without holding. */
    fun armForAccessibility() = handle(controller.armLatched(clock()))

    internal fun tick(readyToFire: Boolean) = handle(controller.onTick(clock(), readyToFire))

    internal fun reset() {
        controller.reset()
        sync()
    }

    private fun handle(effect: Effect) {
        sync()
        if (effect != Effect.NONE) onEffect(effect)
    }

    private fun sync() {
        armed = controller.isArmed
        countdown = controller.countdownFraction(clock())
        engaged = controller.state != ShootAtPeakController.State.Idle
    }
}

/**
 * Remembers a [ShootAtPeakState] wired to the live [readyToFire] condition (shoot-ready and not moving, see
 * [SteadyHint.peakReached]). [latchOnTap] is Settings > More > "Auto shoot when ready". [onCapture] is an
 * ordinary tap; [onArmed] and [onFire] carry the double-tick and medium haptics and the auto capture.
 * While [enabled] is false (a capture is already running) presses are ignored and any armed state drops.
 */
@Composable
fun rememberShootAtPeakState(
    readyToFire: Boolean,
    enabled: Boolean,
    latchOnTap: Boolean,
    onCapture: () -> Unit,
    onArmed: () -> Unit,
    onFire: () -> Unit,
    onCancelled: () -> Unit = {},
): ShootAtPeakState {
    val state = remember { ShootAtPeakState(ShootAtPeakController(), SystemClock::uptimeMillis) }
    val currentReady by rememberUpdatedState(readyToFire)
    val currentEnabled by rememberUpdatedState(enabled)
    val capture by rememberUpdatedState(onCapture)
    val armed by rememberUpdatedState(onArmed)
    val fire by rememberUpdatedState(onFire)
    val cancelled by rememberUpdatedState(onCancelled)

    state.latchOnTap = latchOnTap
    state.onEffect = { effect ->
        when (effect) {
            Effect.CAPTURE -> if (currentEnabled) capture()
            Effect.ARMED -> armed()
            Effect.FIRE -> if (currentEnabled) fire()
            Effect.CANCELLED -> cancelled()
            Effect.NONE -> Unit
        }
    }

    // One tick per frame while a gesture or an armed shutter is live; nothing runs otherwise.
    LaunchedEffect(state.engaged) {
        while (state.engaged) {
            withFrameMillis { }
            state.tick(currentReady)
        }
    }
    // A capture from anywhere else (volume key, the auto fire itself) ends any arming.
    LaunchedEffect(enabled) {
        if (!enabled && state.armed) state.reset()
    }
    DisposableEffect(state) { onDispose { state.reset() } }
    return state
}

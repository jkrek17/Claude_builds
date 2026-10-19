package com.compositioncoach.app.ui.camera

/**
 * "Shoot at the peak": hold the shutter for [armThresholdMs] and the app takes the photo itself, the first
 * moment the framing is ready and the phone is not moving — or after [timeoutMs] regardless, so an armed
 * shutter can never hang. A release *before* arming is an ordinary capture; a release *while* armed
 * cancels without firing. With `latchOnTap` (Settings > More > "Auto shoot when ready") every tap arms
 * immediately and stays armed after the finger lifts; a second tap cancels.
 *
 * Pure and clock-free: every call takes `nowMs`, so the whole state machine is unit-tested on the JVM
 * (`ShootAtPeakControllerTest`). The Compose glue ([rememberShootAtPeakState]) feeds it press/release and
 * a frame tick, and turns the returned [Effect]s into haptics and `onShutterClick`.
 */
class ShootAtPeakController(
    val armThresholdMs: Long = ARM_THRESHOLD_MS,
    val timeoutMs: Long = TIMEOUT_MS,
) {
    sealed interface State {
        data object Idle : State

        /** Finger down, not yet armed. */
        data class Pressed(val downAtMs: Long) : State

        /** Waiting for the peak. [latched] = armed by a tap in auto-shoot mode, so a release does not cancel. */
        data class Armed(val armedAtMs: Long, val latched: Boolean) : State

        /** Fired or cancelled while the finger is still down; the eventual release must do nothing. */
        data object Spent : State
    }

    enum class Effect {
        NONE,

        /** Ordinary capture: released before the arm threshold. Light tick + capture. */
        CAPTURE,

        /** Just armed. Double tick. */
        ARMED,

        /** The peak (or the timeout) arrived. Medium tick + capture. */
        FIRE,

        /** Disarmed without firing. */
        CANCELLED,
    }

    var state: State = State.Idle
        private set

    val isArmed: Boolean get() = state is State.Armed

    /** Finger down on the shutter. */
    fun onPress(nowMs: Long, latchOnTap: Boolean = false): Effect = when (val s = state) {
        State.Idle -> if (latchOnTap) {
            state = State.Armed(nowMs, latched = true)
            Effect.ARMED
        } else {
            state = State.Pressed(nowMs)
            Effect.NONE
        }
        // A tap on a latched shutter is the way to call it off.
        is State.Armed -> if (s.latched) {
            state = State.Spent
            Effect.CANCELLED
        } else {
            Effect.NONE
        }
        is State.Pressed, State.Spent -> Effect.NONE
    }

    /** Finger up, or the gesture was [cancelled] (it slid off, or the system took it). */
    fun onRelease(nowMs: Long, cancelled: Boolean = false): Effect = when (val s = state) {
        is State.Pressed -> {
            state = State.Idle
            when {
                cancelled -> Effect.NONE
                // Held past the threshold but no tick ran to arm it (a stalled frame): neither a tap nor a
                // hold the user saw acknowledged, so take nothing rather than surprise them.
                nowMs - s.downAtMs >= armThresholdMs -> Effect.NONE
                else -> Effect.CAPTURE
            }
        }
        is State.Armed -> if (s.latched) {
            Effect.NONE
        } else {
            state = State.Idle
            Effect.CANCELLED
        }
        State.Spent -> {
            state = State.Idle
            Effect.NONE
        }
        State.Idle -> Effect.NONE
    }

    /**
     * Called every frame while not [State.Idle]. Arms a press held past the threshold, and fires an armed
     * shutter once [readyToFire] (shoot-ready and not moving) or once [timeoutMs] has passed.
     */
    fun onTick(nowMs: Long, readyToFire: Boolean): Effect {
        val s = state
        if (s is State.Pressed && nowMs - s.downAtMs >= armThresholdMs) {
            state = State.Armed(nowMs, latched = false)
            return Effect.ARMED
        }
        if (s is State.Armed && (readyToFire || nowMs - s.armedAtMs >= timeoutMs)) {
            state = if (s.latched) State.Idle else State.Spent
            return Effect.FIRE
        }
        return Effect.NONE
    }

    /** TalkBack's long-press action: holding is not possible there, so arm as a latch. */
    fun armLatched(nowMs: Long): Effect {
        if (state !is State.Idle) return Effect.NONE
        state = State.Armed(nowMs, latched = true)
        return Effect.ARMED
    }

    /** Disarm from outside (the screen leaving, a capture already running). */
    fun reset() {
        state = State.Idle
    }

    /** 1 at the moment of arming, falling to 0 at the timeout; null when not armed. Drives the inner ring. */
    fun countdownFraction(nowMs: Long): Float? {
        val s = state as? State.Armed ?: return null
        val left = 1f - (nowMs - s.armedAtMs).toFloat() / timeoutMs
        return left.coerceIn(0f, 1f)
    }

    companion object {
        const val ARM_THRESHOLD_MS = 350L
        const val TIMEOUT_MS = 4_000L
    }
}

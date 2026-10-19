package com.compositioncoach.app.outcome

import kotlin.math.ceil

/**
 * The self-timer as a pure state machine driven by [tick] with an explicit clock (unit-tested in
 * `CountdownControllerTest`; [com.compositioncoach.app.ui.camera.CaptureFlowCoordinator] ticks it).
 *
 * `start(3 s)` counts 3, 2, 1, then [Phase.Fire]. With `handOverToReadiness` (the Group outcome: prop the
 * phone, join the photo) the end of the count does not fire blindly: it moves to [Phase.AwaitingReady] and
 * fires as soon as the group has been ready for [readyHoldMs] (so a one-frame blip does not fire it), or at
 * the deadline [readinessTimeoutMs] after the count ended, whichever comes first. [Phase.Fire] is returned
 * exactly once; the controller is then idle again.
 */
class CountdownController(
    private val readinessTimeoutMs: Long = READINESS_TIMEOUT_MS,
    private val readyHoldMs: Long = READY_HOLD_MS,
) {
    sealed interface Phase {
        data object Idle : Phase

        /** Counting down; [secondsLeft] is what the screen shows (3, 2, 1). */
        data class Counting(val secondsLeft: Int) : Phase

        /** The count ended in a group: waiting for everyone; [msLeft] until it fires anyway. */
        data class AwaitingReady(val msLeft: Long) : Phase

        /** Take the photo now. */
        data object Fire : Phase
    }

    private var countEndsAt = 0L
    private var deadline = 0L
    private var handOver = false
    private var readySince: Long? = null
    private var running = false

    var phase: Phase = Phase.Idle
        private set

    val isRunning: Boolean get() = running

    /** Starts a [seconds]-second count at [nowMs]. A non-positive [seconds] fires on the first [tick]. */
    fun start(nowMs: Long, seconds: Int, handOverToReadiness: Boolean): Phase {
        countEndsAt = nowMs + seconds.coerceAtLeast(0) * 1_000L
        deadline = countEndsAt + readinessTimeoutMs
        handOver = handOverToReadiness
        readySince = null
        running = true
        phase = if (seconds > 0) Phase.Counting(seconds) else Phase.Counting(0)
        return phase
    }

    /** Advances to [nowMs]; [groupReady] is the latest `GroupStatus.ready` (ignored outside a hand-over). */
    fun tick(nowMs: Long, groupReady: Boolean): Phase {
        if (!running) return Phase.Idle.also { phase = it }
        phase = when {
            nowMs < countEndsAt -> Phase.Counting(ceil((countEndsAt - nowMs) / 1_000.0).toInt())
            !handOver -> fire()
            else -> awaitReady(nowMs, groupReady)
        }
        return phase
    }

    private fun awaitReady(nowMs: Long, groupReady: Boolean): Phase {
        if (groupReady) {
            val since = readySince ?: nowMs.also { readySince = it }
            if (nowMs - since >= readyHoldMs) return fire()
        } else {
            readySince = null
        }
        if (nowMs >= deadline) return fire()
        return Phase.AwaitingReady(deadline - nowMs)
    }

    private fun fire(): Phase {
        running = false
        return Phase.Fire
    }

    fun cancel() {
        running = false
        readySince = null
        phase = Phase.Idle
    }

    /** What `CameraUiState.timerCountdown` shows for [phase]: the seconds left, 0 while waiting for the group, else null. */
    fun displayValue(phase: Phase = this.phase): Int? = when (phase) {
        is Phase.Counting -> phase.secondsLeft
        is Phase.AwaitingReady -> 0
        Phase.Fire, Phase.Idle -> null
    }

    companion object {
        /** In Group, how long after the count ends the timer waits for everyone before firing anyway. */
        const val READINESS_TIMEOUT_MS = 15_000L

        /** How long the group must stay ready before the hand-over fires. */
        const val READY_HOLD_MS = 400L
    }
}

package com.compositioncoach.app.camera

/**
 * Rolling ~1s FPS counter fed by camera frame timestamps (nanoseconds, CameraX
 * `ImageProxy.imageInfo.timestamp`/`FrameAnalysis.timestampNanos`).
 *
 * Camera frame timestamps are not guaranteed to share one monotonic clock across the lifetime of the
 * app: [CameraController.bind] rebinds the same lens on a lens switch, a bind retry, or simply returning
 * from the background, and a new camera capture session is free to restart its own timestamp base. A
 * naive rolling window (`elapsed = timestamp - windowStart`) goes negative the instant a rebind delivers
 * a timestamp smaller than the previous window's start — and a negative `elapsed` never satisfies the
 * "window closed, compute fps" check, so [fps] would silently freeze at its last value forever (or until
 * the new clock happens to catch back up to the old one, which may never happen) instead of recovering.
 * This class treats any out-of-order timestamp as "start a new window" instead of letting that happen.
 *
 * [MAX_PLAUSIBLE_FPS] additionally guards against reporting a nonsensical spike from a pathological
 * `elapsedNanos` close to zero (e.g. two frames sharing an identical or near-identical timestamp).
 */
class FrameFpsTracker(private val windowNanos: Long = WINDOW_NANOS) {
    private var hasWindowStart = false
    private var windowStartNanos = 0L
    private var countInWindow = 0

    var fps: Float = 0f
        private set

    /** Feed one frame's timestamp. Returns the (possibly unchanged) [fps] for convenience. */
    fun onFrame(timestampNanos: Long): Float {
        if (!hasWindowStart || timestampNanos < windowStartNanos) {
            // First frame ever, or the camera's timestamp clock went backwards (a rebind) - restart the
            // window rather than letting `elapsed` go negative.
            hasWindowStart = true
            windowStartNanos = timestampNanos
            countInWindow = 1
            return fps
        }
        countInWindow++
        val elapsedNanos = timestampNanos - windowStartNanos
        if (elapsedNanos >= windowNanos) {
            fps = (countInWindow * windowNanos.toFloat() / elapsedNanos).coerceIn(0f, MAX_PLAUSIBLE_FPS)
            countInWindow = 0
            windowStartNanos = timestampNanos
        }
        return fps
    }

    /** Resets to the just-constructed state; call when the pipeline (re)starts after a stop. */
    fun reset() {
        hasWindowStart = false
        windowStartNanos = 0L
        countInWindow = 0
        fps = 0f
    }

    companion object {
        const val WINDOW_NANOS = 1_000_000_000L
        private const val MAX_PLAUSIBLE_FPS = 240f
    }
}

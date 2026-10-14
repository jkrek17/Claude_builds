package com.compositioncoach.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFpsTrackerTest {

    private val windowNanos = 1_000_000_000L

    @Test
    fun `reports 0 fps before the first window closes`() {
        val tracker = FrameFpsTracker(windowNanos)
        assertEquals(0f, tracker.onFrame(0L))
        assertEquals(0f, tracker.onFrame(500_000_000L))
    }

    @Test
    fun `computes fps once a window's worth of time has elapsed`() {
        val tracker = FrameFpsTracker(windowNanos)
        // 5 frames at t = 0, 250ms, 500ms, 750ms, 1000ms: the window (measured from the *first* frame,
        // not from a nominal t=0) closes exactly on the 5th frame, having counted all 5.
        tracker.onFrame(0L)
        tracker.onFrame(250_000_000L)
        tracker.onFrame(500_000_000L)
        tracker.onFrame(750_000_000L)
        val fps = tracker.onFrame(1_000_000_000L)
        assertEquals(5f, fps, 0.01f)
    }

    @Test
    fun `a backwards timestamp (camera rebind) restarts the window instead of going negative`() {
        val tracker = FrameFpsTracker(windowNanos)
        tracker.onFrame(5_000_000_000L)
        tracker.onFrame(5_500_000_000L)

        // Simulate a rebind: the new capture session's clock starts back near zero.
        val afterRebind = tracker.onFrame(1_000_000L)
        // Must not have computed a negative or otherwise bogus fps from `elapsed < 0`.
        assertTrue("fps must never go negative", afterRebind >= 0f)

        // The tracker should recover and report a sane fps once a fresh window closes post-rebind.
        var t = 1_000_000L
        repeat(5) {
            t += 200_000_000L
            tracker.onFrame(t)
        }
        assertTrue("fps should have recovered to a plausible positive value", tracker.fps in 0f..240f)
    }

    @Test
    fun `never reports an implausible fps spike from a near-zero elapsed`() {
        val tracker = FrameFpsTracker(windowNanos)
        tracker.onFrame(0L)
        // A pathological duplicate/near-duplicate timestamp should never explode fps past the cap.
        val fps = tracker.onFrame(1L)
        assertTrue(fps <= 240f)
    }

    @Test
    fun `reset returns to the just-constructed state`() {
        val tracker = FrameFpsTracker(windowNanos)
        tracker.onFrame(0L)
        tracker.onFrame(500_000_000L)
        tracker.onFrame(1_000_000_000L)
        assertTrue(tracker.fps > 0f)

        tracker.reset()
        assertEquals(0f, tracker.fps)
        // First frame after reset must be treated as a fresh window start (no negative elapsed vs the
        // stale windowStartNanos from before reset).
        val afterReset = tracker.onFrame(1_000L)
        assertEquals(0f, afterReset)
    }
}

package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSamplerTest {

    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long): Long {
            now += ms
            return now
        }
    }

    @Test
    fun `first frame is always accepted`() {
        val clock = FakeClock()
        val sampler = AdaptiveSampler(targetIntervalMs = 100, nowMs = { clock.now })
        assertTrue(sampler.shouldAccept(clock.now))
    }

    @Test
    fun `frames within the interval are rejected`() {
        val clock = FakeClock()
        val sampler = AdaptiveSampler(targetIntervalMs = 100, nowMs = { clock.now })
        assertTrue(sampler.shouldAccept(clock.now))
        clock.advance(50)
        assertFalse(sampler.shouldAccept(clock.now))
        clock.advance(40) // total 90ms since last accept, still < 100
        assertFalse(sampler.shouldAccept(clock.now))
    }

    @Test
    fun `frame right at the interval boundary is accepted`() {
        val clock = FakeClock()
        val sampler = AdaptiveSampler(targetIntervalMs = 100, nowMs = { clock.now })
        assertTrue(sampler.shouldAccept(clock.now))
        clock.advance(100)
        assertTrue(sampler.shouldAccept(clock.now))
    }

    @Test
    fun `high latency backs off the interval`() {
        val sampler = AdaptiveSampler(targetIntervalMs = 100)
        sampler.onFrameProcessed(250) // much slower than the 100ms interval
        assertTrue("interval should have grown past the target", sampler.intervalMs > 100)
        assertTrue("interval should cover the observed latency", sampler.intervalMs >= 250)
        assertEquals(250L, sampler.lastLatency)
    }

    @Test
    fun `interval recovers back down when there is headroom`() {
        val sampler = AdaptiveSampler(targetIntervalMs = 100)
        sampler.onFrameProcessed(300) // back off
        val backedOff = sampler.intervalMs
        assertTrue(backedOff > 100)

        // Report several fast frames; interval should ease back down towards the target.
        repeat(20) { sampler.onFrameProcessed(5) }
        assertTrue("interval should have recovered", sampler.intervalMs < backedOff)
        assertEquals(100L, sampler.intervalMs)
    }

    @Test
    fun `interval is clamped to the configured bounds`() {
        val sampler = AdaptiveSampler(targetIntervalMs = 100, minIntervalMs = 80, maxIntervalMs = 500)
        sampler.onFrameProcessed(10_000)
        assertEquals(500L, sampler.intervalMs)

        repeat(200) { sampler.onFrameProcessed(0) }
        assertTrue(sampler.intervalMs >= 80L)
    }

    @Test
    fun `setTargetIntervalMs raises the floor immediately`() {
        val sampler = AdaptiveSampler(targetIntervalMs = 100)
        sampler.setTargetIntervalMs(200)
        assertEquals(200L, sampler.intervalMs)
    }
}

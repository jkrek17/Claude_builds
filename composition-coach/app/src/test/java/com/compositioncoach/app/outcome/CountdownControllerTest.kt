package com.compositioncoach.app.outcome

import com.compositioncoach.app.outcome.CountdownController.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownControllerTest {

    private val c = CountdownController(readinessTimeoutMs = 15_000, readyHoldMs = 400)

    @Test
    fun `counts whole seconds down and fires once at the end`() {
        assertEquals(Phase.Counting(3), c.start(0, 3, handOverToReadiness = false))
        assertEquals(Phase.Counting(3), c.tick(100, false))
        assertEquals(Phase.Counting(3), c.tick(999, false))
        assertEquals(Phase.Counting(2), c.tick(1_000, false))
        assertEquals(Phase.Counting(1), c.tick(2_500, false))
        assertEquals(Phase.Fire, c.tick(3_000, false))
        assertFalse(c.isRunning)
        assertEquals(Phase.Idle, c.tick(3_100, false))
    }

    @Test
    fun `display values are the seconds left, 0 while waiting for the group, null otherwise`() {
        c.start(0, 10, handOverToReadiness = true)
        assertEquals(10, c.displayValue())
        c.tick(9_100, false)
        assertEquals(1, c.displayValue())
        c.tick(10_000, false)
        assertEquals(0, c.displayValue())
        assertNull(c.displayValue(Phase.Fire))
        assertNull(c.displayValue(Phase.Idle))
    }

    @Test
    fun `in a group the end of the count waits for everyone to be ready`() {
        c.start(0, 3, handOverToReadiness = true)
        assertEquals(Phase.AwaitingReady(15_000), c.tick(3_000, false))
        assertTrue(c.tick(5_000, false) is Phase.AwaitingReady)
        // Ready, but not for long enough yet: a blip does not fire.
        assertTrue(c.tick(5_100, true) is Phase.AwaitingReady)
        assertTrue(c.tick(5_200, false) is Phase.AwaitingReady)
        assertTrue(c.tick(6_000, true) is Phase.AwaitingReady)
        assertTrue(c.tick(6_300, true) is Phase.AwaitingReady)
        assertEquals(Phase.Fire, c.tick(6_400, true))
    }

    @Test
    fun `a group that never gets ready fires at the deadline`() {
        c.start(0, 3, handOverToReadiness = true)
        assertTrue(c.tick(17_999, false) is Phase.AwaitingReady)
        assertEquals(Phase.Fire, c.tick(18_000, false))
    }

    @Test
    fun `readiness during the count itself does not cut it short`() {
        c.start(0, 3, handOverToReadiness = true)
        assertEquals(Phase.Counting(2), c.tick(1_500, true))
        assertEquals(Phase.Counting(1), c.tick(2_900, true))
    }

    @Test
    fun `cancel stops the count`() {
        c.start(0, 3, handOverToReadiness = false)
        c.cancel()
        assertFalse(c.isRunning)
        assertEquals(Phase.Idle, c.tick(5_000, false))
    }
}

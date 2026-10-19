package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.ui.camera.ShootAtPeakController.Effect
import com.compositioncoach.app.ui.camera.ShootAtPeakController.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootAtPeakControllerTest {

    private val c = ShootAtPeakController(armThresholdMs = 350, timeoutMs = 4_000)

    // --- Arm threshold -------------------------------------------------------------------------------

    @Test
    fun `a quick tap is an ordinary capture`() {
        c.onPress(1_000)
        assertEquals(Effect.NONE, c.onTick(1_100, readyToFire = true))
        assertEquals(Effect.CAPTURE, c.onRelease(1_200))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `a release just under the threshold still captures`() {
        c.onPress(0)
        c.onTick(349, readyToFire = false)
        assertEquals(Effect.CAPTURE, c.onRelease(349))
    }

    @Test
    fun `holding to the threshold arms, once`() {
        c.onPress(0)
        assertEquals(Effect.NONE, c.onTick(349, readyToFire = false))
        assertEquals(Effect.ARMED, c.onTick(350, readyToFire = false))
        assertTrue(c.isArmed)
        assertEquals(Effect.NONE, c.onTick(400, readyToFire = false))
    }

    @Test
    fun `ready framing does not fire before the press has armed`() {
        c.onPress(0)
        assertEquals(Effect.NONE, c.onTick(100, readyToFire = true))
        assertFalse(c.isArmed)
    }

    // --- Fire condition ------------------------------------------------------------------------------

    @Test
    fun `an armed shutter fires the first frame the peak arrives`() {
        c.onPress(0)
        c.onTick(350, readyToFire = false)
        assertEquals(Effect.NONE, c.onTick(900, readyToFire = false))
        assertEquals(Effect.FIRE, c.onTick(916, readyToFire = true))
        assertFalse(c.isArmed)
    }

    @Test
    fun `arming while already at the peak fires on the next frame`() {
        c.onPress(0)
        assertEquals(Effect.ARMED, c.onTick(350, readyToFire = true))
        assertEquals(Effect.FIRE, c.onTick(366, readyToFire = true))
    }

    @Test
    fun `after firing, the finger lifting takes nothing more`() {
        c.onPress(0)
        c.onTick(350, readyToFire = false)
        c.onTick(500, readyToFire = true)
        assertEquals(State.Spent, c.state)
        assertEquals(Effect.NONE, c.onRelease(800))
        assertEquals(State.Idle, c.state)
    }

    // --- Timeout ------------------------------------------------------------------------------------

    @Test
    fun `an armed shutter fires at the timeout regardless`() {
        c.onPress(0)
        c.onTick(350, readyToFire = false)
        assertEquals(Effect.NONE, c.onTick(4_349, readyToFire = false))
        assertEquals(Effect.FIRE, c.onTick(4_350, readyToFire = false))
    }

    @Test
    fun `the countdown runs from 1 at arming to 0 at the timeout`() {
        assertNull(c.countdownFraction(0))
        c.onPress(0)
        c.onTick(350, readyToFire = false)
        assertEquals(1f, c.countdownFraction(350)!!, 1e-4f)
        assertEquals(0.5f, c.countdownFraction(2_350)!!, 1e-4f)
        assertEquals(0f, c.countdownFraction(9_999)!!, 1e-4f)
    }

    // --- Cancel on release ---------------------------------------------------------------------------

    @Test
    fun `releasing an armed shutter cancels without firing`() {
        c.onPress(0)
        c.onTick(350, readyToFire = false)
        assertEquals(Effect.CANCELLED, c.onRelease(1_000))
        assertEquals(State.Idle, c.state)
        assertEquals(Effect.NONE, c.onTick(1_016, readyToFire = true))
    }

    @Test
    fun `a gesture the system cancels before arming takes nothing`() {
        c.onPress(0)
        assertEquals(Effect.NONE, c.onRelease(100, cancelled = true))
        assertEquals(State.Idle, c.state)
    }

    // --- Auto-shoot mode (every tap behaves like a hold) ---------------------------------------------

    @Test
    fun `in auto-shoot mode a tap arms at once and survives the release`() {
        assertEquals(Effect.ARMED, c.onPress(0, latchOnTap = true))
        assertEquals(Effect.NONE, c.onRelease(80))
        assertTrue(c.isArmed)
        assertEquals(Effect.FIRE, c.onTick(1_200, readyToFire = true))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `in auto-shoot mode a second tap cancels`() {
        c.onPress(0, latchOnTap = true)
        c.onRelease(80)
        assertEquals(Effect.CANCELLED, c.onPress(1_000, latchOnTap = true))
        assertEquals(Effect.NONE, c.onRelease(1_080))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `the accessibility arm latches and times out like a tap-arm`() {
        assertEquals(Effect.ARMED, c.armLatched(0))
        assertEquals(Effect.NONE, c.armLatched(10))
        assertEquals(Effect.FIRE, c.onTick(4_000, readyToFire = false))
    }

    @Test
    fun `reset drops any arming`() {
        c.onPress(0)
        c.onTick(350, readyToFire = false)
        c.reset()
        assertEquals(State.Idle, c.state)
        assertEquals(Effect.NONE, c.onRelease(500))
    }
}

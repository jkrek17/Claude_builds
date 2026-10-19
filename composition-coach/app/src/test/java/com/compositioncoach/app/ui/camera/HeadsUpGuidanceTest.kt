package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vibration pattern table from `docs/WAVE2.md` section 5 and the when-to-emit rules. */
class HeadsUpGuidanceTest {

    private fun pulses(direction: Direction) = HapticPatterns.forDirection(direction)?.let(HapticPatterns::pulseCount)

    @Test
    fun `left one pulse, right two, up one long, down two long, rotate three short`() {
        assertEquals(1, pulses(Direction.LEFT))
        assertEquals(2, pulses(Direction.RIGHT))
        assertEquals(1, pulses(Direction.UP))
        assertEquals(2, pulses(Direction.DOWN))
        assertEquals(3, pulses(Direction.ROTATE_CLOCKWISE))
        assertEquals(3, pulses(Direction.ROTATE_COUNTER_CLOCKWISE))
        assertEquals(HapticPatterns.LONG_MS, HapticPatterns.forDirection(Direction.UP)!![1])
        assertEquals(HapticPatterns.PULSE_MS, HapticPatterns.forDirection(Direction.LEFT)!![1])
        assertTrue(HapticPatterns.forDirection(Direction.DOWN)!!.drop(1).filterIndexed { i, _ -> i % 2 == 0 }.all { it == HapticPatterns.LONG_MS })
    }

    @Test
    fun `every pattern is distinct, and closer, back and none have none`() {
        val patterns = Direction.entries.mapNotNull { HapticPatterns.forDirection(it)?.toList() }.toSet()
        assertEquals(5, patterns.size)
        assertNull(HapticPatterns.forDirection(Direction.CLOSER))
        assertNull(HapticPatterns.forDirection(Direction.BACK))
        assertNull(HapticPatterns.forDirection(Direction.NONE))
    }

    @Test
    fun `vibration on change, then every 2_5 s while the cue persists`() {
        val planner = HeadsUpPlanner()
        val right = HeadsUpCue(Direction.RIGHT, "Slightly right", false)
        assertEquals(1, planner.onTick(0, right, vibration = true, voice = false).size)
        assertTrue(planner.onTick(1_000, right, vibration = true, voice = false).isEmpty())
        assertEquals(1, planner.onTick(2_500, right, vibration = true, voice = false).size)
        val left = HeadsUpCue(Direction.LEFT, "Slightly left", false)
        assertEquals(
            listOf(HeadsUpAction.Vibrate(HapticPatterns.forDirection(Direction.LEFT)!!)),
            planner.onTick(2_600, left, vibration = true, voice = false),
        )
    }

    @Test
    fun `one strong pulse on entering shoot-ready, not repeated`() {
        val planner = HeadsUpPlanner()
        val ready = HeadsUpCue(null, "SHOOT", true)
        assertEquals(listOf(HeadsUpAction.StrongPulse), planner.onTick(0, ready, vibration = true, voice = false))
        assertTrue(planner.onTick(5_000, ready, vibration = true, voice = false).isEmpty())
    }

    @Test
    fun `voice speaks the chip when it changes, as a word`() {
        val planner = HeadsUpPlanner()
        assertEquals(listOf(HeadsUpAction.Speak("Slightly right")), planner.onTick(0, HeadsUpCue(Direction.RIGHT, "Slightly right", false), false, true))
        assertTrue(planner.onTick(3_000, HeadsUpCue(Direction.RIGHT, "Slightly right", false), false, true).isEmpty())
        assertEquals(listOf(HeadsUpAction.Speak("Shoot")), planner.onTick(3_100, HeadsUpCue(null, "SHOOT", true), false, true))
    }

    @Test
    fun `with both on, voice carries the words and vibration only marks shoot-ready`() {
        val planner = HeadsUpPlanner()
        assertEquals(listOf(HeadsUpAction.Speak("Raise")), planner.onTick(0, HeadsUpCue(Direction.UP, "Raise", false), true, true))
        assertEquals(
            listOf(HeadsUpAction.StrongPulse, HeadsUpAction.Speak("Shoot")),
            planner.onTick(500, HeadsUpCue(null, "SHOOT", true), true, true),
        )
    }

    @Test
    fun `both off emits nothing`() {
        assertTrue(HeadsUpPlanner().onTick(0, HeadsUpCue(Direction.LEFT, "Slightly left", true), false, false).isEmpty())
    }
}

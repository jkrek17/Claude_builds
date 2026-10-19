package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.Direction

/**
 * Heads-up guidance (`docs/WAVE2.md` section 5): the live cue said by vibration or voice, for shooting without
 * looking at the screen. Settings > More > "Guide by vibration" / "Guide by voice", both off by default.
 * This file is the pure part (pattern table and when-to-emit planner, `HeadsUpGuidanceTest`); the Android
 * outputs are [HapticGuide] and [VoiceGuide] in `HeadsUpOutputs.kt`.
 */
object HapticPatterns {
    const val PULSE_MS = 40L
    const val LONG_MS = 160L
    const val SHORT_MS = 25L
    const val GAP_MS = 120L
    const val SHORT_GAP_MS = 80L

    /** How often a persisting headline repeats its pattern. */
    const val REPEAT_MS = 2_500L

    /** The single strong pulse on entering shoot-ready. */
    val SHOOT_READY: LongArray = longArrayOf(0L, 90L)

    /**
     * Android waveform timings (`[wait, on, off, on, ...]`) per direction: left one pulse, right two, up one long,
     * down two long, rotate three short. Closer / back / no direction have no pattern: the brackets and chip
     * carry those, and an ambiguous buzz is worse than none.
     */
    fun forDirection(direction: Direction): LongArray? = when (direction) {
        Direction.LEFT -> longArrayOf(0L, PULSE_MS)
        Direction.RIGHT -> longArrayOf(0L, PULSE_MS, GAP_MS, PULSE_MS)
        Direction.UP -> longArrayOf(0L, LONG_MS)
        Direction.DOWN -> longArrayOf(0L, LONG_MS, GAP_MS, LONG_MS)
        Direction.ROTATE_CLOCKWISE, Direction.ROTATE_COUNTER_CLOCKWISE ->
            longArrayOf(0L, SHORT_MS, SHORT_GAP_MS, SHORT_MS, SHORT_GAP_MS, SHORT_MS)
        Direction.CLOSER, Direction.BACK, Direction.NONE -> null
    }

    /** Number of "on" segments in [pattern]: how many pulses a person feels. */
    fun pulseCount(pattern: LongArray): Int = pattern.indices.count { it % 2 == 1 }
}

/** What the live screen is saying right now, reduced to what heads-up guidance needs. */
data class HeadsUpCue(val direction: Direction?, val chipText: String?, val shootReady: Boolean)

/** One thing to emit. */
sealed interface HeadsUpAction {
    data class Vibrate(val pattern: LongArray) : HeadsUpAction {
        override fun equals(other: Any?) = other is Vibrate && other.pattern.contentEquals(pattern)
        override fun hashCode() = pattern.contentHashCode()
    }
    data object StrongPulse : HeadsUpAction
    data class Speak(val text: String) : HeadsUpAction
}

/**
 * Decides, tick by tick, what to vibrate and what to say. Vibration: the direction's pattern when the headline
 * changes and every [HapticPatterns.REPEAT_MS] while it persists, one strong pulse on entering shoot-ready.
 * Voice: the chip text whenever it changes. Never both at the full rate: with voice on, voice carries the
 * words and vibration only marks shoot-ready.
 */
class HeadsUpPlanner {
    private var lastDirection: Direction? = null
    private var lastPatternAtMs: Long = 0L
    private var wasShootReady = false
    private var lastSpoken: String? = null

    fun onTick(nowMs: Long, cue: HeadsUpCue, vibration: Boolean, voice: Boolean): List<HeadsUpAction> {
        val actions = mutableListOf<HeadsUpAction>()
        val enteredReady = cue.shootReady && !wasShootReady
        wasShootReady = cue.shootReady

        if (vibration) {
            if (enteredReady) actions += HeadsUpAction.StrongPulse
            val pattern = if (cue.shootReady || voice) null else cue.direction?.let { HapticPatterns.forDirection(it) }
            if (pattern == null) {
                lastDirection = null
            } else if (cue.direction != lastDirection || nowMs - lastPatternAtMs >= HapticPatterns.REPEAT_MS) {
                actions += HeadsUpAction.Vibrate(pattern)
                lastDirection = cue.direction
                lastPatternAtMs = nowMs
            }
        }

        if (voice) {
            val text = cue.chipText?.let(::spoken)
            if (text != null && text != lastSpoken) actions += HeadsUpAction.Speak(text)
            lastSpoken = text
        }
        return actions
    }

    fun reset() {
        lastDirection = null
        lastPatternAtMs = 0L
        wasShootReady = false
        lastSpoken = null
    }

    companion object {
        /** "SHOOT" reads as shouting (and some engines spell capitals out); speak it as a word. */
        fun spoken(chipText: String): String =
            chipText.trim().lowercase().replaceFirstChar { it.titlecase() }
    }
}

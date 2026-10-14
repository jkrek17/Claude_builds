package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.Direction

/**
 * Canonical, plain-language physical instruction for each [Direction]. Centralised here so every
 * analyzer says "Move slightly left" the same way instead of each inventing its own phrasing — the UI
 * and any voice/haptic feedback can pattern-match on these exact strings.
 */
object InstructionText {
    fun forDirection(direction: Direction): String = when (direction) {
        Direction.LEFT -> "Move slightly left"
        Direction.RIGHT -> "Move slightly right"
        Direction.UP -> "Raise camera"
        Direction.DOWN -> "Lower camera"
        Direction.CLOSER -> "Move closer"
        Direction.BACK -> "Step back"
        Direction.ROTATE_CLOCKWISE -> "Rotate slightly clockwise"
        Direction.ROTATE_COUNTER_CLOCKWISE -> "Rotate slightly counter-clockwise"
        Direction.NONE -> "Hold steady"
    }
}

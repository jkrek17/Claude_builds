package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.compositioncoach.composition.model.Direction
import kotlin.math.abs

/**
 * Which edge of the (never-rotating) preview an edge cue is drawn on. This is a *screen* edge, derived
 * from a *physical* direction — "the physical right edge is whichever screen edge is currently physically
 * right" (`docs/COACHING_UI.md`, Principle 4).
 *
 * The derivation is deliberately not a hand-written 4x4 table: [forDirection] rotates the direction's own
 * unit vector through [OverlayMapper.rotateVectorToDisplay] — the same call the overlay's geometry goes
 * through — and reads off the dominant screen axis. A table would be a second, independent copy of the
 * rotation convention that could drift from the one the truth table pins; this cannot.
 */
enum class CueEdge {
    LEFT, TOP, RIGHT, BOTTOM;

    /** The `Box` alignment that hugs this edge (centred along it). */
    fun toAlignment(): Alignment = when (this) {
        LEFT -> Alignment.CenterStart
        TOP -> Alignment.TopCenter
        RIGHT -> Alignment.CenterEnd
        BOTTOM -> Alignment.BottomCenter
    }

    /**
     * [toAlignment] slid *along* this edge by [bias] (-1 = hard toward the edge's start, +1 = its end,
     * 0 = centred), staying hugged to the edge itself. Used to move the physical-top chrome stack out of
     * an edge chevron's way when the two share an edge, instead of pushing the chevron off the edge it is
     * supposed to be pointing out of.
     */
    fun alignmentShiftedAlongEdge(bias: Float): Alignment = when (this) {
        LEFT -> BiasAlignment(horizontalBias = -1f, verticalBias = bias)
        RIGHT -> BiasAlignment(horizontalBias = 1f, verticalBias = bias)
        TOP -> BiasAlignment(horizontalBias = bias, verticalBias = -1f)
        BOTTOM -> BiasAlignment(horizontalBias = bias, verticalBias = 1f)
    }

    companion object {
        /**
         * The screen edge the physical [direction] points at when the phone is held at
         * [deviceRotationDegrees], or null for directions that aren't an edge cue (CLOSER/BACK/ROTATE/NONE
         * — see [GuidanceFormatter.cueFor]).
         *
         * Worked example, the one the field report was about: `RIGHT` is the physical vector `(1, 0)`. At
         * `deviceRotationDegrees == 90` (the phone's right edge up) [OverlayMapper.rotateVectorToDisplay]
         * sends it to `(0, 1)` — the screen's **bottom**, which is where physical right actually is in
         * that hold — so the chevron anchors on the bottom edge, not the right one.
         */
        fun forDirection(direction: Direction, deviceRotationDegrees: Int): CueEdge? {
            val (rawDx, rawDy) = when (direction) {
                Direction.LEFT -> -1f to 0f
                Direction.RIGHT -> 1f to 0f
                Direction.UP -> 0f to -1f
                Direction.DOWN -> 0f to 1f
                else -> return null
            }
            val (dx, dy) = OverlayMapper.rotateVectorToDisplay(rawDx, rawDy, deviceRotationDegrees)
            return if (abs(dx) >= abs(dy)) {
                if (dx >= 0f) RIGHT else LEFT
            } else {
                if (dy >= 0f) BOTTOM else TOP
            }
        }

        /**
         * The screen edge physical *up* currently appears at — where the score chip, secondary icons and
         * the bubble level are laid out (`docs/COACHING_UI.md`, "Landscape"). Same answer as
         * [chromeStackEdgeFor] by construction; expressed here in [CueEdge] terms so a cue can ask "am I
         * about to land on top of the chrome stack?" without converting between two enums.
         */
        fun physicalTop(deviceRotationDegrees: Int): CueEdge =
            forDirection(Direction.UP, deviceRotationDegrees) ?: TOP
    }
}

/** Insets a cue from [edge] by [inset], leaving the other three sides alone. */
fun Modifier.cueEdgePadding(edge: CueEdge, inset: Dp): Modifier = when (edge) {
    CueEdge.LEFT -> this.padding(start = inset)
    CueEdge.TOP -> this.padding(top = inset)
    CueEdge.RIGHT -> this.padding(end = inset)
    CueEdge.BOTTOM -> this.padding(bottom = inset)
}

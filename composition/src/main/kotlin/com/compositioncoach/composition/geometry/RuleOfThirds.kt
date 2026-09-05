package com.compositioncoach.composition.geometry

import com.compositioncoach.composition.model.NormalizedPoint

/**
 * The classic rule-of-thirds grid: two evenly spaced vertical and horizontal lines dividing the
 * frame into a 3x3 grid, and their four intersections ("power points").
 */
object RuleOfThirds {
    const val LOWER_THIRD = 1f / 3f
    const val UPPER_THIRD = 2f / 3f

    /** x-positions of the two vertical thirds lines. */
    val verticalLines: List<Float> = listOf(LOWER_THIRD, UPPER_THIRD)

    /** y-positions of the two horizontal thirds lines. */
    val horizontalLines: List<Float> = listOf(LOWER_THIRD, UPPER_THIRD)

    /** The four rule-of-thirds intersection points. */
    val intersections: List<NormalizedPoint> =
        verticalLines.flatMap { x -> horizontalLines.map { y -> NormalizedPoint(x, y) } }

    /** The nearest of the two vertical thirds lines to [x]. */
    fun nearestVerticalThird(x: Float): Float =
        if (kotlin.math.abs(x - LOWER_THIRD) <= kotlin.math.abs(x - UPPER_THIRD)) LOWER_THIRD else UPPER_THIRD

    /** The nearest of the two horizontal thirds lines to [y]. */
    fun nearestHorizontalThird(y: Float): Float =
        if (kotlin.math.abs(y - LOWER_THIRD) <= kotlin.math.abs(y - UPPER_THIRD)) LOWER_THIRD else UPPER_THIRD

    /** The nearest of the four power points to [p]. */
    fun nearestIntersection(p: NormalizedPoint): NormalizedPoint =
        intersections.minBy { it.distanceTo(p) }

    /**
     * The thirds intersection that leaves open "looking room" on [roomOnSide]: pass -1f when the empty
     * space must be on the left of the subject (subject sits on the right third), +1f for the mirror case.
     * Falls back to the nearest vertical third when [roomOnSide] is 0.
     */
    fun preferredIntersection(anchor: NormalizedPoint, roomOnSide: Float): NormalizedPoint {
        val x = if (roomOnSide < 0f) UPPER_THIRD else if (roomOnSide > 0f) LOWER_THIRD else nearestVerticalThird(anchor.x)
        val y = nearestHorizontalThird(anchor.y)
        return NormalizedPoint(x, y)
    }
}

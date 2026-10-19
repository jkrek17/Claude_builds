package com.compositioncoach.composition.model

import kotlin.math.abs
import kotlin.math.hypot

/**
 * A point in normalized frame coordinates.
 *
 * Coordinate convention used throughout the app (all analyzers, overlays and detectors):
 *  - The frame is the *upright preview as the user sees it* (already rotated for display orientation,
 *    and already mirrored for the front camera so that "left" means the left side of the screen).
 *  - x runs 0.0 (left edge) .. 1.0 (right edge)
 *  - y runs 0.0 (top edge)  .. 1.0 (bottom edge)
 */
data class NormalizedPoint(val x: Float, val y: Float) {
    fun distanceTo(other: NormalizedPoint): Float = hypot(x - other.x, y - other.y)
    operator fun minus(other: NormalizedPoint) = NormalizedPoint(x - other.x, y - other.y)
    operator fun plus(other: NormalizedPoint) = NormalizedPoint(x + other.x, y + other.y)

    companion object {
        val CENTER = NormalizedPoint(0.5f, 0.5f)
    }
}

/** An axis-aligned rectangle in normalized frame coordinates (see [NormalizedPoint]). */
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = (width.coerceAtLeast(0f)) * (height.coerceAtLeast(0f))
    val center: NormalizedPoint get() = NormalizedPoint((left + right) / 2f, (top + bottom) / 2f)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun contains(p: NormalizedPoint): Boolean = p.x in left..right && p.y in top..bottom

    fun intersect(other: NormalizedRect): NormalizedRect? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r > l && b > t) NormalizedRect(l, t, r, b) else null
    }

    fun intersectionOverUnion(other: NormalizedRect): Float {
        val inter = intersect(other)?.area ?: return 0f
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun clampToFrame(): NormalizedRect = NormalizedRect(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    /** Distance from each edge of this rect to the corresponding frame edge (0 = touching). */
    val distanceToLeftEdge: Float get() = left
    val distanceToRightEdge: Float get() = 1f - right
    val distanceToTopEdge: Float get() = top
    val distanceToBottomEdge: Float get() = 1f - bottom

    fun inflate(dx: Float, dy: Float = dx): NormalizedRect = NormalizedRect(left - dx, top - dy, right + dx, bottom + dy)

    companion object {
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)
        fun fromCenter(center: NormalizedPoint, width: Float, height: Float) = NormalizedRect(
            center.x - width / 2f, center.y - height / 2f, center.x + width / 2f, center.y + height / 2f,
        )
    }
}

/** A line segment in normalized coordinates, e.g. a detected horizon or a leading line. */
data class DetectedLine(val start: NormalizedPoint, val end: NormalizedPoint, val strength: Float) {
    /** Angle in degrees, 0 = horizontal, positive = clockwise on screen (y down). */
    val angleDegrees: Float
        get() = Math.toDegrees(kotlin.math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())).toFloat()
    val length: Float get() = start.distanceTo(end)
    val isRoughlyHorizontal: Boolean get() = abs(((angleDegrees % 180f) + 180f) % 180f).let { it < 20f || it > 160f }
    val isRoughlyVertical: Boolean get() = abs(((angleDegrees % 180f) + 180f) % 180f).let { it in 70f..110f }
}

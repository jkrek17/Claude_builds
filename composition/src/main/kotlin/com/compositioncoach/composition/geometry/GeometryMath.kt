package com.compositioncoach.composition.geometry

import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect

/** Small numeric/geometry helpers shared by analyzers and the engine. */

/** Clamp to the normalized 0..1 range used throughout the frame coordinate system. */
fun Float.clampUnit(): Float = coerceIn(0f, 1f)

fun NormalizedPoint.clampToFrame(): NormalizedPoint = NormalizedPoint(x.clampUnit(), y.clampUnit())

/** Linear interpolation between two floats, `t` in 0..1. */
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Linear interpolation between two points. */
fun lerp(a: NormalizedPoint, b: NormalizedPoint, t: Float): NormalizedPoint =
    NormalizedPoint(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

/** How far [p] is from the nearest vertical frame edge (0 = touching left or right edge). */
fun distanceToNearestVerticalEdge(p: NormalizedPoint): Float = minOf(p.x, 1f - p.x)

/** How far [p] is from the nearest horizontal frame edge (0 = touching top or bottom edge). */
fun distanceToNearestHorizontalEdge(p: NormalizedPoint): Float = minOf(p.y, 1f - p.y)

/** How far [p] is from the nearest of the four frame edges. */
fun distanceToNearestEdge(p: NormalizedPoint): Float =
    minOf(p.x, 1f - p.x, p.y, 1f - p.y)

/** True when any edge of [rect] lies within [margin] of the matching frame edge (or outside the frame). */
fun NormalizedRect.touchesFrameEdge(margin: Float): Boolean =
    left <= margin || top <= margin || right >= 1f - margin || bottom >= 1f - margin

/** True when [rect] extends past the frame bounds on any side (partially out of frame). */
fun NormalizedRect.exceedsFrame(): Boolean = left < 0f || top < 0f || right > 1f || bottom > 1f

/** A rect's distance (in normalized units) from the frame centre, useful as a proxy for "centrality". */
fun NormalizedRect.centralityDistance(): Float = center.distanceTo(NormalizedPoint.CENTER)

package com.compositioncoach.composition.model

import kotlin.math.abs

/** The composition concepts the engine scores. Each analyzer owns exactly one category. */
enum class MetricCategory {
    SUBJECT_PLACEMENT,
    HORIZON,
    HEADROOM,
    CROPPING,
    LOOKING_ROOM,
    EDGE_TENSION,
    BACKGROUND_DISTRACTION,
    SUBJECT_SEPARATION,
    BALANCE,
    SYMMETRY,
    NEGATIVE_SPACE,
    LEADING_LINES,
    SCENE_SPECIFIC,
}

enum class Severity { NONE, LOW, MEDIUM, HIGH }

enum class Priority { LOW, MEDIUM, HIGH }

/** The physical action we ask the photographer to take. */
enum class Direction {
    LEFT, RIGHT, UP, DOWN,
    CLOSER, BACK,
    ROTATE_CLOCKWISE, ROTATE_COUNTER_CLOCKWISE,
    NONE,
}

/**
 * How the *camera* should move, as a fraction of frame size.
 *
 *  - dx > 0: pan/move the camera to the RIGHT (content shifts left on screen).
 *  - dy > 0: raise the camera / tilt UP (content shifts down on screen).
 *  - zoom > 0: move closer / tighten; zoom < 0: step back / go wider.
 *  - rollDegrees > 0: rotate the phone clockwise.
 *
 * Note the sign convention: to move a subject from screen x=0.8 to x=0.66 the camera pans right by +0.14.
 */
data class ReframeVector(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val zoom: Float = 0f,
    val rollDegrees: Float = 0f,
) {
    val magnitude: Float get() = kotlin.math.sqrt(dx * dx + dy * dy)

    /** The single most significant direction, using [deadZone] as the "close enough" threshold. */
    fun primaryDirection(deadZone: Float = 0.02f, rollDeadZoneDegrees: Float = 1.5f): Direction {
        if (abs(rollDegrees) > rollDeadZoneDegrees && abs(rollDegrees) / 10f > maxOf(abs(dx), abs(dy), abs(zoom))) {
            return if (rollDegrees > 0) Direction.ROTATE_CLOCKWISE else Direction.ROTATE_COUNTER_CLOCKWISE
        }
        val ax = abs(dx); val ay = abs(dy); val az = abs(zoom)
        return when {
            ax < deadZone && ay < deadZone && az < deadZone -> Direction.NONE
            az >= ax && az >= ay -> if (zoom > 0) Direction.CLOSER else Direction.BACK
            ax >= ay -> if (dx > 0) Direction.RIGHT else Direction.LEFT
            else -> if (dy > 0) Direction.UP else Direction.DOWN
        }
    }

    operator fun plus(o: ReframeVector) = ReframeVector(dx + o.dx, dy + o.dy, zoom + o.zoom, rollDegrees + o.rollDegrees)
    operator fun times(s: Float) = ReframeVector(dx * s, dy * s, zoom * s, rollDegrees * s)

    companion object {
        val ZERO = ReframeVector()
        /** Vector that moves a subject currently at [from] so that it lands on [to]. */
        fun toMoveSubject(from: NormalizedPoint, to: NormalizedPoint) = ReframeVector(dx = from.x - to.x, dy = from.y - to.y)
    }
}

/** Optional geometry an analyzer wants drawn on the preview (only in debug mode unless it is a target/arrow). */
sealed class OverlayGeometry {
    data class TargetPoint(val point: NormalizedPoint, val label: String? = null) : OverlayGeometry()
    data class Region(val rect: NormalizedRect, val label: String? = null, val isProblem: Boolean = false) : OverlayGeometry()
    data class Line(val start: NormalizedPoint, val end: NormalizedPoint, val label: String? = null) : OverlayGeometry()
    data class Arrow(val from: NormalizedPoint, val to: NormalizedPoint) : OverlayGeometry()
}

/**
 * One actionable piece of advice. [instruction] is what the user reads ("Move slightly right");
 * [reason] is the photographic why, shown in COACH mode and on the review screen.
 *
 * @param id stable identifier (e.g. "headroom.excessive") used by the smoother to recognise "the same advice".
 * @param expectedImprovement estimated gain in overall score (0..100 points) if the user follows it, when estimable.
 */
data class Recommendation(
    val id: String,
    val category: MetricCategory,
    val priority: Priority,
    val confidence: Float,
    val severity: Severity,
    val title: String,
    val instruction: String,
    val reason: String? = null,
    val direction: Direction = Direction.NONE,
    val vector: ReframeVector? = null,
    val region: NormalizedRect? = null,
    val expectedImprovement: Float? = null,
)

/**
 * The output of one analyzer for one frame.
 *
 * @param score 0..1 quality for this category (1 = ideal).
 * @param confidence 0..1 how much the analyzer trusts its own judgement (low = down-weighted / hidden).
 * @param applicable false when the concept does not apply to this frame (e.g. headroom with no face);
 *   inapplicable metrics are excluded from the weighted score rather than counted as 0.
 */
data class CompositionMetric(
    val category: MetricCategory,
    val analyzerName: String,
    val score: Float,
    val confidence: Float,
    val severity: Severity = Severity.NONE,
    val issue: String? = null,
    val recommendation: Recommendation? = null,
    val geometry: List<OverlayGeometry> = emptyList(),
    val applicable: Boolean = true,
    /** Short positive statement when this category is good ("Level horizon"), used by the review screen. */
    val strength: String? = null,
)

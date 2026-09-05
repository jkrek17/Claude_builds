package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.Severity
import kotlin.math.abs
import kotlin.math.tan

/**
 * Checks whether the horizon/camera is level. A tilted horizon is one of the most reliably-noticed
 * composition faults, and it is cheap and unambiguous to detect and fix, so this analyzer runs first.
 *
 * Signal source: the device's own attitude sensor ([FrameAnalysis.orientation]) is preferred whenever it
 * reports [DeviceOrientation.isReliable] — it is not fooled by scenes with no visible horizon line. When
 * it is unavailable (or unreliable, e.g. the phone is pointed near-vertically), this falls back to the
 * vision layer's own estimate ([ImageStatistics.estimatedHorizonAngleDegrees]), at lower confidence since
 * that estimate depends on a horizon actually being visible and correctly identified.
 *
 * Thresholds: within [DEAD_ZONE_DEGREES] (~1.5°) is treated as level (no advice — human vestibular sense
 * is not that precise and micro-corrections would be annoying). Severity ramps from LOW at the dead zone
 * up to HIGH at [HIGH_SEVERITY_DEGREES] (~6°), which reads as an obviously crooked photo.
 *
 * Sign convention (see [DeviceOrientation.rollDegrees] and [ReframeVector]): positive measured roll means
 * the horizon appears rotated *clockwise*, which the photographer corrects by rotating the phone
 * *counter-clockwise* — so the correction vector's `rollDegrees` is the negation of the measured tilt.
 */
class HorizonAnalyzer : CompositionAnalyzer {
    override val name: String = "HorizonAnalyzer"
    override val category: MetricCategory = MetricCategory.HORIZON

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val reliableOrientation = context.frame.orientation?.takeIf { it.isReliable }
        val measuredRoll: Float
        val confidence: Float
        when {
            reliableOrientation != null -> {
                measuredRoll = reliableOrientation.rollDegrees
                confidence = 0.95f
            }
            context.frame.stats?.estimatedHorizonAngleDegrees != null -> {
                measuredRoll = context.frame.stats.estimatedHorizonAngleDegrees
                confidence = 0.6f
            }
            else -> return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)
        }

        val absRoll = abs(measuredRoll)
        val score = (1f - (absRoll / SCORE_ZERO_AT_DEGREES).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val severity = when {
            absRoll <= DEAD_ZONE_DEGREES -> Severity.NONE
            absRoll <= LOW_SEVERITY_DEGREES -> Severity.LOW
            absRoll <= HIGH_SEVERITY_DEGREES -> Severity.MEDIUM
            else -> Severity.HIGH
        }

        val line = horizonLine(measuredRoll)
        val recommendation = if (absRoll <= DEAD_ZONE_DEGREES) {
            null
        } else {
            val vector = ReframeVector(rollDegrees = -measuredRoll)
            val direction = vector.primaryDirection(rollDeadZoneDegrees = DEAD_ZONE_DEGREES)
            Recommendation(
                id = "horizon.tilt",
                category = category,
                priority = if (severity == Severity.HIGH) Priority.HIGH else Priority.MEDIUM,
                confidence = confidence,
                severity = severity,
                title = "Straighten the horizon",
                instruction = InstructionText.forDirection(direction),
                reason = "Horizon is tilted about ${absRoll.toInt()}°.",
                direction = direction,
                vector = vector,
                expectedImprovement = null,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = confidence,
            severity = severity,
            issue = if (severity != Severity.NONE) "Horizon tilted ${absRoll.toInt()}°" else null,
            recommendation = recommendation,
            geometry = listOf(line),
            strength = if (score >= 0.92f) "Level horizon" else null,
        )
    }

    private fun horizonLine(rollDegrees: Float): OverlayGeometry.Line {
        val halfRise = (tan(Math.toRadians(rollDegrees.toDouble())) * 0.5).toFloat()
        return OverlayGeometry.Line(
            start = NormalizedPoint(0f, 0.5f - halfRise),
            end = NormalizedPoint(1f, 0.5f + halfRise),
            label = "horizon",
        )
    }

    companion object {
        const val DEAD_ZONE_DEGREES = 1.5f
        const val LOW_SEVERITY_DEGREES = 3f
        const val HIGH_SEVERITY_DEGREES = 6f
        const val SCORE_ZERO_AT_DEGREES = 15f
    }
}

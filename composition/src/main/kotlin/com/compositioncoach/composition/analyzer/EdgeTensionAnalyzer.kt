package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.geometry.exceedsFrame
import com.compositioncoach.composition.geometry.touchesFrameEdge
import com.compositioncoach.composition.model.BodyLandmarkType
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import kotlin.math.abs

/**
 * Flags a subject crowding, or partially falling outside, a frame edge — "edge tension" — which reads as
 * an accidental crop rather than a deliberate tight one. Checks the primary subject's box plus any
 * extremity landmarks (wrists, ankles) that the pose detector is confident are actually in frame
 * ([BodyLandmark.inFrameLikelihood] >= [LANDMARK_CONFIDENCE_THRESHOLD]) — a hand or foot can poke past an
 * edge well before the subject's overall bounding box does.
 *
 * Skipped for a close-up portrait with a roughly left/right-symmetric crop: a tight face crop
 * legitimately touches the top/side edges by design (see [HeadroomAnalyzer] for the top edge specifically).
 *
 * Fix direction: perhaps counter-intuitively, the correction is to pan/tilt the camera *toward* the edge
 * being touched. Per [ReframeVector]'s sign convention, panning right shifts frame content left, so
 * "camera right" moves a subject away from the *right* edge and back toward centre — i.e. the camera
 * moves toward the edge, and the subject moves away from it.
 *
 * **Group portraits are special**: the single "primary subject" box below only ever represents one person,
 * but a group photo is ruined just as badly by cropping *any* one of them. So for [SceneType.GROUP_PORTRAIT]
 * (whether auto-detected or declared) this checks every face in frame, not only the primary one, and treats
 * a partially cut face as [Severity.HIGH] outright — there is no "mild edge tension" in a group photo, only
 * "someone got cut off" or not.
 */
class EdgeTensionAnalyzer : CompositionAnalyzer {
    override val name: String = "EdgeTensionAnalyzer"
    override val category: MetricCategory = MetricCategory.EDGE_TENSION

    override fun analyze(context: AnalysisContext): CompositionMetric {
        if (context.scene.type == SceneType.GROUP_PORTRAIT) {
            groupCutOffMetric(context)?.let { return it }
        }

        val subject = context.primarySubject
            ?: return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

        val bounds = subject.bounds
        val symmetricTightCrop = context.scene.isCloseUpPortrait &&
            abs(bounds.distanceToLeftEdge - bounds.distanceToRightEdge) < SYMMETRIC_CROP_TOLERANCE
        if (symmetricTightCrop) {
            return CompositionMetric(category, name, score = 1f, confidence = 0.5f, applicable = true, strength = "Intentional tight crop")
        }

        val distances = linkedMapOf(
            Direction.LEFT to bounds.distanceToLeftEdge,
            Direction.RIGHT to bounds.distanceToRightEdge,
            Direction.UP to bounds.distanceToTopEdge,
            Direction.DOWN to bounds.distanceToBottomEdge,
        )
        subject.body?.let { body ->
            for (type in EXTREMITY_LANDMARKS) {
                val lm = body.visible(type, LANDMARK_CONFIDENCE_THRESHOLD) ?: continue
                distances[Direction.LEFT] = minOf(distances.getValue(Direction.LEFT), lm.position.x)
                distances[Direction.RIGHT] = minOf(distances.getValue(Direction.RIGHT), 1f - lm.position.x)
                distances[Direction.UP] = minOf(distances.getValue(Direction.UP), lm.position.y)
                distances[Direction.DOWN] = minOf(distances.getValue(Direction.DOWN), 1f - lm.position.y)
            }
        }

        val (worstDirection, worstDistance) = distances.minBy { it.value }.toPair()
        val exceedsFrame = bounds.exceedsFrame()
        val score = if (exceedsFrame) 0f else (worstDistance / EDGE_MARGIN).coerceIn(0f, 1f)
        val touching = exceedsFrame || worstDistance <= EDGE_MARGIN

        val recommendation = if (!touching) {
            null
        } else {
            val vector = when (worstDirection) {
                Direction.LEFT -> ReframeVector(dx = -PAN_MAGNITUDE)
                Direction.RIGHT -> ReframeVector(dx = PAN_MAGNITUDE)
                Direction.UP -> ReframeVector(dy = PAN_MAGNITUDE)
                else -> ReframeVector(dy = -PAN_MAGNITUDE)
            }
            Recommendation(
                id = "edge.tension",
                category = category,
                priority = if (exceedsFrame) Priority.HIGH else Priority.MEDIUM,
                confidence = 0.8f,
                severity = if (exceedsFrame) Severity.HIGH else Severity.MEDIUM,
                title = "Subject is too close to frame edge",
                instruction = InstructionText.forDirection(worstDirection),
                reason = "Part of the subject is crowding or leaving the frame.",
                direction = worstDirection,
                vector = vector,
                region = bounds,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.85f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (touching) "Subject crowds the frame edge" else null,
            recommendation = recommendation,
            geometry = listOf(OverlayGeometry.Region(bounds, isProblem = touching)),
            strength = if (score >= 0.9f) "Comfortable margin from the edges" else null,
        )
    }

    /** The first face (if any) touching or past a frame edge, i.e. one person the group shot is about to lose. */
    private fun groupCutOffMetric(context: AnalysisContext): CompositionMetric? {
        val cutFace = context.frame.faces.firstOrNull { it.bounds.exceedsFrame() || it.bounds.touchesFrameEdge(EDGE_MARGIN) }
            ?: return null

        val recommendation = Recommendation(
            id = "edge.tension.group_cutoff",
            category = category,
            priority = Priority.HIGH,
            confidence = 0.85f,
            severity = Severity.HIGH,
            title = "Someone is cut off",
            instruction = "Someone is cut off — step back",
            reason = "A face in the group is crowding or leaving the frame edge.",
            direction = Direction.BACK,
            vector = ReframeVector(zoom = -GROUP_STEP_BACK_ZOOM),
            region = cutFace.bounds,
        )
        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = 0f,
            confidence = 0.85f,
            severity = Severity.HIGH,
            issue = "Someone is cut off at the frame edge",
            recommendation = recommendation,
            geometry = listOf(OverlayGeometry.Region(cutFace.bounds, isProblem = true)),
        )
    }

    companion object {
        const val EDGE_MARGIN = 0.04f
        const val SYMMETRIC_CROP_TOLERANCE = 0.03f
        const val LANDMARK_CONFIDENCE_THRESHOLD = 0.5f
        const val PAN_MAGNITUDE = 0.08f
        /** How far to (virtually) step back when someone in a group is cut off at the edge. */
        const val GROUP_STEP_BACK_ZOOM = 0.15f
        val EXTREMITY_LANDMARKS = listOf(
            BodyLandmarkType.LEFT_WRIST, BodyLandmarkType.RIGHT_WRIST,
            BodyLandmarkType.LEFT_ANKLE, BodyLandmarkType.RIGHT_ANKLE,
            BodyLandmarkType.LEFT_FOOT_INDEX, BodyLandmarkType.RIGHT_FOOT_INDEX,
        )
    }
}

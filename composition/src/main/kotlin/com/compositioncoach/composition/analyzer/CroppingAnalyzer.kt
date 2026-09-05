package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.geometry.distanceToNearestEdge
import com.compositioncoach.composition.model.BodyLandmark
import com.compositioncoach.composition.model.BodyLandmarkType
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.Severity

/**
 * Full-body/partial-body shots only: are limbs cropped at a *joint* rather than mid-limb?
 *
 * The classic rule photographers are taught: cropping exactly at an ankle, knee, wrist, elbow or hip
 * reads as an accident, because the eye reads the truncation as "the leg/arm just... stops". Cropping
 * cleanly *between* joints (mid-thigh, mid-shin, mid-forearm) reads as an intentional framing choice.
 * This only fires with an actual body detection — a face-only frame has no limbs to crop.
 *
 * A joint is treated as "cut" when it sits within [EDGE_THRESHOLD] (~3%) of any frame edge while the
 * pose detector is still reasonably confident it's in frame ([BodyLandmark.inFrameLikelihood]) — a joint
 * the detector is confident is *not* in frame at all is a normal, wider crop, not a hairline one.
 *
 * Advice differs by which joint is at risk: a cropped ankle/foot is fixed by lowering the camera a touch
 * to bring the feet in; a cropped knee or hip is fixed by moving closer so the crop line lands cleanly
 * above the knee instead (a flattering, deliberate half-body framing); a cropped wrist/elbow, or a cropped
 * top of the head on a non-close-up shot, is fixed by stepping back for more headroom all around.
 */
class CroppingAnalyzer : CompositionAnalyzer {
    override val name: String = "CroppingAnalyzer"
    override val category: MetricCategory = MetricCategory.CROPPING

    private data class Violation(val id: String, val edgeDistance: Float, val severity: Severity, val instruction: String, val direction: Direction, val vector: ReframeVector, val reason: String)

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val subject = context.primarySubject
        val body = subject?.body
            ?: return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

        val violations = mutableListOf<Violation>()

        fun check(types: List<BodyLandmarkType>, id: String, instruction: String, direction: Direction, vector: ReframeVector, reason: String) {
            for (type in types) {
                val lm = body.landmark(type) ?: continue
                if (lm.inFrameLikelihood < MIN_LIKELIHOOD) continue
                val edgeDistance = distanceToNearestEdge(lm.position)
                if (edgeDistance <= EDGE_THRESHOLD) {
                    violations += Violation(id, edgeDistance, Severity.HIGH, instruction, direction, vector, reason)
                }
            }
        }

        check(
            listOf(BodyLandmarkType.LEFT_ANKLE, BodyLandmarkType.RIGHT_ANKLE, BodyLandmarkType.LEFT_FOOT_INDEX, BodyLandmarkType.RIGHT_FOOT_INDEX, BodyLandmarkType.LEFT_HEEL, BodyLandmarkType.RIGHT_HEEL),
            "cropping.feet", "Lower camera slightly to include feet", Direction.DOWN, ReframeVector(dy = -LOWER_MAGNITUDE),
            "The feet are cropped right at the ankle.",
        )
        check(
            listOf(BodyLandmarkType.LEFT_KNEE, BodyLandmarkType.RIGHT_KNEE, BodyLandmarkType.LEFT_HIP, BodyLandmarkType.RIGHT_HIP),
            "cropping.kneehip", "Move closer to crop above the knees", Direction.CLOSER, ReframeVector(zoom = CLOSER_MAGNITUDE),
            "The frame cuts right at a knee or hip, which reads as accidental.",
        )
        check(
            listOf(BodyLandmarkType.LEFT_WRIST, BodyLandmarkType.RIGHT_WRIST, BodyLandmarkType.LEFT_ELBOW, BodyLandmarkType.RIGHT_ELBOW),
            "cropping.arm", "Step back slightly", Direction.BACK, ReframeVector(zoom = -STEP_BACK_MAGNITUDE),
            "An arm is cropped at the wrist or elbow.",
        )

        val face = subject.face
        if (face != null && !context.scene.isCloseUpPortrait && face.estimatedHeadTop <= EDGE_THRESHOLD) {
            violations += Violation(
                "cropping.headtop", face.estimatedHeadTop.coerceAtLeast(0f), Severity.MEDIUM,
                "Step back slightly", Direction.BACK, ReframeVector(zoom = -STEP_BACK_MAGNITUDE),
                "The top of the head is cropped.",
            )
        }

        val worst = violations.minByOrNull { it.edgeDistance }
        val score = if (worst == null) 1f else (worst.edgeDistance / EDGE_THRESHOLD).coerceIn(0f, 1f)

        val recommendation = worst?.let {
            Recommendation(
                id = it.id,
                category = category,
                priority = if (it.severity == Severity.HIGH) Priority.HIGH else Priority.MEDIUM,
                confidence = 0.8f,
                severity = it.severity,
                title = "Awkward crop point",
                instruction = it.instruction,
                reason = it.reason,
                direction = it.direction,
                vector = it.vector,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.8f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = worst?.reason,
            recommendation = recommendation,
            strength = if (worst == null) "Clean crop, no joints cut" else null,
        )
    }

    companion object {
        const val EDGE_THRESHOLD = 0.03f
        const val MIN_LIKELIHOOD = 0.5f
        const val LOWER_MAGNITUDE = 0.1f
        const val CLOSER_MAGNITUDE = 0.15f
        const val STEP_BACK_MAGNITUDE = 0.15f
    }
}

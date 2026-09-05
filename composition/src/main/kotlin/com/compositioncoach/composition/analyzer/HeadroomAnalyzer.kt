package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.engine.SceneClassifier
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.Severity

/**
 * Portrait-only: is there a sensible amount of empty space above the subject's head?
 *
 * [DetectedFace.estimatedHeadTop] is already a y-coordinate, i.e. exactly the fraction of frame height
 * between the frame's top edge and the top of the head — that value *is* the headroom.
 *
 * The ideal band narrows as the face gets bigger (a tight close-up needs almost no headroom, a
 * medium/full shot wants a bit more so the head doesn't feel glued to the frame edge) and is skipped
 * entirely on the tight side for [SceneClassification.isCloseUpPortrait]-style close-ups: a deliberately
 * tight crop with the head touching or slightly exceeding the top edge is a valid, common framing choice,
 * not a mistake.
 *
 *  - **Tight** (head touching/cut, and the face isn't already a close-up) -> "Raise camera slightly":
 *    raising the camera pushes the whole scene down in frame, opening space above the head.
 *  - **Excessive** (> [EXCESSIVE_HEADROOM]) -> lower the camera if there is room below the subject to do
 *    so without cropping their body; otherwise recommend moving closer instead, since lowering would
 *    just crop the feet.
 */
class HeadroomAnalyzer : CompositionAnalyzer {
    override val name: String = "HeadroomAnalyzer"
    override val category: MetricCategory = MetricCategory.HEADROOM

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val subject = context.primarySubject
        val face = subject?.face
            ?: return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

        val headroom = face.estimatedHeadTop
        val faceHeight = face.bounds.height
        val closeUp = faceHeight > SceneClassifier.CLOSE_UP_FACE_HEIGHT

        val idealMin = (IDEAL_MIN_BASE - faceHeight * FACE_SIZE_SHRINK).coerceIn(0f, IDEAL_MIN_BASE)
        val idealMax = (IDEAL_MAX_BASE - faceHeight * FACE_SIZE_SHRINK * 2f).coerceAtLeast(idealMin + 0.05f)

        val below = (idealMin - headroom).coerceAtLeast(0f)
        val above = (headroom - idealMax).coerceAtLeast(0f)
        val deficiencyPenalty = if (closeUp) 0f else (below / DEFICIENCY_SCALE).coerceIn(0f, 1f)
        val excessPenalty = (above / EXCESS_SCALE).coerceIn(0f, 1f)
        val score = (1f - maxOf(deficiencyPenalty, excessPenalty)).coerceIn(0f, 1f)

        val tight = !closeUp && headroom <= TIGHT_HEADROOM
        val excessive = headroom > EXCESSIVE_HEADROOM

        val recommendation = when {
            tight -> Recommendation(
                id = "headroom.tight",
                category = category,
                priority = Priority.MEDIUM,
                confidence = 0.85f,
                severity = if (headroom < 0f) Severity.HIGH else Severity.MEDIUM,
                title = "Adjust headroom",
                instruction = "Raise camera slightly",
                reason = "The top of the head is cut off or touching the frame edge.",
                direction = Direction.UP,
                vector = ReframeVector(dy = RAISE_VECTOR_MAGNITUDE),
            )
            excessive -> {
                val bottomRoom = 1f - subject.bounds.bottom
                val canLower = bottomRoom > BOTTOM_ROOM_FOR_LOWER
                Recommendation(
                    id = "headroom.excessive",
                    category = category,
                    priority = Priority.LOW,
                    confidence = 0.75f,
                    severity = if (above > HIGH_EXCESS_MARGIN) Severity.MEDIUM else Severity.LOW,
                    title = "Adjust headroom",
                    instruction = if (canLower) "Lower camera slightly" else "Move closer",
                    reason = "There is a lot of empty space above the subject's head.",
                    direction = if (canLower) Direction.DOWN else Direction.CLOSER,
                    vector = if (canLower) ReframeVector(dy = -LOWER_VECTOR_MAGNITUDE) else ReframeVector(zoom = CLOSER_VECTOR_MAGNITUDE),
                )
            }
            else -> null
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.85f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = when {
                tight -> "Head too close to the top of the frame"
                excessive -> "Too much empty space above the head"
                else -> null
            },
            recommendation = recommendation,
            strength = if (score >= 0.85f) "Comfortable headroom" else null,
        )
    }

    companion object {
        const val IDEAL_MIN_BASE = 0.05f
        const val IDEAL_MAX_BASE = 0.15f
        const val FACE_SIZE_SHRINK = 0.15f
        const val TIGHT_HEADROOM = 0.02f
        const val EXCESSIVE_HEADROOM = 0.25f
        const val HIGH_EXCESS_MARGIN = 0.15f
        const val DEFICIENCY_SCALE = 0.08f
        const val EXCESS_SCALE = 0.25f
        const val BOTTOM_ROOM_FOR_LOWER = 0.1f
        const val RAISE_VECTOR_MAGNITUDE = 0.1f
        const val LOWER_VECTOR_MAGNITUDE = 0.1f
        const val CLOSER_VECTOR_MAGNITUDE = 0.15f
    }
}

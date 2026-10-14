package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.geometry.RuleOfThirds
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectKind
import kotlin.math.abs

/**
 * Where should the primary subject sit in the frame? This is the single highest-weighted metric for
 * most scenes because it is the composition rule photographers hear about most: the rule of thirds.
 *
 * Two valid targets are recognised, and the second must NOT be pushed toward the first:
 *  1. **Centred**: valid when the scene has strong mirror symmetry ([SceneClassification.isSymmetricScene])
 *     — a centred subject reinforces symmetry rather than fighting it — or when the subject is already
 *     within [CENTERED_TOLERANCE] (~5%) of the horizontal centre *and* this isn't a portrait with a
 *     sideways gaze (a centred face that's visibly looking off to one side still reads as unbalanced, so
 *     that combination falls through to the thirds rule instead). For a [SubjectKind.OBJECT] subject there
 *     is no gaze to consult, so centred is instead valid when the object is large enough to anchor the
 *     frame on its own ([LARGE_OBJECT_AREA], ~25% of frame area) — a small object sitting dead centre still
 *     reads as an accident, but a large one (the pint glass filling a third of the shot) earns it.
 *  2. **Rule of thirds**: the anchor point should land on the nearest of the four thirds intersections,
 *     with two refinements: for portraits the eye-line prefers the *upper* horizontal third (roughly
 *     where a viewer's gaze naturally lands first), and the horizontal third is chosen to leave "looking
 *     room" in the direction the subject is facing (see [RuleOfThirds.preferredIntersection]) rather than
 *     always snapping to the nearer line. An object subject has no gaze, so it always snaps to the nearer
 *     thirds intersection on both axes.
 *
 * The score decays linearly with distance from the chosen target (capped at [MAX_MEANINGFUL_DISTANCE]);
 * a [Recommendation] is only produced once the distance exceeds [PLACEMENT_DEAD_ZONE] so that "close
 * enough" framing isn't nagged at.
 */
class SubjectPlacementAnalyzer : CompositionAnalyzer {
    override val name: String = "SubjectPlacementAnalyzer"
    override val category: MetricCategory = MetricCategory.SUBJECT_PLACEMENT

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val subject = context.primarySubject
            ?: return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

        val anchor = subject.anchorPoint
        val gaze = subject.face?.gaze ?: GazeDirection.UNKNOWN
        val portraitWithGaze = context.scene.type == SceneType.PORTRAIT && (gaze == GazeDirection.LEFT || gaze == GazeDirection.RIGHT)
        val nearCenterX = abs(anchor.x - 0.5f) <= CENTERED_TOLERANCE
        val centeredIsValidTarget = if (subject.kind == SubjectKind.OBJECT) {
            context.scene.isSymmetricScene || subject.bounds.area >= LARGE_OBJECT_AREA
        } else {
            context.scene.isSymmetricScene || (nearCenterX && !portraitWithGaze)
        }

        val target = if (centeredIsValidTarget) {
            NormalizedPoint(0.5f, anchor.y)
        } else {
            val yTarget = if (context.scene.type == SceneType.PORTRAIT) RuleOfThirds.LOWER_THIRD else RuleOfThirds.nearestHorizontalThird(anchor.y)
            val roomOnSide = when (gaze) {
                GazeDirection.LEFT -> -1f
                GazeDirection.RIGHT -> 1f
                else -> 0f
            }
            NormalizedPoint(RuleOfThirds.preferredIntersection(anchor, roomOnSide).x, yTarget)
        }

        val distance = anchor.distanceTo(target)
        val score = (1f - (distance / MAX_MEANINGFUL_DISTANCE).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        val recommendation = if (distance <= PLACEMENT_DEAD_ZONE) {
            null
        } else {
            val vector = ReframeVector.toMoveSubject(anchor, target)
            val direction = vector.primaryDirection()
            Recommendation(
                id = if (centeredIsValidTarget) "placement.center" else "placement.thirds",
                category = category,
                priority = if (score < 0.5f) Priority.HIGH else Priority.MEDIUM,
                confidence = 0.85f,
                severity = if (score < 0.4f) Severity.MEDIUM else Severity.LOW,
                title = if (centeredIsValidTarget) "Try centering the subject" else "Improve subject placement",
                instruction = InstructionText.forDirection(direction),
                reason = if (centeredIsValidTarget) {
                    "This composition reads best centred."
                } else {
                    "Placing the subject on a thirds line is generally more pleasing than dead centre."
                },
                direction = direction,
                vector = vector,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.9f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (recommendation != null) {
                if (centeredIsValidTarget) "Subject sits off the frame's centre" else "Subject doesn't land on a thirds point"
            } else {
                null
            },
            recommendation = recommendation,
            geometry = listOf(OverlayGeometry.TargetPoint(target, "target"), OverlayGeometry.Arrow(anchor, target)),
            // Gated on `recommendation == null`, not score alone: PLACEMENT_DEAD_ZONE (0.05) and this
            // strength band (score >= 0.85, i.e. distance <= 0.075) used to overlap, so a subject
            // 0.05-0.075 away from its target could carry both an "issue" string (from the still-active
            // recommendation) and a contradictory "strength" string on the very same metric.
            strength = if (recommendation == null && score >= 0.85f) {
                if (centeredIsValidTarget) "Subject is well-centred" else "Subject sits on a strong thirds point"
            } else {
                null
            },
        )
    }

    companion object {
        const val CENTERED_TOLERANCE = 0.05f
        const val PLACEMENT_DEAD_ZONE = 0.05f
        const val MAX_MEANINGFUL_DISTANCE = 0.5f

        /** An object subject this large (~25% of frame area) can anchor a centred composition on its own. */
        const val LARGE_OBJECT_AREA = 0.25f
    }
}

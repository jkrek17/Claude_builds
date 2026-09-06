package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import kotlin.math.abs

/**
 * "Looking room" / "nose room": when a subject is looking or facing to one side, viewers expect some
 * empty space in front of their gaze; without it the frame feels like the subject is about to walk (or
 * look) straight out of it.
 *
 * Only applies to a single clear subject with a sideways gaze — with two or more faces in frame there is
 * no single "front of subject" to leave room for, so group scenes are skipped entirely.
 *
 * Gaze comes from [DetectedFace.gaze] when it reports LEFT/RIGHT; otherwise head yaw
 * ([DetectedFace.headEulerY]) beyond [YAW_THRESHOLD_DEGREES] is used as a proxy (a face turned well to
 * one side is looking that way even if eye-gaze wasn't separately classified).
 *
 * The room ratio compares the empty space between the face and the frame edge on the gaze side to the
 * face's own width — less than about one face-width of room, combined with the face already sitting in
 * the outer 35% of the frame on that side, reads as cramped. The fix is to pan the camera *toward* the
 * gaze side: panning right shifts the whole frame's content left (see [ReframeVector]), opening space in
 * front of a subject looking/facing right, and moving them off the edge they were crowding.
 */
class LookingRoomAnalyzer : CompositionAnalyzer {
    override val name: String = "LookingRoomAnalyzer"
    override val category: MetricCategory = MetricCategory.LOOKING_ROOM

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val faceCount = context.frame.faces.size
        if (faceCount != 1 || context.scene.type == SceneType.GROUP_PORTRAIT) {
            return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)
        }
        val face = context.primarySubject?.face
            ?: return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

        val effectiveGaze = when {
            face.gaze == GazeDirection.LEFT || face.gaze == GazeDirection.RIGHT -> face.gaze
            face.headEulerY != null && abs(face.headEulerY) > YAW_THRESHOLD_DEGREES ->
                if (face.headEulerY > 0f) GazeDirection.RIGHT else GazeDirection.LEFT
            else -> GazeDirection.UNKNOWN
        }
        if (effectiveGaze != GazeDirection.LEFT && effectiveGaze != GazeDirection.RIGHT) {
            return CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)
        }

        val faceWidth = face.bounds.width.coerceAtLeast(0.01f)
        val space = if (effectiveGaze == GazeDirection.RIGHT) 1f - face.bounds.right else face.bounds.left
        val roomRatio = space / faceWidth
        val inOuterBand = if (effectiveGaze == GazeDirection.RIGHT) {
            face.bounds.right >= 1f - OUTER_BAND
        } else {
            face.bounds.left <= OUTER_BAND
        }

        val score = (roomRatio / IDEAL_ROOM_RATIO).coerceIn(0f, 1f)
        val cramped = roomRatio < IDEAL_ROOM_RATIO && inOuterBand

        val recommendation = if (!cramped) {
            null
        } else {
            val direction = if (effectiveGaze == GazeDirection.RIGHT) Direction.RIGHT else Direction.LEFT
            val vector = ReframeVector(dx = if (direction == Direction.RIGHT) PAN_MAGNITUDE else -PAN_MAGNITUDE)
            Recommendation(
                id = "lookingroom.insufficient",
                category = category,
                priority = Priority.MEDIUM,
                confidence = 0.7f,
                severity = if (roomRatio < IDEAL_ROOM_RATIO * 0.4f) Severity.MEDIUM else Severity.LOW,
                title = "Leave more room in front of subject",
                instruction = InstructionText.forDirection(direction),
                reason = "The subject is looking toward the edge of the frame with little space ahead.",
                direction = direction,
                vector = vector,
            )
        }

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = 0.75f,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = if (cramped) "Not enough looking room" else null,
            recommendation = recommendation,
            // Gated on `!cramped`, not score alone: `cramped` also requires `inOuterBand`, so a room
            // ratio just under IDEAL_ROOM_RATIO (e.g. 0.95, score 0.95) with the face in the outer band
            // used to be flagged as "cramped" (issue) while also clearing the strength threshold.
            strength = if (!cramped && score >= 0.9f) "Good looking room" else null,
        )
    }

    companion object {
        const val YAW_THRESHOLD_DEGREES = 12f
        const val OUTER_BAND = 0.35f
        const val IDEAL_ROOM_RATIO = 1.0f
        const val PAN_MAGNITUDE = 0.08f
    }
}

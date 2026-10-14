package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.SceneIntent

/**
 * Separates faces the photographer is plausibly composing around from incidental background faces.
 *
 * A face detector happily finds the person at the next table or a passer-by at the far end of a bar. Treating
 * that as the subject turns a photo of a pint into a "portrait" with headroom advice about a stranger. The
 * rule is deliberately simple: a face counts as a subject when its box is at least [minSubjectFaceHeight] of
 * the frame height (a full-body person in a portrait-orientation frame has a face around 8-10 % tall, a
 * head-and-shoulders portrait 25 % or more), or when the pose detector found a body for it, which only
 * happens for people who are reasonably large in frame. Everything smaller is background and is dropped
 * before scene classification and subject resolution so every analyzer agrees on what the subject is.
 *
 * **Declared intent relaxes the threshold.** When the photographer has explicitly picked [SceneIntent.PORTRAIT]
 * or [SceneIntent.GROUP_PORTRAIT], they have already told us there is a person to shoot — a face only needs
 * to clear [PORTRAIT_INTENT_MIN_SUBJECT_FACE_HEIGHT] (half the default) to count, so the coach can start
 * guiding the photographer toward someone who is still a fair way off instead of waiting for them to be as
 * close as the unprimed heuristic requires. Every other intent (including `AUTO`) keeps the stricter default.
 */
object SubjectFilter {
    /** Faces shorter than this fraction of frame height are treated as background unless a body backs them. */
    const val MIN_SUBJECT_FACE_HEIGHT = 0.08f

    /** Relaxed threshold used once the photographer has declared PORTRAIT/GROUP_PORTRAIT mode. */
    const val PORTRAIT_INTENT_MIN_SUBJECT_FACE_HEIGHT = 0.04f

    /** The background-face threshold to use for a given declared [intent]. */
    fun minSubjectFaceHeight(intent: SceneIntent): Float = when (intent) {
        SceneIntent.PORTRAIT, SceneIntent.GROUP_PORTRAIT -> PORTRAIT_INTENT_MIN_SUBJECT_FACE_HEIGHT
        else -> MIN_SUBJECT_FACE_HEIGHT
    }

    fun isSubjectFace(face: DetectedFace, bodies: List<DetectedBody>, minHeight: Float = MIN_SUBJECT_FACE_HEIGHT): Boolean =
        face.bounds.height >= minHeight ||
            bodies.any { it.bounds.contains(face.center) || it.bounds.intersectionOverUnion(face.bounds) >= SubjectResolver.FACE_BODY_MIN_IOU }

    /**
     * Returns a copy of [frame] with incidental background faces removed (same instance if nothing changed).
     * [intent] selects the size threshold; defaults to the stricter, mode-agnostic one.
     */
    fun dropIncidentalFaces(frame: FrameAnalysis, intent: SceneIntent = SceneIntent.AUTO): FrameAnalysis {
        if (frame.faces.isEmpty()) return frame
        val minHeight = minSubjectFaceHeight(intent)
        val kept = frame.faces.filter { isSubjectFace(it, frame.bodies, minHeight) }
        return if (kept.size == frame.faces.size) frame else frame.copy(faces = kept)
    }
}

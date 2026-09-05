package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.FrameAnalysis

/**
 * Separates faces the photographer is plausibly composing around from incidental background faces.
 *
 * A face detector happily finds the person at the next table or a passer-by at the far end of a bar. Treating
 * that as the subject turns a photo of a pint into a "portrait" with headroom advice about a stranger. The
 * rule is deliberately simple: a face counts as a subject when its box is at least [MIN_SUBJECT_FACE_HEIGHT]
 * of the frame height (a full-body person in a portrait-orientation frame has a face around 8-10 % tall, a
 * head-and-shoulders portrait 25 % or more), or when the pose detector found a body for it, which only
 * happens for people who are reasonably large in frame. Everything smaller is background and is dropped
 * before scene classification and subject resolution so every analyzer agrees on what the subject is.
 */
object SubjectFilter {
    /** Faces shorter than this fraction of frame height are treated as background unless a body backs them. */
    const val MIN_SUBJECT_FACE_HEIGHT = 0.08f

    fun isSubjectFace(face: DetectedFace, bodies: List<DetectedBody>): Boolean =
        face.bounds.height >= MIN_SUBJECT_FACE_HEIGHT ||
            bodies.any { it.bounds.contains(face.center) || it.bounds.intersectionOverUnion(face.bounds) >= SubjectResolver.FACE_BODY_MIN_IOU }

    /** Returns a copy of [frame] with incidental background faces removed (same instance if nothing changed). */
    fun dropIncidentalFaces(frame: FrameAnalysis): FrameAnalysis {
        if (frame.faces.isEmpty()) return frame
        val kept = frame.faces.filter { isSubjectFace(it, frame.bodies) }
        return if (kept.size == frame.faces.size) frame else frame.copy(faces = kept)
    }
}

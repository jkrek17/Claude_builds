package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.GroupStatus
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Severity

/**
 * Is the whole group ready to shoot? Used by [CompositionEngine] for group portraits (declared or detected)
 * to fill [com.compositioncoach.composition.model.CompositionResult.groupStatus]; the app's shoot-at-peak
 * shutter waits for `ready` in group scenes instead of `isShootReady`.
 *
 * A face is ready when it is
 *  - at least [MIN_FACE_HEIGHT] (6 %) of the frame height ("Someone's too small"),
 *  - not cut at an edge: every side of its box at least [EDGE_MARGIN] (2 %) inside the frame
 *    ("Someone's cut off"),
 *  - eyes open: [DetectedFace.eyesOpenProbability] ≥ [MIN_EYES_OPEN], or unknown ("Eyes closed").
 *
 * The group is ready when at least [IntentSubjectCoach.MIN_GROUP_FACES] faces are in frame, every face is
 * ready, and no applicable metric is at [Severity.HIGH]. A HIGH-severity edge or cropping problem is
 * reported as "Someone's cut off"; any other HIGH-severity problem adds its recommendation's instruction as
 * the blocker so the chip is never blank while the shutter waits.
 *
 * Blockers are ordered by urgency (cut off, eyes closed, too small) and never repeated.
 */
object GroupReadiness {
    const val MIN_FACE_HEIGHT = 0.06f
    const val EDGE_MARGIN = 0.02f
    const val MIN_EYES_OPEN = 0.6f

    const val BLOCKER_CUT_OFF = "Someone's cut off"
    const val BLOCKER_EYES_CLOSED = "Eyes closed"
    const val BLOCKER_TOO_SMALL = "Someone's too small"

    fun evaluate(faces: List<DetectedFace>, metrics: List<CompositionMetric>): GroupStatus {
        var cutOff = false
        var eyesClosed = false
        var tooSmall = false
        var facesReady = 0
        for (face in faces) {
            val faceCut = isCutAtEdge(face)
            val faceEyesClosed = face.eyesOpenProbability?.let { it < MIN_EYES_OPEN } ?: false
            val faceTooSmall = face.bounds.height < MIN_FACE_HEIGHT
            cutOff = cutOff || faceCut
            eyesClosed = eyesClosed || faceEyesClosed
            tooSmall = tooSmall || faceTooSmall
            if (!faceCut && !faceEyesClosed && !faceTooSmall) facesReady++
        }

        val highMetrics = metrics.filter { it.applicable && it.severity == Severity.HIGH }
        val blockers = mutableListOf<String>()
        if (cutOff || highMetrics.any { it.category == MetricCategory.EDGE_TENSION || it.category == MetricCategory.CROPPING }) {
            blockers += BLOCKER_CUT_OFF
        }
        if (eyesClosed) blockers += BLOCKER_EYES_CLOSED
        if (tooSmall) blockers += BLOCKER_TOO_SMALL
        for (metric in highMetrics) {
            if (metric.category == MetricCategory.EDGE_TENSION || metric.category == MetricCategory.CROPPING) continue
            val instruction = metric.recommendation?.instruction ?: continue
            if (instruction !in blockers) blockers += instruction
        }

        val ready = faces.size >= IntentSubjectCoach.MIN_GROUP_FACES && facesReady == faces.size && highMetrics.isEmpty()
        return GroupStatus(ready = ready, facesTotal = faces.size, facesReady = facesReady, blockers = if (ready) emptyList() else blockers)
    }

    /** True when any side of the face box is within [EDGE_MARGIN] of (or beyond) the frame edge. */
    fun isCutAtEdge(face: DetectedFace): Boolean {
        val b = face.bounds
        return b.left < EDGE_MARGIN || b.top < EDGE_MARGIN || b.right > 1f - EDGE_MARGIN || b.bottom > 1f - EDGE_MARGIN
    }
}

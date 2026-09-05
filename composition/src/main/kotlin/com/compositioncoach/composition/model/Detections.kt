package com.compositioncoach.composition.model

/** Which way a face appears to be turned/looking, in *screen* terms (LEFT = toward the left edge of the frame). */
enum class GazeDirection { LEFT, RIGHT, CENTER, UNKNOWN }

/**
 * A detected face. All geometry is in normalized upright-frame coordinates (see [NormalizedPoint]).
 *
 * @param headEulerY yaw in degrees (positive = face turned toward the screen's right), null if unknown.
 * @param headEulerZ roll in degrees (positive = head tilted clockwise on screen), null if unknown.
 */
data class DetectedFace(
    val id: Int,
    val bounds: NormalizedRect,
    val leftEye: NormalizedPoint? = null,
    val rightEye: NormalizedPoint? = null,
    val noseBase: NormalizedPoint? = null,
    val headEulerY: Float? = null,
    val headEulerZ: Float? = null,
    val gaze: GazeDirection = GazeDirection.UNKNOWN,
    val confidence: Float = 1f,
    val smilingProbability: Float? = null,
) {
    val center: NormalizedPoint get() = bounds.center

    /** Approximate eye line; falls back to ~40% down the face box when eyes are unknown. */
    val eyeLineY: Float
        get() = if (leftEye != null && rightEye != null) (leftEye.y + rightEye.y) / 2f else bounds.top + bounds.height * 0.4f

    /** The face detector's box usually stops around the eyebrows; estimate the actual top of the head. */
    val estimatedHeadTop: Float get() = (bounds.top - bounds.height * 0.25f).coerceAtLeast(-0.5f)
}

/** Body landmarks, mirroring ML Kit pose landmark types. LEFT/RIGHT are the subject's own left/right. */
enum class BodyLandmarkType {
    NOSE,
    LEFT_EYE, RIGHT_EYE,
    LEFT_EAR, RIGHT_EAR,
    LEFT_SHOULDER, RIGHT_SHOULDER,
    LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST,
    LEFT_HIP, RIGHT_HIP,
    LEFT_KNEE, RIGHT_KNEE,
    LEFT_ANKLE, RIGHT_ANKLE,
    LEFT_HEEL, RIGHT_HEEL,
    LEFT_FOOT_INDEX, RIGHT_FOOT_INDEX,
}

data class BodyLandmark(
    val type: BodyLandmarkType,
    val position: NormalizedPoint,
    /** 0..1 likelihood that this landmark is actually inside the frame (ML Kit "inFrameLikelihood"). */
    val inFrameLikelihood: Float,
)

/** A detected human body (pose). Landmarks may be partially outside the frame. */
data class DetectedBody(
    val id: Int,
    val bounds: NormalizedRect,
    val landmarks: Map<BodyLandmarkType, BodyLandmark>,
    val confidence: Float = 1f,
) {
    fun landmark(type: BodyLandmarkType): BodyLandmark? = landmarks[type]
    fun visible(type: BodyLandmarkType, threshold: Float = 0.5f): BodyLandmark? =
        landmarks[type]?.takeIf { it.inFrameLikelihood >= threshold }
}

enum class SubjectKind { FACE, PERSON, SALIENT_REGION }

/**
 * The composition engine's notion of "a thing in the frame worth composing around".
 * Built by the engine from faces/bodies/statistics; the primary subject drives most guidance.
 */
data class DetectedSubject(
    val id: Int,
    val kind: SubjectKind,
    val bounds: NormalizedRect,
    val face: DetectedFace? = null,
    val body: DetectedBody? = null,
    /** Relative visual importance, 0..1. Larger, more central, more confident subjects rank higher. */
    val salience: Float = 0.5f,
    val isPrimary: Boolean = false,
) {
    val center: NormalizedPoint get() = bounds.center
    /** The point the engine should place on a compositional anchor (eyes for people, box center otherwise). */
    val anchorPoint: NormalizedPoint
        get() = face?.let { NormalizedPoint(it.center.x, it.eyeLineY) } ?: bounds.center
}

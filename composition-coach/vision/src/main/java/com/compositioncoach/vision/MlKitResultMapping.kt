package com.compositioncoach.vision

import com.compositioncoach.composition.model.BodyLandmark
import com.compositioncoach.composition.model.BodyLandmarkType
import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SubjectMask
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.segmentation.SegmentationMask
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * ML Kit result -> `:composition` model mapping, shared by the live [VisionPipeline] and the
 * [StillImageAnalyzer] so a captured photo and a preview frame are described in exactly the same terms.
 * Every function takes the [FrameCoordinateMapper] built for the image ML Kit saw.
 */
internal object MlKitResultMapping {
    private const val GAZE_CENTER_THRESHOLD_DEGREES = 12f

    /**
     * Maps an ML Kit [Face] to [DetectedFace].
     *
     * ML Kit's `headEulerAngleY` (yaw) is positive when the face is rotated toward the *camera's* right (the
     * right side of the un-mirrored image ML Kit sees). [DetectedFace.headEulerY] is screen-space: positive =
     * face turned toward the screen's right. Without a mirror camera-right == screen-right; with one
     * ([FrameCoordinateMapper.mirror]) the sign flips, as does roll, and the two eye landmarks swap so that
     * [DetectedFace.leftEye] keeps the meaning it has for an un-mirrored frame. Pose landmarks deliberately do
     * not swap: [BodyLandmarkType] documents LEFT/RIGHT as the subject's own sides.
     *
     * [DetectedFace.eyesOpenProbability] is the lower of ML Kit's two eye-open probabilities (a group shot is
     * spoiled by either eye closing), or null when classification did not run for this face.
     */
    fun mapFace(id: Int, face: Face, mapper: FrameCoordinateMapper): DetectedFace {
        val box = face.boundingBox
        val bounds: NormalizedRect = mapper
            .rotatedFullRectToNormalized(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
            .clampToFrame()

        fun landmark(type: Int) = face.getLandmark(type)?.position?.let { mapper.rotatedFullPointToNormalized(it.x, it.y) }

        var leftEye = landmark(FaceLandmark.LEFT_EYE)
        var rightEye = landmark(FaceLandmark.RIGHT_EYE)
        val noseBase = landmark(FaceLandmark.NOSE_BASE)
        if (mapper.mirror) {
            val tmp = leftEye
            leftEye = rightEye
            rightEye = tmp
        }

        val screenYaw = if (mapper.mirror) -face.headEulerAngleY else face.headEulerAngleY
        val screenRoll = if (mapper.mirror) -face.headEulerAngleZ else face.headEulerAngleZ
        val gaze = when {
            abs(screenYaw) < GAZE_CENTER_THRESHOLD_DEGREES -> GazeDirection.CENTER
            screenYaw > 0f -> GazeDirection.RIGHT
            else -> GazeDirection.LEFT
        }
        return DetectedFace(
            id = face.trackingId ?: id,
            bounds = bounds,
            leftEye = leftEye,
            rightEye = rightEye,
            noseBase = noseBase,
            headEulerY = screenYaw,
            headEulerZ = screenRoll,
            gaze = gaze,
            // ML Kit's face detector exposes no scalar confidence; 1f is DetectedFace's documented default.
            confidence = 1f,
            smilingProbability = face.smilingProbability,
            eyesOpenProbability = eyesOpen(face.leftEyeOpenProbability, face.rightEyeOpenProbability),
        )
    }

    /** min(left, right) when both are known, the known one when only one is, null when neither is. */
    fun eyesOpen(left: Float?, right: Float?): Float? = when {
        left != null && right != null -> minOf(left, right)
        else -> left ?: right
    }

    fun mapPose(pose: Pose, mapper: FrameCoordinateMapper): DetectedBody? {
        val landmarks = LinkedHashMap<BodyLandmarkType, BodyLandmark>()
        for (lm in pose.allPoseLandmarks) {
            val type = POSE_LANDMARK_MAP[lm.landmarkType] ?: continue
            val pos = mapper.rotatedFullPointToNormalized(lm.position.x, lm.position.y)
            landmarks[type] = BodyLandmark(type, pos, lm.inFrameLikelihood)
        }
        if (landmarks.isEmpty()) return null
        val visible = landmarks.values.filter { it.inFrameLikelihood > 0.5f }
        if (visible.isEmpty()) return null
        val bounds = NormalizedRect(
            visible.minOf { it.position.x },
            visible.minOf { it.position.y },
            visible.maxOf { it.position.x },
            visible.maxOf { it.position.y },
        ).clampToFrame()
        return DetectedBody(id = 0, bounds = bounds, landmarks = landmarks, confidence = visible.size.toFloat() / landmarks.size)
    }

    /**
     * Maps an ML Kit object-detection result (rotated-full pixel space, like [Face.getBoundingBox]) to a
     * [RawDetectedObject]; filtering/categorization happens in [ObjectMapper].
     */
    fun mapRawObject(obj: com.google.mlkit.vision.objects.DetectedObject, mapper: FrameCoordinateMapper): RawDetectedObject {
        val box = obj.boundingBox
        val bounds = mapper
            .rotatedFullRectToNormalized(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
            .clampToFrame()
        val topLabel = obj.labels.maxByOrNull { it.confidence }
        return RawDetectedObject(
            bounds = bounds,
            trackingId = obj.trackingId,
            labelText = topLabel?.text,
            labelConfidence = topLabel?.confidence,
        )
    }

    /**
     * Converts a raw-size [SegmentationMask] (a direct `ByteBuffer` of float confidences in rotated-full pixel
     * space) into a [MaskDownsampler.DEFAULT_GRID_SIZE]-square [SubjectMask]. [scratch] is reused for the float
     * copy when it is exactly the right size; the returned grid is always a fresh array (callers may hold it
     * across frames). Returns the mask and the scratch array actually used, so the caller can keep it.
     */
    fun subjectMask(mask: SegmentationMask, mapper: FrameCoordinateMapper, scratch: FloatArray): Pair<SubjectMask, FloatArray> {
        val needed = mask.width * mask.height
        val floats = scratch.takeIf { it.size == needed } ?: FloatArray(needed)
        val buffer = mask.buffer
        buffer.rewind()
        // ML Kit fills these buffers from native code in the platform's native byte order.
        buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats, 0, needed)
        val size = MaskDownsampler.DEFAULT_GRID_SIZE
        val grid = MaskDownsampler.downsample(floats, mask.width, mask.height, mapper, size, size, FloatArray(size * size))
        return SubjectMask(size, size, grid) to floats
    }

    private val POSE_LANDMARK_MAP: Map<Int, BodyLandmarkType> = mapOf(
        PoseLandmark.NOSE to BodyLandmarkType.NOSE,
        PoseLandmark.LEFT_EYE to BodyLandmarkType.LEFT_EYE,
        PoseLandmark.RIGHT_EYE to BodyLandmarkType.RIGHT_EYE,
        PoseLandmark.LEFT_EAR to BodyLandmarkType.LEFT_EAR,
        PoseLandmark.RIGHT_EAR to BodyLandmarkType.RIGHT_EAR,
        PoseLandmark.LEFT_SHOULDER to BodyLandmarkType.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER to BodyLandmarkType.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW to BodyLandmarkType.LEFT_ELBOW,
        PoseLandmark.RIGHT_ELBOW to BodyLandmarkType.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST to BodyLandmarkType.LEFT_WRIST,
        PoseLandmark.RIGHT_WRIST to BodyLandmarkType.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP to BodyLandmarkType.LEFT_HIP,
        PoseLandmark.RIGHT_HIP to BodyLandmarkType.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE to BodyLandmarkType.LEFT_KNEE,
        PoseLandmark.RIGHT_KNEE to BodyLandmarkType.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE to BodyLandmarkType.LEFT_ANKLE,
        PoseLandmark.RIGHT_ANKLE to BodyLandmarkType.RIGHT_ANKLE,
        PoseLandmark.LEFT_HEEL to BodyLandmarkType.LEFT_HEEL,
        PoseLandmark.RIGHT_HEEL to BodyLandmarkType.RIGHT_HEEL,
        PoseLandmark.LEFT_FOOT_INDEX to BodyLandmarkType.LEFT_FOOT_INDEX,
        PoseLandmark.RIGHT_FOOT_INDEX to BodyLandmarkType.RIGHT_FOOT_INDEX,
    )
}

package com.compositioncoach.composition.fixtures

import com.compositioncoach.composition.model.BodyLandmark
import com.compositioncoach.composition.model.BodyLandmarkType
import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.DeviceOrientation
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect

/** Small builders for constructing [FrameAnalysis] and [ImageStatistics] fixtures in tests without ceremony. */
object SyntheticFrames {

    fun face(
        cx: Float,
        cy: Float,
        size: Float,
        gaze: GazeDirection = GazeDirection.CENTER,
        id: Int = 0,
        confidence: Float = 1f,
        headEulerY: Float? = null,
    ): DetectedFace {
        val half = size / 2f
        val bounds = NormalizedRect(cx - half, cy - half, cx + half, cy + half)
        val eyeY = bounds.top + bounds.height * 0.4f
        return DetectedFace(
            id = id,
            bounds = bounds,
            leftEye = NormalizedPoint(cx - size * 0.15f, eyeY),
            rightEye = NormalizedPoint(cx + size * 0.15f, eyeY),
            gaze = gaze,
            confidence = confidence,
            headEulerY = headEulerY,
        )
    }

    fun landmark(type: BodyLandmarkType, x: Float, y: Float, inFrameLikelihood: Float = 0.95f): Pair<BodyLandmarkType, BodyLandmark> =
        type to BodyLandmark(type, NormalizedPoint(x, y), inFrameLikelihood)

    fun body(
        cx: Float,
        top: Float,
        bottom: Float,
        halfWidth: Float = 0.12f,
        id: Int = 0,
        confidence: Float = 1f,
        landmarks: Map<BodyLandmarkType, BodyLandmark> = emptyMap(),
    ): DetectedBody = DetectedBody(
        id = id,
        bounds = NormalizedRect(cx - halfWidth, top, cx + halfWidth, bottom),
        landmarks = landmarks,
        confidence = confidence,
    )

    /** A simple standing full-body pose from shoulders to ankles, centred at [cx], feet at [feetY]. */
    fun standingBody(cx: Float, headTop: Float, feetY: Float, halfWidth: Float = 0.12f, id: Int = 0): DetectedBody {
        val shoulderY = headTop + (feetY - headTop) * 0.18f
        val hipY = headTop + (feetY - headTop) * 0.5f
        val kneeY = headTop + (feetY - headTop) * 0.75f
        return body(
            cx, headTop, feetY, halfWidth, id,
            landmarks = mapOf(
                landmark(BodyLandmarkType.LEFT_SHOULDER, cx - halfWidth, shoulderY),
                landmark(BodyLandmarkType.RIGHT_SHOULDER, cx + halfWidth, shoulderY),
                landmark(BodyLandmarkType.LEFT_HIP, cx - halfWidth * 0.7f, hipY),
                landmark(BodyLandmarkType.RIGHT_HIP, cx + halfWidth * 0.7f, hipY),
                landmark(BodyLandmarkType.LEFT_KNEE, cx - halfWidth * 0.6f, kneeY),
                landmark(BodyLandmarkType.RIGHT_KNEE, cx + halfWidth * 0.6f, kneeY),
                landmark(BodyLandmarkType.LEFT_ANKLE, cx - halfWidth * 0.5f, feetY),
                landmark(BodyLandmarkType.RIGHT_ANKLE, cx + halfWidth * 0.5f, feetY),
            ),
        )
    }

    fun frameWith(
        faces: List<DetectedFace> = emptyList(),
        bodies: List<DetectedBody> = emptyList(),
        rollDegrees: Float? = null,
        orientationReliable: Boolean = true,
        stats: ImageStatistics? = null,
        frameWidth: Int = 1080,
        frameHeight: Int = 1920,
        timestampNanos: Long = 0L,
        isFrontCamera: Boolean = false,
    ): FrameAnalysis = FrameAnalysis(
        timestampNanos = timestampNanos,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        faces = faces,
        bodies = bodies,
        stats = stats,
        orientation = rollDegrees?.let { DeviceOrientation(it, 0f, orientationReliable) },
        isFrontCamera = isFrontCamera,
    )

    fun symmetricStats(gridWidth: Int = 16, gridHeight: Int = 16, horizontalSymmetry: Float = 0.9f): ImageStatistics =
        ImageStatistics.flat(gridWidth, gridHeight).copy(horizontalSymmetry = horizontalSymmetry)

    fun statsWithHorizonAngle(angleDegrees: Float, gridWidth: Int = 16, gridHeight: Int = 16): ImageStatistics =
        ImageStatistics.flat(gridWidth, gridHeight).copy(estimatedHorizonAngleDegrees = angleDegrees)

    /** Flat statistics with a sharp brightness step at [rowFraction] (0..1) to simulate a visible horizon line. */
    fun statsWithHorizonLine(rowFraction: Float, gridWidth: Int = 16, gridHeight: Int = 16): ImageStatistics {
        val luminance = FloatArray(gridWidth * gridHeight)
        val row = (rowFraction * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        for (r in 0 until gridHeight) for (c in 0 until gridWidth) {
            luminance[r * gridWidth + c] = if (r < row) 0.8f else 0.3f
        }
        val edge = FloatArray(gridWidth * gridHeight) { 0.05f }
        for (c in 0 until gridWidth) edge[row * gridWidth + c] = 0.9f
        return ImageStatistics(
            gridWidth, gridHeight, luminance, edge,
            horizontalSymmetry = 0.3f, verticalSymmetry = 0.3f,
            meanLuminance = 0.55f, contrast = 0.4f,
        )
    }

    /** A frame statistics grid with a narrow high-edge-density vertical band directly above [face]. */
    fun withVerticalBandAbove(face: DetectedFace, gridWidth: Int = 20, gridHeight: Int = 20, bandEdge: Float = 0.7f, backgroundEdge: Float = 0.03f): ImageStatistics {
        val luminance = FloatArray(gridWidth * gridHeight) { 0.5f }
        val edge = FloatArray(gridWidth * gridHeight) { backgroundEdge }
        val colStart = (face.bounds.left * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val colEnd = ((face.bounds.right * gridWidth).toInt() - 1).coerceIn(colStart, gridWidth - 1)
        val rowEnd = ((face.estimatedHeadTop.coerceIn(0f, 1f)) * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        for (row in 0..rowEnd) for (col in colStart..colEnd) edge[row * gridWidth + col] = bandEdge
        return ImageStatistics(
            gridWidth, gridHeight, luminance, edge,
            horizontalSymmetry = 0.3f, verticalSymmetry = 0.3f,
            meanLuminance = 0.5f, contrast = 0.3f,
        )
    }
}

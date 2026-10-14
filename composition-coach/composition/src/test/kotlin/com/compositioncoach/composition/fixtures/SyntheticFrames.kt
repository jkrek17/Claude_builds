package com.compositioncoach.composition.fixtures

import com.compositioncoach.composition.model.BodyLandmark
import com.compositioncoach.composition.model.BodyLandmarkType
import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.DeviceOrientation
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ObjectCategory
import com.compositioncoach.composition.model.SubjectMask

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
        objects: List<DetectedObject> = emptyList(),
        subjectMask: SubjectMask? = null,
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
        objects = objects,
        subjectMask = subjectMask,
        orientation = rollDegrees?.let { DeviceOrientation(it, 0f, orientationReliable) },
        isFrontCamera = isFrontCamera,
    )

    /** A detected non-person object (e.g. a plate, a pint glass) centred at ([cx], [cy]) with the given square [size]. */
    fun objectAt(
        cx: Float,
        cy: Float,
        size: Float,
        category: ObjectCategory = ObjectCategory.FOOD,
        confidence: Float = 0.85f,
        id: Int = 0,
    ): DetectedObject {
        val half = size / 2f
        return DetectedObject(
            id = id,
            bounds = NormalizedRect(cx - half, cy - half, cx + half, cy + half),
            category = category,
            confidence = confidence,
        )
    }

    /**
     * A [SubjectMask] whose cells inside [rect] are "subject" (probability 1) and everything else is
     * background (probability 0) — the simplest possible mask shape for tests that just need a silhouette
     * with a clean, known boundary.
     */
    fun maskFromRect(rect: NormalizedRect, gridWidth: Int = 32, gridHeight: Int = 32): SubjectMask {
        val probability = FloatArray(gridWidth * gridHeight)
        val c0 = (rect.left * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val c1 = ((rect.right * gridWidth).toInt() - 1).coerceIn(c0, gridWidth - 1)
        val r0 = (rect.top * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        val r1 = ((rect.bottom * gridHeight).toInt() - 1).coerceIn(r0, gridHeight - 1)
        for (row in r0..r1) for (col in c0..c1) probability[row * gridWidth + col] = 1f
        return SubjectMask(gridWidth, gridHeight, probability)
    }

    /**
     * Same silhouette as [maskFromRect], but with a narrow vertical band of *mask* probability directly
     * above the rect's top edge, simulating what a "pole growing out of the head" would look like if the
     * segmenter itself extended onto the pole. Most pole tests instead pair [maskFromRect] with
     * [withVerticalBandAbove]-style edge-density stats (the pole is a background object, not part of the
     * person mask) — this helper exists for the rarer case of asserting the mask's own boundary shape.
     */
    fun maskWithPoleAbove(rect: NormalizedRect, poleWidthCells: Int = 1, poleHeightCells: Int = 6, gridWidth: Int = 32, gridHeight: Int = 32): SubjectMask {
        val probability = FloatArray(gridWidth * gridHeight)
        val c0 = (rect.left * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val c1 = ((rect.right * gridWidth).toInt() - 1).coerceIn(c0, gridWidth - 1)
        val r0 = (rect.top * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        val r1 = ((rect.bottom * gridHeight).toInt() - 1).coerceIn(r0, gridHeight - 1)
        for (row in r0..r1) for (col in c0..c1) probability[row * gridWidth + col] = 1f
        val poleCol0 = ((c0 + c1) / 2).coerceIn(0, gridWidth - 1)
        val poleCol1 = (poleCol0 + poleWidthCells - 1).coerceIn(poleCol0, gridWidth - 1)
        for (row in (r0 - poleHeightCells).coerceAtLeast(0) until r0) {
            for (col in poleCol0..poleCol1) probability[row * gridWidth + col] = 1f
        }
        return SubjectMask(gridWidth, gridHeight, probability)
    }

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

    /**
     * A compact, centred square of elevated edge density against a quieter background — a plausible
     * "object on a table" signal for [StatsHeuristics.compactHighEdgeRegion]. Tune [hotEdge] relative to
     * [backgroundEdge] to land the resulting contrast ratio wherever a test needs it (e.g. above
     * [SubjectResolver]'s OBJECT-intent threshold but below its default one).
     */
    fun centralSalientRegion(hotEdge: Float, backgroundEdge: Float = 0.05f, gridWidth: Int = 20, gridHeight: Int = 20): ImageStatistics {
        val luminance = FloatArray(gridWidth * gridHeight) { 0.5f }
        val edge = FloatArray(gridWidth * gridHeight) { backgroundEdge }
        val c0 = (gridWidth * 0.35f).toInt()
        val c1 = (gridWidth * 0.65f).toInt()
        val r0 = (gridHeight * 0.35f).toInt()
        val r1 = (gridHeight * 0.65f).toInt()
        for (r in r0..r1) for (c in c0..c1) edge[r * gridWidth + c] = hotEdge
        return ImageStatistics(
            gridWidth, gridHeight, luminance, edge,
            horizontalSymmetry = 0.3f, verticalSymmetry = 0.3f,
            meanLuminance = 0.5f, contrast = 0.3f,
        )
    }

    /** Uniform luminance and edge density everywhere — no separation signal at all between any two regions. */
    fun statsUniform(gridWidth: Int = 32, gridHeight: Int = 32, luminance: Float = 0.5f, edge: Float = 0.3f): ImageStatistics = ImageStatistics(
        gridWidth, gridHeight,
        FloatArray(gridWidth * gridHeight) { luminance },
        FloatArray(gridWidth * gridHeight) { edge },
        horizontalSymmetry = 0.3f, verticalSymmetry = 0.3f,
        meanLuminance = luminance, contrast = 0.1f,
    )

    /**
     * A narrow (single-cell-ish) high-edge-density column directly above [rect]'s top edge, on the *same*
     * grid resolution used for a paired [SubjectMask] (see [maskFromRect]) — pairing the two at matching
     * grid sizes makes [MaskHeuristics]'s cell-to-cell mapping exact, which is what a true "pole through the
     * head" test needs.
     */
    fun statsWithNarrowPoleAbove(
        rect: NormalizedRect,
        poleWidthFraction: Float = 0.03f,
        poleHeightAboveFraction: Float = 0.2f,
        gridWidth: Int = 32,
        gridHeight: Int = 32,
        poleEdge: Float = 0.9f,
        backgroundEdge: Float = 0.03f,
    ): ImageStatistics {
        val luminance = FloatArray(gridWidth * gridHeight) { 0.5f }
        val edge = FloatArray(gridWidth * gridHeight) { backgroundEdge }
        val centerX = rect.center.x
        val c0 = ((centerX - poleWidthFraction / 2f) * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val c1 = ((centerX + poleWidthFraction / 2f) * gridWidth).toInt().coerceIn(c0, gridWidth - 1)
        val r0 = ((rect.top - poleHeightAboveFraction) * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        val r1 = ((rect.top * gridHeight).toInt() - 1).coerceIn(r0, gridHeight - 1)
        for (row in r0..r1) for (col in c0..c1) edge[row * gridWidth + col] = poleEdge
        return ImageStatistics(
            gridWidth, gridHeight, luminance, edge,
            horizontalSymmetry = 0.3f, verticalSymmetry = 0.3f,
            meanLuminance = 0.5f, contrast = 0.3f,
        )
    }

    /**
     * Like [centralSalientRegion] but the hot patch (and, with it, the resulting salient-region subject)
     * sits at [cx] instead of dead centre, with [horizontalSymmetry] independently controllable — useful for
     * a "symmetric scene with an off-centre subject" fixture where [SymmetryAnalyzer] and [SubjectResolver]'s
     * salient-region fallback both need to see the same feature.
     */
    fun offCenterSalientRegion(
        cx: Float,
        hotEdge: Float,
        backgroundEdge: Float = 0.05f,
        horizontalSymmetry: Float = 0.9f,
        gridWidth: Int = 20,
        gridHeight: Int = 20,
    ): ImageStatistics {
        val luminance = FloatArray(gridWidth * gridHeight) { 0.5f }
        val edge = FloatArray(gridWidth * gridHeight) { backgroundEdge }
        val half = 0.15f
        val c0 = ((cx - half) * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val c1 = ((cx + half) * gridWidth).toInt().coerceIn(c0, gridWidth - 1)
        val r0 = (0.35f * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        val r1 = (0.65f * gridHeight).toInt().coerceIn(r0, gridHeight - 1)
        for (r in r0..r1) for (c in c0..c1) edge[r * gridWidth + c] = hotEdge
        return ImageStatistics(
            gridWidth, gridHeight, luminance, edge,
            horizontalSymmetry = horizontalSymmetry, verticalSymmetry = horizontalSymmetry,
            meanLuminance = 0.5f, contrast = 0.3f,
        )
    }

    /**
     * A sky-over-ground luminance step at [rowFraction] with a *flat, low* edge grid (unlike
     * [statsWithHorizonLine], which spikes edge density along the horizon row) — this reads as a horizon to
     * [SceneSpecificAnalyzer]'s luminance-transition scan without also creating a "compact high edge
     * region" that [SubjectResolver]'s salient-region fallback would otherwise mistake for a subject. Set
     * [angleDegrees] to also make the frame classify as [SceneType.LANDSCAPE] via a reported horizon angle.
     */
    fun statsWithHorizonStep(rowFraction: Float, angleDegrees: Float? = null, gridWidth: Int = 16, gridHeight: Int = 16): ImageStatistics {
        val row = (rowFraction * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        val luminance = FloatArray(gridWidth * gridHeight) { i -> if (i / gridWidth < row) 0.8f else 0.3f }
        val edge = FloatArray(gridWidth * gridHeight) { 0.01f }
        return ImageStatistics(
            gridWidth, gridHeight, luminance, edge,
            horizontalSymmetry = 0.3f, verticalSymmetry = 0.3f,
            meanLuminance = 0.55f, contrast = 0.4f,
            estimatedHorizonAngleDegrees = angleDegrees,
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

package com.compositioncoach.composition.geometry

import com.compositioncoach.composition.model.BodyLandmark
import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.DetectedLine
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.SubjectMask

/**
 * Simulates the effect of a camera move (pan/tilt + zoom, described by a [ReframeVector]) on the
 * detected geometry of a [FrameAnalysis], so the [com.compositioncoach.composition.engine.CompositionOptimizer]
 * can ask "what would the score look like if the photographer made this move?" without a new camera frame.
 *
 * Sign convention (matches [ReframeVector]'s own kdoc, applied literally so this stays independent of any
 * analyzer-level interpretation): panning the camera right (`dx > 0`) shifts frame content left
 * (`x -= dx`); raising/tilting the camera up (`dy > 0`) shifts frame content down (`y += dy`); zooming in
 * (`zoom > 0`) magnifies geometry about the frame centre. Roll is not simulated here: the optimizer only
 * searches pan/tilt/zoom candidates.
 *
 * KNOWN APPROXIMATION: only face/body geometry is transformed. [ImageStatistics] (luminance/edge grids,
 * symmetry, horizon angle) is left untouched on the transformed frame — recomputing a downscaled image
 * for a hypothetical crop is exactly the expensive work this optimizer exists to avoid. This means
 * statistics-driven analyzers (background distraction, balance, symmetry, negative space, leading lines,
 * scene-specific) see the *original* frame's stats even for shifted candidates; only face/body-driven
 * analyzers (subject placement, headroom, looking room, edge tension, cropping) meaningfully change
 * across candidates. This is fine for the optimizer's purpose (find the best *directional* move) since
 * those are exactly the categories a small reframe changes fastest.
 *
 * [crop] is the exact counterpart for a crop of a captured photo (used by
 * [com.compositioncoach.composition.engine.CropSuggester]): there *every* signal, statistics and mask
 * included, is re-expressed in the crop window, because the crop's pixels are already known.
 */
object FrameTransform {

    /** Transforms a single point as if the camera had moved by [vector]. */
    fun transformPoint(p: NormalizedPoint, vector: ReframeVector): NormalizedPoint {
        val shiftedX = p.x - vector.dx
        val shiftedY = p.y + vector.dy
        val scale = (1f + vector.zoom).coerceAtLeast(0.05f)
        val cx = 0.5f
        val cy = 0.5f
        return NormalizedPoint(cx + (shiftedX - cx) * scale, cy + (shiftedY - cy) * scale)
    }

    /** Transforms an axis-aligned rect the same way; scaling about the frame centre keeps it axis-aligned. */
    fun transformRect(rect: NormalizedRect, vector: ReframeVector): NormalizedRect {
        val a = transformPoint(NormalizedPoint(rect.left, rect.top), vector)
        val b = transformPoint(NormalizedPoint(rect.right, rect.bottom), vector)
        return NormalizedRect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    private fun transformFace(face: DetectedFace, vector: ReframeVector): DetectedFace = face.copy(
        bounds = transformRect(face.bounds, vector),
        leftEye = face.leftEye?.let { transformPoint(it, vector) },
        rightEye = face.rightEye?.let { transformPoint(it, vector) },
        noseBase = face.noseBase?.let { transformPoint(it, vector) },
    )

    private fun transformBody(body: DetectedBody, vector: ReframeVector): DetectedBody = body.copy(
        bounds = transformRect(body.bounds, vector),
        landmarks = body.landmarks.mapValues { (_, lm) ->
            BodyLandmark(lm.type, transformPoint(lm.position, vector), lm.inFrameLikelihood)
        },
    )

    /** Returns a copy of [frame] with faces/bodies moved as if the photographer applied [vector]. A zero vector is a cheap no-op copy. */
    fun transform(frame: FrameAnalysis, vector: ReframeVector): FrameAnalysis {
        if (vector == ReframeVector.ZERO) return frame
        return frame.copy(
            faces = frame.faces.map { transformFace(it, vector) },
            bodies = frame.bodies.map { transformBody(it, vector) },
        )
    }

    // ---- Cropping --------------------------------------------------------------------------------------

    /**
     * A landmark that ends up further than this outside the crop window is reported as out of frame
     * (`inFrameLikelihood = 0`), the way a pose detector reports a joint that is not in the picture. One
     * just outside (within the margin) keeps its likelihood: that *is* a crop through the joint.
     */
    const val CROP_LANDMARK_OUTSIDE_MARGIN = 0.03f

    /** Maps a point of the original frame into the coordinates of the crop window [rect]. */
    fun cropPoint(p: NormalizedPoint, rect: NormalizedRect): NormalizedPoint =
        NormalizedPoint((p.x - rect.left) / rect.width, (p.y - rect.top) / rect.height)

    /** Maps a rect of the original frame into the coordinates of the crop window [rect] (not clipped). */
    fun cropRect(r: NormalizedRect, rect: NormalizedRect): NormalizedRect {
        val a = cropPoint(NormalizedPoint(r.left, r.top), rect)
        val b = cropPoint(NormalizedPoint(r.right, r.bottom), rect)
        return NormalizedRect(a.x, a.y, b.x, b.y)
    }

    /**
     * The [FrameAnalysis] the photo would produce if it were cropped to [rect] (normalized, clamped into the
     * frame), so the whole engine can score a crop exactly as it scores a photo. Unlike [transform], every
     * signal is carried over:
     *  - faces, bodies and objects are re-expressed in the window's coordinates; a detection that falls
     *    entirely outside the window is dropped; one that straddles the window edge is kept (it is cut);
     *    body landmarks more than [CROP_LANDMARK_OUTSIDE_MARGIN] outside get `inFrameLikelihood = 0`;
     *  - the [SubjectMask] and the [ImageStatistics] luminance/edge grids are re-sampled, nearest neighbour,
     *    into grids of the same size (each output cell takes the source cell under its centre);
     *  - `meanLuminance`, `contrast` and the two symmetry scores are shifted by how much the re-sampled grid
     *    changes them (the vision layer computes them on a finer buffer than the grid, so the reported values
     *    are kept as the baseline and only the crop's effect is applied — an identity crop changes nothing);
     *  - dominant lines are mapped and clipped to the window (dropped when nothing is left);
     *  - `frameWidth`/`frameHeight` become the crop's pixel size, so the aspect ratio is the crop's.
     * Orientation, the horizon angle and timings are unchanged (a crop does not rotate anything). An empty
     * window returns [frame] unchanged.
     */
    fun crop(frame: FrameAnalysis, rect: NormalizedRect): FrameAnalysis {
        val window = rect.clampToFrame()
        if (window.isEmpty) return frame
        if (window == NormalizedRect.FULL) return frame
        return frame.copy(
            frameWidth = Math.round(frame.frameWidth * window.width).coerceAtLeast(1),
            frameHeight = Math.round(frame.frameHeight * window.height).coerceAtLeast(1),
            faces = frame.faces.filter { overlaps(it.bounds, window) }.map { cropFace(it, window) },
            bodies = frame.bodies.filter { overlaps(it.bounds, window) }.map { cropBody(it, window) },
            objects = frame.objects.filter { overlaps(it.bounds, window) }.map { it.copy(bounds = cropRect(it.bounds, window)) },
            subjectMask = frame.subjectMask?.let { cropMask(it, window) },
            stats = frame.stats?.let { cropStats(it, window) },
        )
    }

    private fun overlaps(r: NormalizedRect, window: NormalizedRect): Boolean =
        r.right > window.left && r.left < window.right && r.bottom > window.top && r.top < window.bottom

    private fun cropFace(face: DetectedFace, window: NormalizedRect): DetectedFace = face.copy(
        bounds = cropRect(face.bounds, window),
        leftEye = face.leftEye?.let { cropPoint(it, window) },
        rightEye = face.rightEye?.let { cropPoint(it, window) },
        noseBase = face.noseBase?.let { cropPoint(it, window) },
    )

    private fun cropBody(body: DetectedBody, window: NormalizedRect): DetectedBody = body.copy(
        bounds = cropRect(body.bounds, window),
        landmarks = body.landmarks.mapValues { (_, lm) ->
            val p = cropPoint(lm.position, window)
            val m = CROP_LANDMARK_OUTSIDE_MARGIN
            val outside = p.x < -m || p.x > 1f + m || p.y < -m || p.y > 1f + m
            BodyLandmark(lm.type, p, if (outside) 0f else lm.inFrameLikelihood)
        },
    )

    /** Nearest-neighbour re-sample of a row-major [gridWidth]×[gridHeight] grid into the window, same size. */
    private fun resample(grid: FloatArray, gridWidth: Int, gridHeight: Int, window: NormalizedRect): FloatArray {
        val out = FloatArray(gridWidth * gridHeight)
        for (row in 0 until gridHeight) {
            val y = window.top + (row + 0.5f) / gridHeight * window.height
            val srcRow = (y * gridHeight).toInt().coerceIn(0, gridHeight - 1)
            for (col in 0 until gridWidth) {
                val x = window.left + (col + 0.5f) / gridWidth * window.width
                val srcCol = (x * gridWidth).toInt().coerceIn(0, gridWidth - 1)
                out[row * gridWidth + col] = grid[srcRow * gridWidth + srcCol]
            }
        }
        return out
    }

    private fun cropMask(mask: SubjectMask, window: NormalizedRect): SubjectMask =
        SubjectMask(mask.gridWidth, mask.gridHeight, resample(mask.probability, mask.gridWidth, mask.gridHeight, window))

    private fun cropStats(stats: ImageStatistics, window: NormalizedRect): ImageStatistics {
        val w = stats.gridWidth
        val h = stats.gridHeight
        val luminance = resample(stats.luminance, w, h, window)
        val edge = resample(stats.edgeDensity, w, h, window)
        val meanShift = mean(luminance) - mean(stats.luminance)
        val contrastShift = stdDev(luminance) - stdDev(stats.luminance)
        val hSymShift = gridSymmetry(luminance, edge, w, h, horizontal = true) - gridSymmetry(stats.luminance, stats.edgeDensity, w, h, horizontal = true)
        val vSymShift = gridSymmetry(luminance, edge, w, h, horizontal = false) - gridSymmetry(stats.luminance, stats.edgeDensity, w, h, horizontal = false)
        return stats.copy(
            luminance = luminance,
            edgeDensity = edge,
            meanLuminance = (stats.meanLuminance + meanShift).coerceIn(0f, 1f),
            contrast = (stats.contrast + contrastShift).coerceIn(0f, 1f),
            horizontalSymmetry = (stats.horizontalSymmetry + hSymShift).coerceIn(0f, 1f),
            verticalSymmetry = (stats.verticalSymmetry + vSymShift).coerceIn(0f, 1f),
            dominantLines = stats.dominantLines.mapNotNull { cropLine(it, window) },
        )
    }

    private fun mean(values: FloatArray): Float = if (values.isEmpty()) 0f else values.sum() / values.size

    private fun stdDev(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val m = mean(values)
        var sum = 0f
        for (v in values) sum += (v - m) * (v - m)
        return kotlin.math.sqrt(sum / values.size)
    }

    /**
     * Same construction as `:vision`'s `ImageStatisticsComputer` symmetry score (edge-weighted mean absolute
     * difference to the mirrored cell, blended toward 0.5 by how much edge content there is), computed on
     * the grid. Only its *change* under a crop is used (see [crop]).
     */
    private fun gridSymmetry(luminance: FloatArray, edge: FloatArray, w: Int, h: Int, horizontal: Boolean): Float {
        var weightedDiff = 0.0
        var weightSum = 0.0
        for (row in 0 until h) for (col in 0 until w) {
            val mirrorCol = if (horizontal) w - 1 - col else col
            val mirrorRow = if (horizontal) row else h - 1 - row
            val i = row * w + col
            val m = mirrorRow * w + mirrorCol
            val weight = (edge[i] + edge[m]) / 2.0
            weightedDiff += weight * kotlin.math.abs(luminance[i] - luminance[m])
            weightSum += weight
        }
        val raw = if (weightSum > 1e-6) 1.0 - weightedDiff / weightSum else 1.0
        val confidence = (mean(edge) / SYMMETRY_EDGE_SATURATION).toDouble().coerceIn(0.0, 1.0)
        return (0.5 + confidence * (raw - 0.5)).toFloat()
    }

    /** Matches `ImageStatisticsComputer.EDGE_CONFIDENCE_SATURATION`. */
    private const val SYMMETRY_EDGE_SATURATION = 0.06f

    /** Maps [line] into the window and clips it to the window's `0..1` square (Liang-Barsky); null when nothing remains. */
    private fun cropLine(line: DetectedLine, window: NormalizedRect): DetectedLine? {
        val a = cropPoint(line.start, window)
        val b = cropPoint(line.end, window)
        val dx = b.x - a.x
        val dy = b.y - a.y
        var t0 = 0f
        var t1 = 1f
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(a.x, 1f - a.x, a.y, 1f - a.y)
        for (i in 0 until 4) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return null
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) t0 = maxOf(t0, t) else t1 = minOf(t1, t)
            }
        }
        if (t0 >= t1) return null
        return line.copy(
            start = NormalizedPoint(a.x + t0 * dx, a.y + t0 * dy),
            end = NormalizedPoint(a.x + t1 * dx, a.y + t1 * dy),
        )
    }
}

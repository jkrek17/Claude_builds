package com.compositioncoach.vision.merge

import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.vision.PixelShift
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The group photo's face composite: every face is taken from the burst frame where it looks best (eyes open,
 * sharp) and pasted into the best overall frame. Pure maths on face boxes, luma guides and `IntArray` ARGB
 * pixels, so it is unit-tested on synthetic images; the Android side (decoding, ML Kit, strips) is
 * `GroupCompositeCapture`.
 *
 * ## Steps
 * 1. **Base frame** ([chooseBase]): the frame with the most faces whose eyes are open, then the sharpest.
 * 2. **Matching** ([matchFaces]): the base frame's faces are the tracks; each other frame's faces are matched
 *    to them one-to-one by centre distance (greedy, nearest first, within [MATCH_DISTANCE_FACES] face sizes).
 *    A face the detector missed in the base is never touched; a face missing in another frame just has no
 *    candidate there.
 * 3. **Best frame per face** ([chooseSources]): a candidate needs both eyes open (probability above
 *    [EYES_OPEN_MIN]) and, unless the base's eyes are closed, a sharpness score over its own face box
 *    ([sharpness], variance of the Laplacian) at least [SHARPNESS_GAIN] times the base's, so a tie never
 *    triggers a paste. The sharpest qualifying candidate wins.
 * 4. **Alignment and guard** ([alignBox]): the gyro shift is refined by a block match of the grown face box on
 *    the guide-scale luma (+/- [SEARCH_RADIUS] guide pixels, sum of absolute differences). When the best match
 *    still differs by more than [MAX_RESIDUAL] (mean absolute luma, 0..1) the person moved or turned between
 *    frames and pasting would show a seam or a ghost: the base is kept for that face ([plan]).
 * 5. **Blend** ([blendPatch]): the face box grown by [BOX_GROWTH] is copied from the aligned source with a
 *    smoothstep feather of [FEATHER_FRACTION] of the box width inside its edges.
 *
 * Coordinates: faces are in normalized coordinates of the *stored* frame (the buffer the JPEG decodes to);
 * [uprightRectToBuffer] converts ML Kit's upright boxes. Shifts follow `GyroAlignment`: content that moved by
 * `(dx, dy)` relative to the reference is found at `(x + dx, y + dy)`.
 */
object FaceComposite {
    /** Both eyes must be open with at least this probability to be a candidate (spec: above 0.6). */
    const val EYES_OPEN_MIN = 0.6f

    /** The pasted box is the face box grown by this fraction of its size (spec: 25 %). */
    const val BOX_GROWTH = 0.25f

    /** The paste fades in over this fraction of the box width (spec: 8 %). */
    const val FEATHER_FRACTION = 0.08f

    /** Block-match search range around the gyro shift, in guide pixels (spec: +/- 8). */
    const val SEARCH_RADIUS = 8

    /** Largest mean absolute luma difference (0..1) over the grown box after alignment that still allows a paste. */
    const val MAX_RESIDUAL = 0.08f

    /** A sharper candidate must beat the base's face by this factor (so equal faces are left alone). */
    const val SHARPNESS_GAIN = 1.1f

    /** Two detections are the same person when their centres are within this many face sizes. */
    const val MATCH_DISTANCE_FACES = 0.75f

    /** At most this many pixel comparisons per block-match candidate (larger boxes are subsampled). */
    private const val MAX_SAD_SAMPLES = 4096

    /** One detected face: [bounds] normalized in the stored frame, [eyesOpen] min of both eyes (null = unknown). */
    class Face(val bounds: NormalizedRect, val eyesOpen: Float?)

    /** An integer pixel box, [right] and [bottom] exclusive. */
    class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }

    /** Result of a block match: the source is sampled at `(x + dx, y + dy)`; [residual] is the mean absolute luma difference. */
    class Alignment(val dx: Int, val dy: Int, val residual: Float)

    /** One face to paste: base-frame [bounds], from frame [source], with the guide-scale [alignment] found for its grown box. */
    class Replacement(val track: Int, val source: Int, val bounds: NormalizedRect, val alignment: Alignment)

    /** What [plan] decided: the [base] frame, the faces to [replacements], and how many were refused ([rejected]) for moving. */
    class Plan(val base: Int, val replacements: List<Replacement>, val rejected: Int, val faceCount: Int)

    // ---- Selection -------------------------------------------------------------------------------------------

    /** Whether [face]'s eyes count as open for choosing a base (unknown counts as open, as `GroupReadiness` does). */
    fun isEyesOpen(face: Face): Boolean = face.eyesOpen == null || face.eyesOpen > EYES_OPEN_MIN

    /**
     * The base frame: most faces with open eyes, then the highest whole-frame [frameSharpness], then the
     * earliest. [faces] and [frameSharpness] are per frame.
     */
    fun chooseBase(faces: List<List<Face>>, frameSharpness: List<Float>): Int {
        require(faces.isNotEmpty() && faces.size == frameSharpness.size) { "one face list and one sharpness per frame" }
        var best = 0
        var bestOpen = faces[0].count { isEyesOpen(it) }
        for (i in 1 until faces.size) {
            val open = faces[i].count { isEyesOpen(it) }
            if (open > bestOpen || (open == bestOpen && frameSharpness[i] > frameSharpness[best])) {
                best = i
                bestOpen = open
            }
        }
        return best
    }

    /**
     * Matches faces across frames: result `[track][frame]`, one track per face of frame [base] (same order), the
     * face of that frame matched to it or null. [aspect] is frame width / height, so distances are isotropic.
     */
    fun matchFaces(frames: List<List<Face>>, base: Int, aspect: Float): List<Array<Face?>> {
        val baseFaces = frames[base]
        val tracks = baseFaces.map { face -> arrayOfNulls<Face>(frames.size).also { it[base] = face } }
        for (k in frames.indices) {
            if (k == base) continue
            val candidates = ArrayList<Triple<Float, Int, Int>>()
            for (t in baseFaces.indices) for (f in frames[k].indices) {
                val a = baseFaces[t].bounds
                val b = frames[k][f].bounds
                val distance = hypot((a.center.x - b.center.x) * aspect, a.center.y - b.center.y)
                val limit = MATCH_DISTANCE_FACES * maxOf(a.width * aspect, a.height)
                if (distance <= limit) candidates += Triple(distance, t, f)
            }
            val usedTracks = HashSet<Int>()
            val usedFaces = HashSet<Int>()
            for ((_, t, f) in candidates.sortedBy { it.first }) {
                if (t in usedTracks || f in usedFaces) continue
                usedTracks += t
                usedFaces += f
                tracks[t][k] = frames[k][f]
            }
        }
        return tracks
    }

    /**
     * The frame each track should be taken from: the sharpest frame whose face has eyes open above
     * [EYES_OPEN_MIN] and, unless the base face's eyes are closed, a [sharpness] of at least [SHARPNESS_GAIN]
     * times the base's; otherwise [base]. [sharpness] is `[track][frame]`.
     */
    fun chooseSources(tracks: List<Array<Face?>>, base: Int, sharpness: List<FloatArray>): IntArray =
        IntArray(tracks.size) { t ->
            val baseFace = tracks[t][base] ?: return@IntArray base
            val baseClosed = !isEyesOpen(baseFace)
            var best = base
            var bestScore = if (baseClosed) -1f else sharpness[t][base] * SHARPNESS_GAIN
            for (k in tracks[t].indices) {
                if (k == base) continue
                val face = tracks[t][k] ?: continue
                val open = face.eyesOpen ?: continue
                if (open <= EYES_OPEN_MIN) continue
                val score = sharpness[t][k]
                if (score > bestScore) {
                    best = k
                    bestScore = score
                }
            }
            best
        }

    /**
     * Focus score of [bounds] in a luma plane (0..1, [width] x [height]): variance of the 4-neighbour Laplacian
     * over the box interior (the same measure as `SharpnessScorer`). 0 for a box under 3x3 pixels.
     */
    fun sharpness(luma: FloatArray, width: Int, height: Int, bounds: NormalizedRect): Float {
        val left = (bounds.left * width).roundToInt().coerceIn(1, width - 1)
        val right = (bounds.right * width).roundToInt().coerceIn(left, width - 1)
        val top = (bounds.top * height).roundToInt().coerceIn(1, height - 1)
        val bottom = (bounds.bottom * height).roundToInt().coerceIn(top, height - 1)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in top until bottom) for (x in left until right) {
            val i = y * width + x
            val lap = luma[i - width] + luma[i + width] + luma[i - 1] + luma[i + 1] - 4f * luma[i]
            sum += lap
            sumSq += lap.toDouble() * lap
            n++
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    // ---- Alignment -------------------------------------------------------------------------------------------

    /** [bounds] grown by [BOX_GROWTH] around its centre, as a pixel box clamped to a [width] x [height] frame. */
    fun growBox(bounds: NormalizedRect, width: Int, height: Int): PixelBox {
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f
        val halfW = bounds.width * (1f + BOX_GROWTH) / 2f
        val halfH = bounds.height * (1f + BOX_GROWTH) / 2f
        return PixelBox(
            ((cx - halfW) * width).roundToInt().coerceIn(0, width),
            ((cy - halfH) * height).roundToInt().coerceIn(0, height),
            ((cx + halfW) * width).roundToInt().coerceIn(0, width),
            ((cy + halfH) * height).roundToInt().coerceIn(0, height),
        )
    }

    /**
     * Block-matches [box] of [base] against [other] (both luma 0..1, [width] x [height]): tries every shift
     * within [radius] of ([initialDx], [initialDy]) and returns the one with the smallest mean absolute
     * difference (ties go to the one nearest the initial guess). [other] is sampled at `(x + dx, y + dy)`,
     * clamped at the frame edge.
     */
    fun alignBox(
        base: FloatArray,
        other: FloatArray,
        width: Int,
        height: Int,
        box: PixelBox,
        initialDx: Int = 0,
        initialDy: Int = 0,
        radius: Int = SEARCH_RADIUS,
    ): Alignment {
        if (box.isEmpty) return Alignment(initialDx, initialDy, Float.MAX_VALUE)
        val step = if (box.width * box.height > MAX_SAD_SAMPLES) 2 else 1
        var bestDx = initialDx
        var bestDy = initialDy
        var bestSad = Float.MAX_VALUE
        var bestDistance = Int.MAX_VALUE
        for (dy in initialDy - radius..initialDy + radius) {
            for (dx in initialDx - radius..initialDx + radius) {
                var sad = 0f
                var n = 0
                var y = box.top
                while (y < box.bottom) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    var x = box.left
                    while (x < box.right) {
                        sad += abs(base[y * width + x] - other[sy * width + (x + dx).coerceIn(0, width - 1)])
                        n++
                        x += step
                    }
                    y += step
                }
                val mean = sad / n
                val distance = abs(dx - initialDx) + abs(dy - initialDy)
                if (mean < bestSad - 1e-6f || (mean <= bestSad + 1e-6f && distance < bestDistance)) {
                    bestSad = mean
                    bestDx = dx
                    bestDy = dy
                    bestDistance = distance
                }
            }
        }
        return Alignment(bestDx, bestDy, bestSad)
    }

    /**
     * The whole decision for a burst: base frame, per-face source frames and the alignment guard.
     *
     * [lumas] are the frames' guide luma planes ([width] x [height]), [faces] their detections (normalized),
     * [shifts] the gyro shift of each frame relative to the same reference, in guide pixels (null or missing =
     * no shift known). [base] overrides the automatic base choice.
     */
    fun plan(
        lumas: List<FloatArray>,
        width: Int,
        height: Int,
        faces: List<List<Face>>,
        shifts: List<PixelShift>? = null,
        base: Int? = null,
    ): Plan {
        require(lumas.size == faces.size && lumas.isNotEmpty()) { "one luma plane and one face list per frame" }
        val whole = NormalizedRect(0f, 0f, 1f, 1f)
        val baseIndex = base ?: chooseBase(faces, lumas.map { sharpness(it, width, height, whole) })
        val tracks = matchFaces(faces, baseIndex, width.toFloat() / height)
        val scores = tracks.map { track ->
            FloatArray(lumas.size) { k -> track[k]?.let { sharpness(lumas[k], width, height, it.bounds) } ?: 0f }
        }
        val sources = chooseSources(tracks, baseIndex, scores)
        val replacements = ArrayList<Replacement>()
        var rejected = 0
        for (t in tracks.indices) {
            val k = sources[t]
            if (k == baseIndex) continue
            val bounds = tracks[t][baseIndex]!!.bounds
            val s = shifts?.getOrNull(k) ?: PixelShift.NONE
            val b = shifts?.getOrNull(baseIndex) ?: PixelShift.NONE
            val alignment = alignBox(
                lumas[baseIndex], lumas[k], width, height, growBox(bounds, width, height),
                initialDx = s.dx - b.dx, initialDy = s.dy - b.dy,
            )
            if (alignment.residual > MAX_RESIDUAL) rejected++ else replacements += Replacement(t, k, bounds, alignment)
        }
        return Plan(baseIndex, replacements, rejected, tracks.size)
    }

    // ---- Blend -----------------------------------------------------------------------------------------------

    /** Feather width in pixels for [box]: [FEATHER_FRACTION] of its width, at least 1. */
    fun featherPx(box: PixelBox): Int = (box.width * FEATHER_FRACTION).roundToInt().coerceAtLeast(1)

    /**
     * Blend weight (0..1) of pixel (x, y) for a paste into [box]: 0 outside and at the edge, a smoothstep up to
     * 1 at [feather] pixels inside, 1 beyond.
     */
    fun featherWeight(box: PixelBox, feather: Int, x: Int, y: Int): Float {
        if (x < box.left || x >= box.right || y < box.top || y >= box.bottom) return 0f
        val edge = minOf(x - box.left + 0.5f, box.right - x - 0.5f, y - box.top + 0.5f, box.bottom - y - 0.5f)
        val t = (edge / feather).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The part of [box] whose source pixels `(x + dx, y + dy)` lie inside a [width] x [height] frame, so a
     * paste never samples outside the source. Null when nothing is left.
     */
    fun sourceSafeBox(box: PixelBox, dx: Int, dy: Int, width: Int, height: Int): PixelBox? {
        val safe = PixelBox(
            maxOf(box.left, -dx), maxOf(box.top, -dy),
            minOf(box.right, width - dx), minOf(box.bottom, height - dy),
        )
        return if (safe.isEmpty) null else safe
    }

    /**
     * Blends [patch] into rows [y0] until [y0] + [rows] of an image [width] wide (`out`, `width * rows` ARGB).
     * [patch] holds the already aligned source pixels for destination rows [patchTop] until
     * [patchTop] + [patchRows] and columns `box.left` until `box.right` (`box.width * patchRows`); rows outside
     * the strip are ignored, so a strip loop can hand each strip its own part of the box.
     */
    fun blendPatch(out: IntArray, width: Int, y0: Int, rows: Int, box: PixelBox, patch: IntArray, patchTop: Int, patchRows: Int) {
        val feather = featherPx(box)
        val from = maxOf(y0, patchTop, box.top)
        val to = minOf(y0 + rows, patchTop + patchRows, box.bottom)
        for (y in from until to) {
            for (x in box.left until box.right) {
                val w = featherWeight(box, feather, x, y)
                if (w <= 0f) continue
                val i = (y - y0) * width + x
                val s = patch[(y - patchTop) * box.width + (x - box.left)]
                val d = out[i]
                out[i] = (0xFF shl 24) or
                    (mix((d shr 16) and 0xFF, (s shr 16) and 0xFF, w) shl 16) or
                    (mix((d shr 8) and 0xFF, (s shr 8) and 0xFF, w) shl 8) or
                    mix(d and 0xFF, s and 0xFF, w)
            }
        }
    }

    private fun mix(base: Int, source: Int, w: Float): Int = (base + w * (source - base)).roundToInt().coerceIn(0, 255)

    /**
     * Whole-image composite for small images (tests, previews): runs [plan] with the frames' own luma as the
     * guide (scale 1) and pastes every replacement into a copy of the base frame. Returns the pixels and the plan.
     */
    fun composite(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        faces: List<List<Face>>,
        shifts: List<PixelShift>? = null,
        base: Int? = null,
    ): Pair<IntArray, Plan> {
        val lumas = frames.map { px -> FloatArray(px.size) { i -> luma(px[i]) } }
        val plan = plan(lumas, width, height, faces, shifts, base)
        val out = frames[plan.base].copyOf()
        for (r in plan.replacements) {
            val grown = growBox(r.bounds, width, height)
            val box = sourceSafeBox(grown, r.alignment.dx, r.alignment.dy, width, height) ?: continue
            val source = frames[r.source]
            val patch = IntArray(box.width * box.height) { i ->
                source[(box.top + i / box.width + r.alignment.dy) * width + box.left + i % box.width + r.alignment.dx]
            }
            blendPatch(out, width, 0, height, box, patch, box.top, box.height)
        }
        return out to plan
    }

    /** BT.601 luma (0..1) of one ARGB pixel. */
    fun luma(argb: Int): Float =
        (0.299f * ((argb shr 16) and 0xFF) + 0.587f * ((argb shr 8) and 0xFF) + 0.114f * (argb and 0xFF)) / 255f

    // ---- Orientation -----------------------------------------------------------------------------------------

    /**
     * Converts a box in the *upright* image (the stored buffer rotated clockwise by [bufferToUprightDegrees],
     * which is what ML Kit sees) back to normalized stored-buffer coordinates.
     */
    fun uprightRectToBuffer(rect: NormalizedRect, bufferToUprightDegrees: Int): NormalizedRect {
        fun point(x: Float, y: Float): Pair<Float, Float> = when (((bufferToUprightDegrees % 360) + 360) % 360) {
            90 -> y to 1f - x
            180 -> 1f - x to 1f - y
            270 -> 1f - y to x
            else -> x to y
        }
        val a = point(rect.left, rect.top)
        val b = point(rect.right, rect.bottom)
        return NormalizedRect(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }
}

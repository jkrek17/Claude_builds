package com.compositioncoach.composition.engine

import com.compositioncoach.composition.geometry.FrameTransform
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.CropAspect
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SubjectKind
import kotlin.math.abs

/**
 * "Better crop?" on the review screen: proposes up to [MAX_SUGGESTIONS] crops of the captured photo that the
 * engine predicts will score at least [MIN_GAIN] points higher than the photo as shot.
 *
 * Candidates (all windows lie inside the photo):
 *  1. **Optimizer best**: the optimizer's best simulated move as a crop ([com.compositioncoach.composition.model.FramingCandidate.asCrop]:
 *     a pan becomes a shifted window, a zoom a window of size `1 / (1 + zoom)`).
 *  2. **Thirds**: windows at [THIRDS_SIZES] (85 % and 70 % of each side, photo's own aspect) placed so the
 *     primary subject's anchor (the eyes for a person, the box centre otherwise) lands on the nearest
 *     thirds intersection, as far as the photo's edges allow. Skipped for symmetric scenes, which should
 *     stay centred.
 *  3. **Centred**: for symmetric scenes, an 85 % window centred horizontally on the subject (or the frame).
 *  4. **Portrait shapes**: around a single face subject, a 4:5 and a 1:1 head-and-shoulders window (face
 *     about a third of the crop's height, eyes on the upper third line, face centred horizontally). Never
 *     offered for two or more faces.
 *
 * No candidate may cut into or leave out any subject face (the visible part of each face must be inside the
 * window).
 *
 * Each candidate is scored by building the cropped [FrameAnalysis] ([FrameTransform.crop]) and running the
 * full [CompositionEngine] on it with the same level, intent and taste weights. Candidates scoring below
 * `current + MIN_GAIN` (or that lose the subject) are dropped; the rest are sorted by score and
 * de-duplicated (a candidate overlapping a better one with IoU > [DUPLICATE_IOU] is dropped).
 */
class CropSuggester(
    private val engine: CompositionEngine = CompositionEngine.default(),
) {
    fun suggest(
        frame: FrameAnalysis,
        intent: SceneIntent,
        level: GuidanceLevel = GuidanceLevel.COACH,
        adaptive: AdaptiveWeights = AdaptiveWeights.NEUTRAL,
    ): List<CropSuggestion> = runCatching { suggestInternal(frame, intent, level, adaptive) }.getOrElse { emptyList() }

    /** Same as [suggest] but reuses an existing evaluation of [frame] (saves one engine run). */
    fun suggest(
        frame: FrameAnalysis,
        current: CompositionResult,
        intent: SceneIntent,
        level: GuidanceLevel = GuidanceLevel.COACH,
        adaptive: AdaptiveWeights = AdaptiveWeights.NEUTRAL,
    ): List<CropSuggestion> = runCatching { suggestFrom(frame, current, intent, level, adaptive) }.getOrElse { emptyList() }

    private fun suggestInternal(frame: FrameAnalysis, intent: SceneIntent, level: GuidanceLevel, adaptive: AdaptiveWeights): List<CropSuggestion> =
        suggestFrom(frame, engine.evaluate(frame, level, intent, adaptive), intent, level, adaptive)

    private fun suggestFrom(
        frame: FrameAnalysis,
        current: CompositionResult,
        intent: SceneIntent,
        level: GuidanceLevel,
        adaptive: AdaptiveWeights,
    ): List<CropSuggestion> {
        if (current.awaitingSubject) return emptyList()
        val candidates = candidates(frame, current)
        val threshold = current.score + MIN_GAIN
        val scored = candidates.mapNotNull { candidate ->
            val rect = candidate.rect.clampToFrame()
            if (rect.isEmpty || rect.width < MIN_SIZE || rect.height < MIN_SIZE) return@mapNotNull null
            if (!keepsEveryFace(current, rect)) return@mapNotNull null
            val result = engine.evaluate(FrameTransform.crop(frame, rect), level, intent, adaptive)
            if (result.awaitingSubject) return@mapNotNull null
            if (current.primarySubject != null && result.primarySubject == null) return@mapNotNull null
            if (result.score < threshold) return@mapNotNull null
            CropSuggestion(rect, candidate.aspect, result.score, candidate.headline)
        }
        val kept = mutableListOf<CropSuggestion>()
        for (suggestion in scored.sortedByDescending { it.predictedScore }) {
            if (kept.any { it.rect.intersectionOverUnion(suggestion.rect) > DUPLICATE_IOU }) continue
            kept += suggestion
            if (kept.size == MAX_SUGGESTIONS) break
        }
        return kept
    }

    /**
     * A crop must never cut into or remove a person: the visible part of every subject face must lie inside
     * the window. (The engine would often score a single-face crop of a group *higher* — it is now a tidy
     * portrait — which is exactly the crop a photographer must never be offered.)
     */
    private fun keepsEveryFace(current: CompositionResult, rect: NormalizedRect): Boolean =
        current.subjects.mapNotNull { it.face }.all { face ->
            val visible = face.bounds.intersect(NormalizedRect.FULL) ?: return@all true
            visible.left >= rect.left - FACE_EPSILON && visible.right <= rect.right + FACE_EPSILON &&
                visible.top >= rect.top - FACE_EPSILON && visible.bottom <= rect.bottom + FACE_EPSILON
        }

    private data class Candidate(val rect: NormalizedRect, val aspect: CropAspect, val headline: String)

    private fun candidates(frame: FrameAnalysis, current: CompositionResult): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val primary = current.primarySubject
        val symmetric = current.scene.isSymmetricScene

        current.optimization?.best?.let { best ->
            best.asCrop()?.let { out += Candidate(it, CropAspect.ORIGINAL, optimizerHeadline(best.vector.primaryDirection())) }
        }

        if (primary != null && !symmetric) {
            val anchor = primary.anchorPoint
            val target = nearestIntersection(anchor)
            val isPerson = primary.kind == SubjectKind.FACE || primary.kind == SubjectKind.PERSON
            val headline = if (isPerson && primary.face != null) HEADLINE_THIRDS_EYES else HEADLINE_THIRDS_SUBJECT
            for (size in THIRDS_SIZES) {
                out += Candidate(windowPlacing(anchor, target, size, size), CropAspect.ORIGINAL, headline)
            }
        }

        if (symmetric) {
            val cx = primary?.anchorPoint?.x ?: 0.5f
            val size = CENTRED_SIZE
            val left = (cx - size / 2f).coerceIn(0f, 1f - size)
            val top = (1f - size) / 2f
            out += Candidate(NormalizedRect(left, top, left + size, top + size), CropAspect.ORIGINAL, HEADLINE_CENTRED)
        }

        val face = primary?.face
        val faceCount = current.subjects.count { it.face != null }
        if (face != null && faceCount == 1) {
            portraitWindow(frame, face.bounds.height, NormalizedPoint(face.center.x, face.eyeLineY), CropAspect.FOUR_FIVE)
                ?.let { out += Candidate(it, CropAspect.FOUR_FIVE, HEADLINE_FOUR_FIVE) }
            portraitWindow(frame, face.bounds.height, NormalizedPoint(face.center.x, face.eyeLineY), CropAspect.SQUARE)
                ?.let { out += Candidate(it, CropAspect.SQUARE, HEADLINE_SQUARE) }
        }
        return out
    }

    companion object {
        const val MIN_GAIN = 3
        const val MAX_SUGGESTIONS = 3
        const val DUPLICATE_IOU = 0.8f
        val THIRDS_SIZES = listOf(0.85f, 0.70f)
        const val CENTRED_SIZE = 0.85f

        /** A head-and-shoulders crop is this many face-box heights tall. */
        const val HEAD_AND_SHOULDERS_FACE_HEIGHTS = 3.2f

        /** Crops smaller than this fraction of a side are not offered (too much resolution lost). */
        const val MIN_SIZE = 0.3f

        private const val FACE_EPSILON = 0.001f

        const val HEADLINE_THIRDS_EYES = "Tighter, eyes on the third"
        const val HEADLINE_THIRDS_SUBJECT = "Tighter, subject on the third"
        const val HEADLINE_CENTRED = "Centred"
        const val HEADLINE_FOUR_FIVE = "4:5 portrait"
        const val HEADLINE_SQUARE = "Square portrait"

        /** Headline for the optimizer's best move shown as a crop. */
        fun optimizerHeadline(direction: Direction): String = when (direction) {
            Direction.LEFT -> "Nudged left"
            Direction.RIGHT -> "Nudged right"
            Direction.UP -> "Nudged up"
            Direction.DOWN -> "Nudged down"
            Direction.CLOSER -> "Tighter"
            else -> "Reframed"
        }

        private val THIRDS = floatArrayOf(1f / 3f, 2f / 3f)

        /** The thirds intersection nearest [p]. */
        fun nearestIntersection(p: NormalizedPoint): NormalizedPoint {
            val x = THIRDS.minByOrNull { abs(it - p.x) }!!
            val y = THIRDS.minByOrNull { abs(it - p.y) }!!
            return NormalizedPoint(x, y)
        }

        /**
         * A [width] × [height] window (normalized) placed so that [anchor] sits at [target] (in the window's
         * own coordinates), then shifted as little as needed to stay inside the photo.
         */
        fun windowPlacing(anchor: NormalizedPoint, target: NormalizedPoint, width: Float, height: Float): NormalizedRect {
            val w = width.coerceIn(0f, 1f)
            val h = height.coerceIn(0f, 1f)
            val left = (anchor.x - target.x * w).coerceIn(0f, 1f - w)
            val top = (anchor.y - target.y * h).coerceIn(0f, 1f - h)
            return NormalizedRect(left, top, left + w, top + h)
        }

        /**
         * A head-and-shoulders window of the given [aspect] (pixel width / height) around a face [faceHeight]
         * tall with its eyes at [eyes]: [HEAD_AND_SHOULDERS_FACE_HEIGHTS] face heights tall, eyes on the upper
         * third, centred on the face, shrunk to fit and kept inside the photo. Null when the photo's pixel
         * size is unknown.
         */
        fun portraitWindow(frame: FrameAnalysis, faceHeight: Float, eyes: NormalizedPoint, aspect: CropAspect): NormalizedRect? {
            val ratio = aspect.widthOverHeight ?: return null
            if (frame.frameWidth <= 0 || frame.frameHeight <= 0) return null
            val frameAspect = frame.frameWidth.toFloat() / frame.frameHeight
            var h = (faceHeight * HEAD_AND_SHOULDERS_FACE_HEIGHTS).coerceAtMost(1f)
            var w = h * ratio / frameAspect
            if (w > 1f) {
                h /= w
                w = 1f
            }
            return windowPlacing(eyes, NormalizedPoint(0.5f, 1f / 3f), w, h)
        }
    }
}

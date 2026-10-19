package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectKind
import com.compositioncoach.composition.model.SubjectMask
import kotlin.math.abs

/**
 * Light on the subject: is the person (or object) lit well enough to photograph, and from a sensible
 * direction? Composition rules say nothing about this, but for portraits it matters more than the rule of
 * thirds — a perfectly framed silhouette against a window is still a bad photo.
 *
 * Applicable only with a primary [SubjectKind.FACE] / [SubjectKind.PERSON] subject, or a
 * [SubjectKind.OBJECT] subject when a [SubjectMask] is available (without a silhouette there is no way to
 * tell the object from the table it sits on), and only when the engine produced luminance statistics.
 *
 * **Where the numbers come from.** The *subject luminance* is the mean luminance of the face box when a
 * face is at least [MIN_FACE_HEIGHT] tall (what matters in a portrait is the face, not a dark jacket), else
 * the mean over the mask's subject cells, else over the subject's bounding box. The *background* is a
 * [MaskHeuristics.ringCells] ring traced around the mask silhouette (confidence [MASK_CONFIDENCE]) or, with
 * no mask, a rectangular ring around the subject box (confidence [NO_MASK_CONFIDENCE]). With a mask we also
 * measure the zone directly behind and beside the head, which is what separates "a bright window right
 * behind you" from "the whole room is bright".
 *
 * **Issues**, at most one per frame, checked in this order (each is advice, never a blocker, so severity
 * never reaches HIGH):
 *  1. *Backlit* ([Severity.MEDIUM], `light.backlit`): subject / background < [BACKLIT_RATIO] while the
 *     background is brighter than [BRIGHT_BACKGROUND]. If the brightest background is directly behind the
 *     head the advice names the window.
 *  2. *Too dark* (`light.dark`): subject luminance < [DARK_SUBJECT], background not bright. LOW, MEDIUM
 *     when below [VERY_DARK_SUBJECT].
 *  3. *Blown highlights* (`light.blown`, LOW): more than [BLOWN_FRACTION] of the face cells above
 *     [BLOWN_LUMINANCE].
 *  4. *Harsh side light* (`light.split`, LOW, [Direction.NONE]): left and right halves of the face box
 *     differ by more than [SPLIT_DIFFERENCE], face at least [MIN_FACE_HEIGHT] tall.
 *
 * **Good light** (a strength, only when no issue fired): subject luminance within
 * [GOOD_LUMINANCE_MIN]..[GOOD_LUMINANCE_MAX], moderate contrast with the background, face halves within
 * [GOOD_HALVES_DIFFERENCE], essentially no blown cells, and an overall score of at least [STRENGTH_SCORE].
 *
 * The 0..1 score is the weakest of four smooth factors (exposure, subject-vs-background ratio, face-half
 * balance, blown fraction) so it degrades continuously toward each threshold instead of cliff-edging.
 */
class LightAnalyzer : CompositionAnalyzer {
    override val name: String = "LightAnalyzer"
    override val category: MetricCategory = MetricCategory.LIGHT

    override fun analyze(context: AnalysisContext): CompositionMetric {
        val subject = context.primarySubject
        val stats = context.frame.stats
        if (subject == null || stats == null || subject.kind !in LIGHT_SUBJECT_KINDS) return inapplicable()
        val measured = measure(subject, stats, context.frame.subjectMask) ?: return inapplicable()
        return evaluate(measured)
    }

    // --- Measurement -------------------------------------------------------------------------------

    /** Everything the decision logic needs; null luminances mean "could not be measured for this frame". */
    private class Reading(
        val subjectLuminance: Float,
        /** Brightness of whatever is behind/around the subject, or null when nothing surrounds it (extreme close-up). */
        val backgroundLuminance: Float?,
        /** True only when a mask shows the brightest part of the background sits directly behind the head. */
        val windowBehindHead: Boolean,
        val leftHalf: Float?,
        val rightHalf: Float?,
        /** Fraction of face cells above [BLOWN_LUMINANCE], or null when the face is too small to say. */
        val blownFraction: Float?,
        val confidence: Float,
        val region: NormalizedRect,
    )

    private fun measure(subject: DetectedSubject, stats: ImageStatistics, frameMask: SubjectMask?): Reading? {
        val mask = usableMask(subject, stats, frameMask)
        if (subject.kind == SubjectKind.OBJECT && mask == null) return null

        val face = subject.face?.takeIf { it.bounds.height >= MIN_FACE_HEIGHT && !it.bounds.clampToFrame().isEmpty }
        val faceCells = face?.let { sample(stats, it.bounds, ensureOne = true) }

        var subjectLuminance: Float? = faceCells?.mean
        var background: Float? = null
        var windowBehindHead = false
        var region = face?.bounds ?: subject.bounds

        if (mask != null) {
            val cells = MaskHeuristics.subjectCells(mask)
            val ring = MaskHeuristics.ringCells(mask, RING_WIDTH_CELLS, knownSubjectCells = cells)
            if (subjectLuminance == null) subjectLuminance = MaskHeuristics.meanLuminance(mask, stats, cells)
            if (face == null) region = mask.bounds(MaskHeuristics.SUBJECT_THRESHOLD) ?: region
            val surround = if (ring.isEmpty()) null else MaskHeuristics.meanLuminance(mask, stats, ring)
            val behindHead = behindHeadZone(mask, stats, cells)
            background = listOfNotNull(surround, behindHead?.mean).maxOrNull()
            windowBehindHead = behindHead != null && surround != null &&
                behindHead.mean >= BRIGHT_BACKGROUND && behindHead.mean >= surround + WINDOW_MARGIN
            if (windowBehindHead) region = behindHead!!.rect
        } else {
            val box = subject.bounds
            if (subjectLuminance == null) subjectLuminance = sample(stats, box, ensureOne = true)?.mean
            val margin = (minOf(box.width, box.height) * RING_MARGIN_FRACTION).coerceIn(MIN_RING_MARGIN, MAX_RING_MARGIN)
            background = sample(stats, box.inflate(margin), exclude = box)?.mean
        }
        val subjectLum = subjectLuminance ?: return null
        if (!subjectLum.isFinite()) return null

        var left: Float? = null
        var right: Float? = null
        var blown: Float? = null
        if (face != null) {
            val midX = (face.bounds.left + face.bounds.right) / 2f
            left = sample(stats, NormalizedRect(face.bounds.left, face.bounds.top, midX, face.bounds.bottom))?.mean
            right = sample(stats, NormalizedRect(midX, face.bounds.top, face.bounds.right, face.bounds.bottom))?.mean
            blown = faceCells?.takeIf { it.count >= MIN_BLOWN_CELLS }?.let { it.blownFraction }
        }
        return Reading(
            subjectLuminance = subjectLum,
            backgroundLuminance = background?.takeIf { it.isFinite() },
            windowBehindHead = windowBehindHead,
            leftHalf = left?.takeIf { it.isFinite() },
            rightHalf = right?.takeIf { it.isFinite() },
            blownFraction = blown,
            confidence = if (mask != null) MASK_CONFIDENCE else NO_MASK_CONFIDENCE,
            region = region,
        )
    }

    /**
     * The mask to use as the subject silhouette, or null. A person's mask is used as-is; for an OBJECT
     * subject the mask (a person segmenter's output) is clipped to the object's own box, so a person
     * standing behind a pint glass does not become "the subject" of the light reading.
     */
    private fun usableMask(subject: DetectedSubject, stats: ImageStatistics, mask: SubjectMask?): SubjectMask? {
        if (mask == null) return null
        val candidate = if (subject.kind == SubjectKind.OBJECT) clipMask(mask, subject.bounds.inflate(OBJECT_CLIP_MARGIN)) else mask
        val cells = MaskHeuristics.subjectCells(candidate)
        if (cells.size < MIN_MASK_CELLS || stats.gridWidth <= 0) return null
        return candidate
    }

    private fun clipMask(mask: SubjectMask, rect: NormalizedRect): SubjectMask {
        val clipped = FloatArray(mask.probability.size)
        for (row in 0 until mask.gridHeight) for (col in 0 until mask.gridWidth) {
            val cx = (col + 0.5f) / mask.gridWidth
            val cy = (row + 0.5f) / mask.gridHeight
            if (cx >= rect.left && cx < rect.right && cy >= rect.top && cy < rect.bottom) {
                clipped[row * mask.gridWidth + col] = mask.at(col, row)
            }
        }
        return SubjectMask(mask.gridWidth, mask.gridHeight, clipped)
    }

    private class Zone(val mean: Float, val rect: NormalizedRect)

    /**
     * Mean background luminance in the patch directly above and beside the head: the columns of the
     * mask's top [HEAD_ROWS] rows, padded by [HEAD_ZONE_PAD_CELLS] either side, from [HEAD_ZONE_ABOVE_CELLS]
     * above the mask top down to [HEAD_ROWS] below it. Subject cells are excluded. Null when too few
     * background cells fall inside (a head touching the frame top, say).
     */
    private fun behindHeadZone(mask: SubjectMask, stats: ImageStatistics, subjectCells: List<Pair<Int, Int>>): Zone? {
        val topRow = subjectCells.minOf { it.second }
        val headCols = subjectCells.filter { it.second < topRow + HEAD_ROWS }.map { it.first }
        val c0 = (headCols.min() - HEAD_ZONE_PAD_CELLS).coerceAtLeast(0)
        val c1 = (headCols.max() + HEAD_ZONE_PAD_CELLS).coerceAtMost(mask.gridWidth - 1)
        val r0 = (topRow - HEAD_ZONE_ABOVE_CELLS).coerceAtLeast(0)
        val r1 = (topRow + HEAD_ROWS).coerceAtMost(mask.gridHeight - 1)
        var sum = 0f
        var n = 0
        for (row in r0..r1) for (col in c0..c1) {
            if (mask.at(col, row) >= MaskHeuristics.SUBJECT_THRESHOLD) continue
            val (sc, sr) = stats.cellOf(with(MaskHeuristics) { mask.cellCenter(col, row) })
            sum += stats.luminanceAt(sc, sr)
            n++
        }
        if (n < MIN_ZONE_CELLS) return null
        val rect = NormalizedRect(c0.toFloat() / mask.gridWidth, r0.toFloat() / mask.gridHeight, (c1 + 1).toFloat() / mask.gridWidth, (r1 + 1).toFloat() / mask.gridHeight)
        return Zone(sum / n, rect)
    }

    private class Sample(val mean: Float, val count: Int, val blownFraction: Float)

    /**
     * Mean luminance over the stats cells whose centre falls inside [rect] (and outside [exclude]).
     * With [ensureOne], a rect smaller than a cell still reports the cell containing its centre.
     */
    private fun sample(stats: ImageStatistics, rect: NormalizedRect, exclude: NormalizedRect? = null, ensureOne: Boolean = false): Sample? {
        val r = rect.clampToFrame()
        if (r.isEmpty) return null
        val gw = stats.gridWidth
        val gh = stats.gridHeight
        var sum = 0f
        var blown = 0
        var n = 0
        val c0 = kotlin.math.ceil(r.left * gw - 0.5f).toInt().coerceAtLeast(0)
        val c1 = (kotlin.math.ceil(r.right * gw - 0.5f).toInt() - 1).coerceAtMost(gw - 1)
        val r0 = kotlin.math.ceil(r.top * gh - 0.5f).toInt().coerceAtLeast(0)
        val r1 = (kotlin.math.ceil(r.bottom * gh - 0.5f).toInt() - 1).coerceAtMost(gh - 1)
        for (row in r0..r1) for (col in c0..c1) {
            if (exclude != null) {
                val cx = (col + 0.5f) / gw
                val cy = (row + 0.5f) / gh
                if (cx >= exclude.left && cx < exclude.right && cy >= exclude.top && cy < exclude.bottom) continue
            }
            val v = stats.luminanceAt(col, row)
            sum += v
            if (v > BLOWN_LUMINANCE) blown++
            n++
        }
        if (n == 0) {
            if (!ensureOne) return null
            val (col, row) = stats.cellOf(r.center)
            val v = stats.luminanceAt(col, row)
            return Sample(v, 1, if (v > BLOWN_LUMINANCE) 1f else 0f)
        }
        return Sample(sum / n, n, blown.toFloat() / n)
    }

    // --- Decision ----------------------------------------------------------------------------------

    private fun evaluate(reading: Reading): CompositionMetric {
        val subject = reading.subjectLuminance
        val background = reading.backgroundLuminance
        val brightBackground = background != null && background > BRIGHT_BACKGROUND
        val ratio = if (brightBackground) subject / background!! else null
        val halvesDifference = if (reading.leftHalf != null && reading.rightHalf != null) abs(reading.leftHalf - reading.rightHalf) else null

        val recommendation: Recommendation? = when {
            ratio != null && ratio < BACKLIT_RATIO -> backlit(reading)
            subject < DARK_SUBJECT -> tooDark(reading)
            reading.blownFraction != null && reading.blownFraction > BLOWN_FRACTION -> blownHighlights(reading)
            halvesDifference != null && halvesDifference > SPLIT_DIFFERENCE -> sideLight(reading)
            else -> null
        }

        val exposure = when {
            subject < GOOD_LUMINANCE_MIN -> { val t = (subject / GOOD_LUMINANCE_MIN).coerceIn(0f, 1f); t * t }
            subject > GOOD_LUMINANCE_MAX -> 1f - ((subject - GOOD_LUMINANCE_MAX) / (1f - GOOD_LUMINANCE_MAX)).coerceIn(0f, 1f)
            else -> 1f
        }
        val separation = if (ratio == null) 1f else ((ratio - BACKLIT_FLOOR) / (GOOD_RATIO - BACKLIT_FLOOR)).coerceIn(0f, 1f)
        val balance = if (halvesDifference == null) 1f else 1f - ((halvesDifference - GOOD_HALVES_DIFFERENCE) / SPLIT_SPAN).coerceIn(0f, 1f)
        val highlights = if (reading.blownFraction == null) 1f else 1f - ((reading.blownFraction - GOOD_BLOWN_FRACTION) / BLOWN_SPAN).coerceIn(0f, 1f)
        val raw = minOf(minOf(exposure, separation), minOf(balance, highlights))
        val score = if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0.5f

        val moderateContrast = background == null || abs(subject - background) <= MODERATE_CONTRAST
        val good = recommendation == null && score >= STRENGTH_SCORE && moderateContrast &&
            subject in GOOD_LUMINANCE_MIN..GOOD_LUMINANCE_MAX &&
            (halvesDifference == null || halvesDifference <= GOOD_HALVES_DIFFERENCE) &&
            (reading.blownFraction == null || reading.blownFraction <= GOOD_BLOWN_FRACTION)

        return CompositionMetric(
            category = category,
            analyzerName = name,
            score = score,
            confidence = reading.confidence,
            severity = recommendation?.severity ?: Severity.NONE,
            issue = recommendation?.let { issueFor(it.id) },
            recommendation = recommendation,
            // Gated on `recommendation == null` (see the README Quality gate): a strength and an issue
            // must never come from the same metric.
            strength = if (good) "Nice light on your subject" else null,
        )
    }

    private fun issueFor(id: String): String = when (id) {
        ID_BACKLIT -> "Your subject is darker than the background"
        ID_DARK -> "Your subject is in shadow"
        ID_BLOWN -> "Bright light is washing out the face"
        else -> "Light falls on only one side of the face"
    }

    private fun backlit(reading: Reading): Recommendation = Recommendation(
        id = ID_BACKLIT,
        category = category,
        priority = Priority.MEDIUM,
        confidence = reading.confidence,
        severity = Severity.MEDIUM,
        title = "Subject is backlit",
        instruction = if (reading.windowBehindHead) "Move so the bright window is beside you, not behind" else "Turn so the light is behind you",
        reason = "Your subject is darker than the background",
        direction = Direction.NONE,
        region = reading.region,
    )

    private fun tooDark(reading: Reading): Recommendation = Recommendation(
        id = ID_DARK,
        category = category,
        priority = Priority.LOW,
        confidence = reading.confidence,
        severity = if (reading.subjectLuminance < VERY_DARK_SUBJECT) Severity.MEDIUM else Severity.LOW,
        title = "Subject is in shadow",
        instruction = "Move toward the light",
        reason = "Your subject is too dark to show detail",
        direction = Direction.NONE,
        region = reading.region,
    )

    private fun blownHighlights(reading: Reading): Recommendation = Recommendation(
        id = ID_BLOWN,
        category = category,
        priority = Priority.LOW,
        confidence = reading.confidence,
        severity = Severity.LOW,
        title = "Highlights are blown out",
        instruction = "Step into the shade",
        reason = "Part of the face is so bright it loses all detail",
        direction = Direction.NONE,
        region = reading.region,
    )

    private fun sideLight(reading: Reading): Recommendation = Recommendation(
        id = ID_SPLIT,
        category = category,
        priority = Priority.LOW,
        confidence = reading.confidence,
        severity = Severity.LOW,
        title = "Harsh light from one side",
        instruction = "Turn your subject slightly toward the light",
        reason = "One side of the face is much brighter than the other",
        direction = Direction.NONE,
        region = reading.region,
    )

    private fun inapplicable() = CompositionMetric(category, name, score = 1f, confidence = 0f, applicable = false)

    companion object {
        const val ID_BACKLIT = "light.backlit"
        const val ID_DARK = "light.dark"
        const val ID_SPLIT = "light.split"
        const val ID_BLOWN = "light.blown"

        private val LIGHT_SUBJECT_KINDS = setOf(SubjectKind.FACE, SubjectKind.PERSON, SubjectKind.OBJECT)

        /** Subject / background luminance below this, with a bright background, reads as backlit. */
        const val BACKLIT_RATIO = 0.55f
        /** Background mean luminance above this counts as bright. */
        const val BRIGHT_BACKGROUND = 0.6f
        /** Subject mean luminance below this is "too dark"; below [VERY_DARK_SUBJECT] the severity rises to MEDIUM. */
        const val DARK_SUBJECT = 0.22f
        const val VERY_DARK_SUBJECT = 0.12f
        /** Left/right face-half luminance difference above this is harsh side light. */
        const val SPLIT_DIFFERENCE = 0.35f
        /** Faces shorter than this (fraction of frame height) are too few cells to judge halves, blown cells or tone. */
        const val MIN_FACE_HEIGHT = 0.10f
        /** Fraction of face cells above [BLOWN_LUMINANCE] that counts as blown. */
        const val BLOWN_FRACTION = 0.15f
        const val BLOWN_LUMINANCE = 0.95f
        const val MIN_BLOWN_CELLS = 4

        const val GOOD_LUMINANCE_MIN = 0.3f
        const val GOOD_LUMINANCE_MAX = 0.8f
        const val GOOD_HALVES_DIFFERENCE = 0.2f
        const val GOOD_BLOWN_FRACTION = 0.05f
        /** Largest |subject - background| still called "moderate" contrast for the strength. */
        const val MODERATE_CONTRAST = 0.4f
        const val STRENGTH_SCORE = 0.85f

        // Score shaping: ratio factor is 0 at BACKLIT_FLOOR, 1 at GOOD_RATIO; the others fall to 0 over their span.
        const val BACKLIT_FLOOR = 0.25f
        const val GOOD_RATIO = 0.7f
        const val SPLIT_SPAN = 0.4f
        const val BLOWN_SPAN = 0.3f

        /** A head-zone brighter than the surrounding ring by this much means the brightest part is behind the head. */
        const val WINDOW_MARGIN = 0.08f
        const val HEAD_ROWS = 4
        const val HEAD_ZONE_ABOVE_CELLS = 4
        const val HEAD_ZONE_PAD_CELLS = 2
        const val MIN_ZONE_CELLS = 3

        const val RING_WIDTH_CELLS = 2
        const val MIN_MASK_CELLS = 6
        const val OBJECT_CLIP_MARGIN = 0.03f
        const val RING_MARGIN_FRACTION = 0.5f
        const val MIN_RING_MARGIN = 0.04f
        const val MAX_RING_MARGIN = 0.15f

        const val MASK_CONFIDENCE = 0.8f
        const val NO_MASK_CONFIDENCE = 0.55f
    }
}

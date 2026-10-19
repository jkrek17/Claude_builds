package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.CropAspect
import com.compositioncoach.composition.model.MetricCategory
import kotlin.math.abs

/**
 * One learning signal from the user, derived by the app from what they did (see `docs/PRODUCT.md`,
 * "Learning what you like"). Names follow the Wave 2 contract.
 */
sealed class Feedback {
    /** The headline cue for [category] was showing and its issue resolved while it was shown. */
    data class FOLLOWED(val category: MetricCategory) : Feedback()

    /** The user captured with the [category] cue still showing, then kept the photo. */
    data class IGNORED_AND_KEPT(val category: MetricCategory) : Feedback()

    /** The user captured with the [category] cue still showing, then retook the photo. */
    data class IGNORED_AND_RETAKEN(val category: MetricCategory) : Feedback()

    /** The user tapped "Not helpful" on advice about [category]. */
    data class NOT_HELPFUL(val category: MetricCategory) : Feedback()

    /** The user saved a suggested crop of shape [aspect]; [tighter] when it was tighter than the original framing. */
    data class CROP_CHOSEN(val aspect: CropAspect, val tighter: Boolean) : Feedback()
}

/**
 * The on-device taste model: one multiplier per [MetricCategory], bounded to [MIN_MULTIPLIER]..[MAX_MULTIPLIER]
 * and 1.0 by default, plus a running histogram of chosen crops. Immutable: [apply] returns a new instance.
 *
 * **Where it acts.** [ScoreAggregator] multiplies each category's scene weight by its multiplier, and
 * [RecommendationEngine] ranks advice by `confidence × multiplier` within a severity band, so a category the
 * user keeps ignoring counts less in the score and stops winning the headline. [NEUTRAL] multiplies by exactly
 * 1.0, so it reproduces the pre-taste-model pipeline bit for bit.
 *
 * **Update rule** (per event, in this order):
 *  1. every multiplier decays [DECAY] (0.5 %) of the way back toward 1.0, so old habits fade (after 200
 *     events about a third of any deviation remains);
 *  2. the event's step: [Feedback.IGNORED_AND_KEPT] and [Feedback.NOT_HELPFUL] `-0.03`; [Feedback.FOLLOWED]
 *     `+0.01` but only toward 1.0 from below (following advice repairs a lowered weight, it never inflates
 *     one); [Feedback.IGNORED_AND_RETAKEN] `+0.02` (the user should have listened); [Feedback.CROP_CHOSEN]
 *     changes no multiplier, only the crop histogram;
 *  3. clamp to `0.6..1.4`.
 * Steady state: a category that is always ignored settles at the 0.6 floor; one always regretted at 1.4.
 *
 * **Persistence.** [serialize] / [parse] use a compact comma-separated string, e.g.
 * `"HORIZON=0.94,HEADROOM=1.1,crop.SQUARE=3,crop.tight=2,crop.total=4"`: only multipliers that differ from
 * 1.0 are written (rounded to 3 decimals); `crop.<ASPECT>` counts chosen crops per [CropAspect],
 * `crop.tight` how many were tighter, `crop.total` all chosen crops. [parse] ignores unknown keys and bad
 * numbers and clamps every value, so a corrupt or future-format string degrades to [NEUTRAL], never throws.
 */
class AdaptiveWeights private constructor(
    private val multipliers: Map<MetricCategory, Float>,
    private val cropCounts: Map<CropAspect, Int>,
    /** How many chosen crops were tighter than the original framing. */
    val tightCrops: Int,
    /** How many crops the user has chosen in total. */
    val totalCrops: Int,
) {
    /** The multiplier for [category], 1.0 when never adjusted. */
    fun multiplier(category: MetricCategory): Float = multipliers[category] ?: 1f

    /** Every category's multiplier (all categories, 1.0 where never adjusted). */
    fun allMultipliers(): Map<MetricCategory, Float> = MetricCategory.entries.associateWith { multiplier(it) }

    /** How many chosen crops had [aspect]. */
    fun cropCount(aspect: CropAspect): Int = cropCounts[aspect] ?: 0

    /** True when every multiplier is exactly 1.0 (crop history does not affect scoring). */
    val isNeutral: Boolean get() = multipliers.values.all { it == 1f }

    /** Returns the weights after [feedback]; see the class kdoc for the update rule. */
    fun apply(feedback: Feedback): AdaptiveWeights {
        val decayed = HashMap<MetricCategory, Float>()
        for ((category, value) in multipliers) decayed[category] = 1f + (value - 1f) * (1f - DECAY)

        fun step(category: MetricCategory, delta: Float) {
            val current = decayed[category] ?: 1f
            decayed[category] = (current + delta).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        }

        var counts = cropCounts
        var tight = tightCrops
        var total = totalCrops
        when (feedback) {
            is Feedback.IGNORED_AND_KEPT -> step(feedback.category, -IGNORED_STEP)
            is Feedback.NOT_HELPFUL -> step(feedback.category, -NOT_HELPFUL_STEP)
            is Feedback.IGNORED_AND_RETAKEN -> step(feedback.category, RETAKEN_STEP)
            is Feedback.FOLLOWED -> {
                val current = decayed[feedback.category] ?: 1f
                if (current < 1f) decayed[feedback.category] = minOf(1f, current + FOLLOWED_STEP)
            }
            is Feedback.CROP_CHOSEN -> {
                counts = counts + (feedback.aspect to ((counts[feedback.aspect] ?: 0) + 1))
                if (feedback.tighter) tight++
                total++
            }
        }
        return AdaptiveWeights(normalised(decayed), counts, tight, total)
    }

    /** Applies every event in order. */
    fun applyAll(events: Iterable<Feedback>): AdaptiveWeights = events.fold(this) { weights, event -> weights.apply(event) }

    /** Compact persisted form; see the class kdoc. Empty for [NEUTRAL]. */
    fun serialize(): String {
        val parts = mutableListOf<String>()
        for (category in MetricCategory.entries) {
            val value = multipliers[category] ?: continue
            if (abs(value - 1f) < SERIALIZE_EPSILON) continue
            parts += "${category.name}=${format(value)}"
        }
        for (aspect in CropAspect.entries) {
            val count = cropCounts[aspect] ?: 0
            if (count > 0) parts += "$CROP_PREFIX${aspect.name}=$count"
        }
        if (tightCrops > 0) parts += "${CROP_PREFIX}tight=$tightCrops"
        if (totalCrops > 0) parts += "${CROP_PREFIX}total=$totalCrops"
        return parts.joinToString(",")
    }

    override fun toString(): String = "AdaptiveWeights(${serialize()})"

    override fun equals(other: Any?): Boolean =
        other is AdaptiveWeights && allMultipliers() == other.allMultipliers() &&
            CropAspect.entries.all { cropCount(it) == other.cropCount(it) } &&
            tightCrops == other.tightCrops && totalCrops == other.totalCrops

    override fun hashCode(): Int = allMultipliers().hashCode() * 31 + totalCrops

    /**
     * Up to three plain sentences describing the user's taste, strongest first, for Settings and the
     * "Your eye" screen. Drawn from multipliers at least [TENDENCY_THRESHOLD] away from 1.0 and, once
     * [MIN_CROPS_FOR_TENDENCY] crops have been chosen, the crop history. Empty while nothing stands out.
     * See the README's "Adaptive weights" wording table.
     */
    fun summaryTendencies(): List<String> {
        val candidates = mutableListOf<Pair<Float, String>>()
        for (category in MetricCategory.entries) {
            val value = multiplier(category)
            val deviation = value - 1f
            if (abs(deviation) < TENDENCY_THRESHOLD) continue
            val sentence = if (deviation < 0f) lowSentence(category) else highSentence(category)
            candidates += abs(deviation) to sentence
        }
        if (totalCrops >= MIN_CROPS_FOR_TENDENCY) {
            val tightShare = tightCrops.toFloat() / totalCrops
            when {
                tightShare >= CROP_MAJORITY -> candidates += (tightShare - 0.5f) to "You like tight crops"
                tightShare <= 1f - CROP_MAJORITY -> candidates += (0.5f - tightShare) to "You like leaving room around your subject"
            }
            val favourite = CropAspect.entries.filter { it != CropAspect.ORIGINAL }.maxByOrNull { cropCount(it) }
            if (favourite != null) {
                val share = cropCount(favourite).toFloat() / totalCrops
                if (share >= CROP_MAJORITY) {
                    val sentence = if (favourite == CropAspect.SQUARE) "You like square crops" else "You like tall 4:5 crops"
                    candidates += (share - 0.5f) to sentence
                }
            }
        }
        return candidates.sortedByDescending { it.first }.map { it.second }.distinct().take(MAX_TENDENCIES)
    }

    companion object {
        const val MIN_MULTIPLIER = 0.6f
        const val MAX_MULTIPLIER = 1.4f
        const val DECAY = 0.005f
        const val IGNORED_STEP = 0.03f
        const val NOT_HELPFUL_STEP = 0.03f
        const val FOLLOWED_STEP = 0.01f
        const val RETAKEN_STEP = 0.02f

        /** A multiplier must be at least this far from 1.0 to become a tendency sentence. */
        const val TENDENCY_THRESHOLD = 0.1f
        const val MIN_CROPS_FOR_TENDENCY = 3
        const val CROP_MAJORITY = 0.6f
        const val MAX_TENDENCIES = 3

        private const val CROP_PREFIX = "crop."
        private const val SERIALIZE_EPSILON = 0.0005f

        /** Every multiplier 1.0, no crop history: scoring and ranking are exactly the pre-taste-model ones. */
        val NEUTRAL = AdaptiveWeights(emptyMap(), emptyMap(), 0, 0)

        /** Builds weights from explicit multipliers (clamped); categories left out stay at 1.0. */
        fun of(multipliers: Map<MetricCategory, Float>): AdaptiveWeights = AdaptiveWeights(
            normalised(multipliers.mapValues { (_, v) -> if (v.isFinite()) v.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER) else 1f }),
            emptyMap(), 0, 0,
        )

        /** Inverse of [serialize]; never throws (bad input degrades to [NEUTRAL] for the bad parts). */
        fun parse(serialized: String?): AdaptiveWeights {
            if (serialized.isNullOrBlank()) return NEUTRAL
            val multipliers = HashMap<MetricCategory, Float>()
            val counts = HashMap<CropAspect, Int>()
            var tight = 0
            var total = 0
            for (part in serialized.split(',')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (key.startsWith(CROP_PREFIX)) {
                    val count = value.toIntOrNull()?.coerceAtLeast(0) ?: continue
                    when (val name = key.removePrefix(CROP_PREFIX)) {
                        "tight" -> tight = count
                        "total" -> total = count
                        else -> CropAspect.entries.firstOrNull { it.name == name }?.let { counts[it] = count }
                    }
                } else {
                    val category = MetricCategory.entries.firstOrNull { it.name == key } ?: continue
                    val number = value.toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
                    multipliers[category] = number.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
                }
            }
            total = maxOf(total, counts.values.sum(), tight)
            return AdaptiveWeights(normalised(multipliers), counts, tight.coerceAtMost(total), total)
        }

        /** Drops entries that are exactly 1.0 so equal weights always have equal maps. */
        private fun normalised(map: Map<MetricCategory, Float>): Map<MetricCategory, Float> = map.filterValues { it != 1f }

        private fun format(value: Float): String {
            val rounded = Math.round(value * 1000f) / 1000f
            val text = rounded.toString()
            return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
        }

        /** Sentence for a category the user tends to ignore (multiplier below 1). */
        fun lowSentence(category: MetricCategory): String = when (category) {
            MetricCategory.SUBJECT_PLACEMENT -> "You tend to keep centred subjects"
            MetricCategory.HORIZON -> "You rarely follow levelling advice"
            MetricCategory.HEADROOM -> "You rarely follow headroom advice"
            MetricCategory.CROPPING -> "You don't mind cropping into arms and legs"
            MetricCategory.LOOKING_ROOM -> "You don't mind faces looking out of the frame"
            MetricCategory.EDGE_TENSION -> "You don't mind subjects near the edge"
            MetricCategory.BACKGROUND_DISTRACTION -> "You rarely follow background advice"
            MetricCategory.SUBJECT_SEPARATION -> "You don't mind subjects blending in"
            MetricCategory.BALANCE -> "You don't mind a lopsided frame"
            MetricCategory.SYMMETRY -> "You like subjects a little off the middle"
            MetricCategory.NEGATIVE_SPACE -> "You like small subjects in big spaces"
            MetricCategory.LEADING_LINES -> "You rarely follow advice about lines"
            MetricCategory.SCENE_SPECIFIC -> "You rarely follow scene advice"
            MetricCategory.LIGHT -> "You rarely follow light advice"
        }

        /** Sentence for a category the user tends to care about (multiplier above 1). */
        fun highSentence(category: MetricCategory): String = when (category) {
            MetricCategory.SUBJECT_PLACEMENT -> "You care where your subject sits"
            MetricCategory.HORIZON -> "You like level horizons"
            MetricCategory.HEADROOM -> "You care about headroom"
            MetricCategory.CROPPING -> "You like whole arms and legs in frame"
            MetricCategory.LOOKING_ROOM -> "You like space in front of faces"
            MetricCategory.EDGE_TENSION -> "You keep subjects clear of the edges"
            MetricCategory.BACKGROUND_DISTRACTION -> "You like clean backgrounds"
            MetricCategory.SUBJECT_SEPARATION -> "You like subjects that stand out"
            MetricCategory.BALANCE -> "You like a balanced frame"
            MetricCategory.SYMMETRY -> "You like symmetry"
            MetricCategory.NEGATIVE_SPACE -> "You like your subject to fill the frame"
            MetricCategory.LEADING_LINES -> "You like lines that lead to the subject"
            MetricCategory.SCENE_SPECIFIC -> "You follow scene advice closely"
            MetricCategory.LIGHT -> "You care about light"
        }
    }
}

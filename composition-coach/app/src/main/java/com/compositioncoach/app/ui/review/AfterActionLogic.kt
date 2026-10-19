package com.compositioncoach.app.ui.review

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.compositioncoach.app.R
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.outcome.ReviewCardMode
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.composition.engine.AfterActionReport
import com.compositioncoach.composition.engine.Verdict
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Severity
import kotlin.math.roundToInt

/**
 * Everything the after-action card decides that is not layout: which verdict, which sentences, which score
 * pair the "What if" shows, whether the card shows at all. Pure, unit-tested (`AfterActionLogicTest`).
 * `docs/PRODUCT.md` "After the shot" is the specification.
 */
object AfterActionLogic {

    /** The "Try next time" line split for display: the sentence without its "(+N)", and N on its own. */
    data class TryNextLine(val text: String, val gain: Int)

    private val TRAILING_GAIN = Regex("""\s*\(\+\d+\)\s*$""")

    /** The result the card speaks about: the still-photo evaluation once it exists, else the live frame's. */
    fun resultOf(entry: ReviewEntry): CompositionResult = entry.stillResult ?: entry.result

    /**
     * The verdict: the report's own once it exists, otherwise computed from the live score with the engine's
     * rule ([AfterActionReport.verdictFor]), so the word never waits for still analysis.
     */
    fun verdict(report: AfterActionReport?, result: CompositionResult): Verdict =
        report?.verdict ?: AfterActionReport.verdictFor(
            result.score,
            result.metrics.any { it.applicable && it.severity == Severity.HIGH },
        )

    @StringRes
    fun verdictRes(verdict: Verdict): Int = when (verdict) {
        Verdict.KEEPER -> R.string.verdict_keeper
        Verdict.GOOD -> R.string.verdict_good
        Verdict.TRY_AGAIN -> R.string.verdict_try_again
    }

    /** Keeper is green, Good is amber, Try again is plain white: said kindly, never red. */
    fun verdictColor(verdict: Verdict): Color = when (verdict) {
        Verdict.KEEPER -> Accent.Ready
        Verdict.GOOD -> Accent.Warn
        Verdict.TRY_AGAIN -> OnScrim
    }

    /** Up to three "What worked" sentences: the report's, or the live strengths in plain words. */
    fun worked(report: AfterActionReport?, result: CompositionResult): List<String> =
        (report?.worked ?: result.strengths.map(AfterActionReport::plainStrength).distinct())
            .take(AfterActionReport.MAX_WORKED)

    /** The single amber line, or null when the report has nothing to suggest (or does not exist yet). */
    fun tryNext(report: AfterActionReport?): TryNextLine? {
        val next = report?.tryNextTime ?: return null
        return TryNextLine(text = stripGain(next.sentence), gain = next.predictedGain.coerceAtLeast(1))
    }

    /** "A step to the left would have cleared the pole (+9)" -> "A step to the left would have cleared the pole". */
    fun stripGain(sentence: String): String = sentence.replace(TRAILING_GAIN, "").trim()

    /** "Nothing to change. Keep this one." once analysis is done and found nothing to say. */
    fun nothingToChange(entry: ReviewEntry): Boolean =
        !entry.analysing && entry.report != null && entry.report.tryNextTime == null && entry.crops.isEmpty()

    /** A Try-next-time or a crop: what `ReviewCardMode.ONLY_WHEN_USEFUL` waits for. */
    fun hasSomethingToSay(entry: ReviewEntry): Boolean = entry.report?.tryNextTime != null || entry.crops.isNotEmpty()

    /**
     * Whether the full card shows. ALWAYS (default): yes. ONLY_WHEN_USEFUL: only once there is a Try-next-time or
     * a crop, so a clean shot gets the photo and the three actions and nothing else.
     */
    fun showCard(mode: ReviewCardMode, entry: ReviewEntry): Boolean = when (mode) {
        ReviewCardMode.ALWAYS -> true
        ReviewCardMode.ONLY_WHEN_USEFUL -> hasSomethingToSay(entry)
    }

    /**
     * The "What if" score pair ("72 → 83"): this photo's score and the optimizer's best framing, or null when
     * there is no what-if crop. Falls back to score + the optimizer-derived Try-next-time gain.
     */
    fun whatIfScores(entry: ReviewEntry): Pair<Int, Int>? {
        if (entry.whatIf == null) return null
        val result = resultOf(entry)
        val predicted = result.optimization?.best?.predictedScore?.roundToInt()
            ?: entry.report?.tryNextTime?.takeIf { it.fromOptimizer }?.let { result.score + it.predictedGain }
            ?: return null
        return result.score to predicted.coerceIn(0, 100)
    }

    /** The Details list of every applicable measure, best last so the weakest reads first. */
    fun metricLines(result: CompositionResult): List<Pair<String, Int>> =
        result.metrics.filter { it.applicable }
            .sortedBy { it.score }
            .map { categoryLabel(it.category) to (it.score.coerceIn(0f, 1f) * 100).roundToInt() }

    fun categoryLabel(category: MetricCategory): String =
        category.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

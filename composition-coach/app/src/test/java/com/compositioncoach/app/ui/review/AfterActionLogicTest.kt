package com.compositioncoach.app.ui.review

import android.net.Uri
import com.compositioncoach.app.R
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.outcome.ReviewCardMode
import com.compositioncoach.composition.engine.AfterActionReport
import com.compositioncoach.composition.engine.TryNextTime
import com.compositioncoach.composition.engine.Verdict
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.CropAspect
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.FramingCandidate
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.OptimizationResult
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verdict and wording helpers, the card-mode rule and the What-if pair. Robolectric only for a real [Uri]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AfterActionLogicTest {

    private val uri = Uri.parse("content://test/1")
    private fun result(score: Int, strengths: List<String> = emptyList()) = CompositionResult.empty().copy(score = score, strengths = strengths)
    private val tryNext = TryNextTime("A step to the left would have cleared the pole behind them (+9)", 9, Direction.LEFT, MetricCategory.BACKGROUND_DISTRACTION)
    private fun report(verdict: Verdict = Verdict.GOOD, next: TryNextTime? = tryNext) = AfterActionReport(verdict, listOf("Level horizon"), next, null)
    private fun entry(score: Int = 70, report: AfterActionReport? = null, crops: List<CropSuggestion> = emptyList(), analysing: Boolean = false, whatIf: NormalizedRect? = null) =
        ReviewEntry(photoUri = uri, result = result(score), report = report, crops = crops, analysing = analysing, whatIf = whatIf)
    private val crop = CropSuggestion(NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f), CropAspect.SQUARE, 81, "Square")

    @Test
    fun `the verdict waits for nothing, computed from the live score until the report arrives`() {
        assertEquals(Verdict.KEEPER, AfterActionLogic.verdict(null, result(85)))
        assertEquals(Verdict.GOOD, AfterActionLogic.verdict(null, result(65)))
        assertEquals(Verdict.TRY_AGAIN, AfterActionLogic.verdict(null, result(40)))
        assertEquals(Verdict.TRY_AGAIN, AfterActionLogic.verdict(report(Verdict.TRY_AGAIN), result(85)))
    }

    @Test
    fun `a HIGH severity problem caps the live verdict at Good`() {
        val high = CompositionMetric(MetricCategory.HORIZON, "h", 0.2f, 0.9f, severity = Severity.HIGH)
        assertEquals(Verdict.GOOD, AfterActionLogic.verdict(null, result(90).copy(metrics = listOf(high))))
    }

    @Test
    fun `verdict words come from resources, Keeper green, Try again never red`() {
        assertEquals(R.string.verdict_keeper, AfterActionLogic.verdictRes(Verdict.KEEPER))
        assertEquals(R.string.verdict_try_again, AfterActionLogic.verdictRes(Verdict.TRY_AGAIN))
    }

    @Test
    fun `what worked is at most three plain sentences`() {
        val live = result(70, listOf("Good looking room", "Level horizon", "Nice light", "Fourth"))
        assertEquals(listOf("Space in front of their face", "Level horizon", "Nice light"), AfterActionLogic.worked(null, live))
        assertEquals(listOf("Level horizon"), AfterActionLogic.worked(report(), live))
    }

    @Test
    fun `try next time is one line with the gain split out`() {
        val line = AfterActionLogic.tryNext(report())!!
        assertEquals("A step to the left would have cleared the pole behind them", line.text)
        assertEquals(9, line.gain)
        assertNull(AfterActionLogic.tryNext(report(next = null)))
        assertNull(AfterActionLogic.tryNext(null))
        assertEquals("No gain here", AfterActionLogic.stripGain("No gain here"))
    }

    @Test
    fun `ALWAYS shows the card, ONLY_WHEN_USEFUL waits for a try-next-time or a crop`() {
        val clean = entry(report = report(next = null))
        assertTrue(AfterActionLogic.showCard(ReviewCardMode.ALWAYS, clean))
        assertFalse(AfterActionLogic.showCard(ReviewCardMode.ONLY_WHEN_USEFUL, clean))
        assertFalse(AfterActionLogic.showCard(ReviewCardMode.ONLY_WHEN_USEFUL, entry(analysing = true)))
        assertTrue(AfterActionLogic.showCard(ReviewCardMode.ONLY_WHEN_USEFUL, entry(report = report())))
        assertTrue(AfterActionLogic.showCard(ReviewCardMode.ONLY_WHEN_USEFUL, clean.copy(crops = listOf(crop))))
    }

    @Test
    fun `nothing to change only once analysis has finished and found nothing`() {
        assertTrue(AfterActionLogic.nothingToChange(entry(report = report(next = null))))
        assertFalse(AfterActionLogic.nothingToChange(entry(report = report(next = null), analysing = true)))
        assertFalse(AfterActionLogic.nothingToChange(entry(report = report(next = null), crops = listOf(crop))))
        assertFalse(AfterActionLogic.nothingToChange(entry()))
    }

    @Test
    fun `what if pairs this score with the optimizer's best`() {
        val best = FramingCandidate("left", ReframeVector(dx = -0.1f), 83.4f)
        val withOpt = entry(score = 72, whatIf = NormalizedRect(0f, 0f, 0.8f, 0.8f))
            .let { it.copy(result = it.result.copy(optimization = OptimizationResult(72f, listOf(best), best))) }
        assertEquals(72 to 83, AfterActionLogic.whatIfScores(withOpt))
        assertNull(AfterActionLogic.whatIfScores(entry(score = 72)))
        val fromReport = entry(score = 70, whatIf = NormalizedRect(0f, 0f, 0.8f, 0.8f), report = report(next = tryNext.copy(fromOptimizer = true)))
        assertEquals(70 to 79, AfterActionLogic.whatIfScores(fromReport))
    }

    @Test
    fun `details list the weakest measure first, as percentages`() {
        val metrics = listOf(
            CompositionMetric(MetricCategory.HORIZON, "h", 0.9f, 1f),
            CompositionMetric(MetricCategory.SUBJECT_PLACEMENT, "p", 0.42f, 1f),
            CompositionMetric(MetricCategory.LIGHT, "l", 0.1f, 1f, applicable = false),
        )
        assertEquals(listOf("Subject placement" to 42, "Horizon" to 90), AfterActionLogic.metricLines(result(70).copy(metrics = metrics)))
    }
}

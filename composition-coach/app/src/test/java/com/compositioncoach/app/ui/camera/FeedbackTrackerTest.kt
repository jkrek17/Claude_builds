package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.ui.camera.FeedbackTracker.Cue
import com.compositioncoach.composition.engine.AfterActionReport
import com.compositioncoach.composition.engine.Feedback
import com.compositioncoach.composition.engine.TryNextTime
import com.compositioncoach.composition.engine.Verdict
import com.compositioncoach.composition.model.CropAspect
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackTrackerTest {

    private val horizon = Cue("horizon.tilt", MetricCategory.HORIZON)
    private val placement = Cue("placement.thirds", MetricCategory.SUBJECT_PLACEMENT)
    private val t = FeedbackTracker()

    private fun frame(now: Long, headline: Cue?, vararg active: Cue, subject: Boolean = true): Feedback? =
        t.onFrame(now, headline, (listOfNotNull(headline) + active).map { it.id }.toSet(), subject)

    @Test
    fun `a headline cue that resolves within 6 s while the subject stays is FOLLOWED`() {
        assertNull(frame(0, horizon))
        assertNull(frame(3_000, horizon))
        assertEquals(Feedback.FOLLOWED(MetricCategory.HORIZON), frame(5_900, null))
    }

    @Test
    fun `a cue that lingers past the window is not credited`() {
        frame(0, horizon)
        assertNull(frame(6_100, null))
    }

    @Test
    fun `a cue that disappears because the subject left is not credited`() {
        frame(0, horizon)
        assertNull(frame(1_000, null, subject = false))
        assertNull(frame(1_100, null))
    }

    @Test
    fun `a demoted cue still active below the headline is not followed, and the window restarts per headline`() {
        frame(0, horizon)
        // Placement takes over the headline while horizon is still active: horizon is no longer shown.
        assertNull(frame(1_000, placement, horizon))
        assertNull(frame(2_000, placement))
        // Placement resolves 5 s after *it* became the headline.
        assertEquals(Feedback.FOLLOWED(MetricCategory.SUBJECT_PLACEMENT), frame(6_000, null))
    }

    @Test
    fun `each showing is credited at most once`() {
        frame(0, horizon)
        assertEquals(Feedback.FOLLOWED(MetricCategory.HORIZON), frame(1_000, null))
        assertNull(frame(1_100, null))
        // Shown again later, it is a new showing.
        frame(2_000, horizon)
        assertEquals(Feedback.FOLLOWED(MetricCategory.HORIZON), frame(3_000, null))
    }

    @Test
    fun `capture reports the cue showing and the cues followed since the last capture`() {
        frame(0, horizon)
        frame(1_000, null)
        frame(2_000, placement)
        val (cue, followed) = t.onCapture()
        assertEquals(placement, cue)
        assertEquals(listOf(MetricCategory.HORIZON), followed)
        assertEquals(emptyList<MetricCategory>(), t.onCapture().second)
    }

    @Test
    fun `capture interrupts tracking, so the cue resolving after the shutter is not FOLLOWED`() {
        frame(0, horizon)
        t.onCapture()
        assertNull(frame(500, null))
    }

    @Test
    fun `reset forgets the cue in flight`() {
        frame(0, horizon)
        t.reset()
        assertNull(frame(500, null))
        assertNull(t.onCapture().first)
    }

    @Test
    fun `review decisions with a cue at capture`() {
        assertEquals(Feedback.IGNORED_AND_KEPT(MetricCategory.HORIZON), FeedbackTracker.reviewDecision(MetricCategory.HORIZON, kept = true))
        assertEquals(Feedback.IGNORED_AND_RETAKEN(MetricCategory.HORIZON), FeedbackTracker.reviewDecision(MetricCategory.HORIZON, kept = false))
        assertNull(FeedbackTracker.reviewDecision(null, kept = true))
    }

    @Test
    fun `not helpful uses the try-next-time category`() {
        val report = AfterActionReport(
            Verdict.GOOD,
            emptyList(),
            TryNextTime("A step to the left would have helped (+5)", 5, Direction.LEFT, MetricCategory.BACKGROUND_DISTRACTION),
            null,
        )
        assertEquals(Feedback.NOT_HELPFUL(MetricCategory.BACKGROUND_DISTRACTION), FeedbackTracker.notHelpful(report))
        assertNull(FeedbackTracker.notHelpful(report.copy(tryNextTime = null)))
        assertNull(FeedbackTracker.notHelpful(null))
    }

    @Test
    fun `a chosen crop records its aspect and whether it was tight`() {
        val tight = CropSuggestion(NormalizedRect(0.15f, 0.15f, 0.85f, 0.85f), CropAspect.SQUARE, 80, "Square")
        val loose = CropSuggestion(NormalizedRect(0.05f, 0.05f, 0.9f, 0.95f), CropAspect.ORIGINAL, 75, "Tighter")
        assertEquals(Feedback.CROP_CHOSEN(CropAspect.SQUARE, tighter = true), FeedbackTracker.cropChosen(tight))
        assertEquals(Feedback.CROP_CHOSEN(CropAspect.ORIGINAL, tighter = false), FeedbackTracker.cropChosen(loose))
    }
}

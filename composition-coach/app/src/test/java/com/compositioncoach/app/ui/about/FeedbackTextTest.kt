package com.compositioncoach.app.ui.about

import android.net.Uri
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.composition.engine.AfterActionReport
import com.compositioncoach.composition.engine.TryNextTime
import com.compositioncoach.composition.engine.Verdict
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Send feedback" text: the version, then the last card as plain text. Nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackTextTest {

    @Test
    fun `without a card it says so`() {
        assertEquals("Version: 1.0\n\nNo card.", FeedbackText.build("1.0", null, "No card."))
    }

    @Test
    fun `the last card reads as plain lines`() {
        val entry = ReviewEntry(
            photoUri = Uri.parse("content://p/1"),
            result = CompositionResult.empty().copy(score = 74),
            outcome = Outcome.PORTRAIT,
            report = AfterActionReport(
                Verdict.GOOD,
                listOf("Level horizon"),
                TryNextTime("Moving a little closer would have helped (+6)", 6, Direction.CLOSER, MetricCategory.NEGATIVE_SPACE),
                null,
            ),
        )
        val text = FeedbackText.build("1.0", entry, "No card.")
        assertTrue(text, text.contains("Last card: Good · 74"))
        assertTrue(text, text.contains("Outcome: A person"))
        assertTrue(text, text.contains("Worked: Level horizon"))
        assertTrue(text, text.contains("Try next time: Moving a little closer would have helped (+6)"))
    }
}

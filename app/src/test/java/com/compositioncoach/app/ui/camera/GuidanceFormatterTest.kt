package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuidanceFormatterTest {

    private fun rec(
        direction: Direction = Direction.RIGHT,
        instruction: String = "Move slightly right",
        reason: String? = "Keeps the subject off-center for a stronger composition",
    ) = Recommendation(
        id = "test.rec",
        category = MetricCategory.SUBJECT_PLACEMENT,
        priority = Priority.HIGH,
        confidence = 0.9f,
        severity = Severity.MEDIUM,
        title = "Reframe",
        instruction = instruction,
        reason = reason,
        direction = direction,
    )

    @Test
    fun `glyphFor maps every direction to its expected glyph`() {
        assertEquals("←", GuidanceFormatter.glyphFor(Direction.LEFT))
        assertEquals("→", GuidanceFormatter.glyphFor(Direction.RIGHT))
        assertEquals("↑", GuidanceFormatter.glyphFor(Direction.UP))
        assertEquals("↓", GuidanceFormatter.glyphFor(Direction.DOWN))
        assertEquals("↻", GuidanceFormatter.glyphFor(Direction.ROTATE_CLOCKWISE))
        assertEquals("↺", GuidanceFormatter.glyphFor(Direction.ROTATE_COUNTER_CLOCKWISE))
        assertEquals("", GuidanceFormatter.glyphFor(Direction.NONE))
    }

    @Test
    fun `primaryLine combines the glyph and the instruction`() {
        val line = GuidanceFormatter.primaryLine(rec(direction = Direction.RIGHT, instruction = "Move slightly right"))
        assertEquals("→ Move slightly right", line)
    }

    @Test
    fun `primaryLine omits the glyph entirely when direction is NONE`() {
        val line = GuidanceFormatter.primaryLine(rec(direction = Direction.NONE, instruction = "Hold steady"))
        assertEquals("Hold steady", line)
    }

    @Test
    fun `reasonLine is shown only at COACH level`() {
        val recommendation = rec(reason = "Because the horizon is tilted")
        assertEquals("Because the horizon is tilted", GuidanceFormatter.reasonLine(recommendation, GuidanceLevel.COACH))
        assertNull(GuidanceFormatter.reasonLine(recommendation, GuidanceLevel.BALANCED))
        assertNull(GuidanceFormatter.reasonLine(recommendation, GuidanceLevel.MINIMAL))
    }

    @Test
    fun `reasonLine is null at COACH level when there is no reason`() {
        assertNull(GuidanceFormatter.reasonLine(rec(reason = null), GuidanceLevel.COACH))
    }

    @Test
    fun `score tiers follow the documented thresholds`() {
        assertEquals(ScoreTier.LOW, GuidanceFormatter.scoreTier(0))
        assertEquals(ScoreTier.LOW, GuidanceFormatter.scoreTier(69))
        assertEquals(ScoreTier.GOOD, GuidanceFormatter.scoreTier(70))
        assertEquals(ScoreTier.GOOD, GuidanceFormatter.scoreTier(87))
        assertEquals(ScoreTier.EXCELLENT, GuidanceFormatter.scoreTier(88))
        assertEquals(ScoreTier.EXCELLENT, GuidanceFormatter.scoreTier(100))
    }

    @Test
    fun `shootReadyScoreText formats the em-dash SHOOT suffix`() {
        assertEquals("94 — SHOOT", GuidanceFormatter.shootReadyScoreText(94))
    }

    @Test
    fun `badgeAlpha dims the score only while awaiting a subject`() {
        assertEquals(GuidanceFormatter.AWAITING_SUBJECT_ALPHA, GuidanceFormatter.badgeAlpha(awaitingSubject = true))
        assertEquals(1f, GuidanceFormatter.badgeAlpha(awaitingSubject = false))
    }

    @Test
    fun `awaitingSubjectTitleLine surfaces the find-subject recommendation's title`() {
        val findSubject = rec().copy(id = "intent.no_subject", title = "Looking for a face", instruction = "Move closer to your subject")
        assertEquals("Looking for a face", GuidanceFormatter.awaitingSubjectTitleLine(findSubject))
    }

    @Test
    fun `awaitingSubjectHeadline surfaces the find-subject recommendation's instruction, not its title`() {
        val findSubject = rec().copy(id = "intent.no_subject", title = "Looking for a face", instruction = "Move closer to your subject")
        assertEquals("Move closer to your subject", GuidanceFormatter.awaitingSubjectHeadline(findSubject))
    }

    @Test
    fun `hold framing hint appears only for decent scores`() {
        org.junit.Assert.assertTrue(GuidanceFormatter.showsHoldFramingHint(70))
        org.junit.Assert.assertTrue(GuidanceFormatter.showsHoldFramingHint(85))
        org.junit.Assert.assertFalse(GuidanceFormatter.showsHoldFramingHint(69))
    }
}

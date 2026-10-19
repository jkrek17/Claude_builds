package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the chip wording table and the guidance-level behaviour table from `docs/COACHING_UI.md`. Both are
 * specification, not implementation detail: the words on screen are the whole of the app's live text, and
 * the level table is what "Minimal" actually means.
 */
class GuidanceFormatterTest {

    private fun rec(
        direction: Direction = Direction.RIGHT,
        category: MetricCategory = MetricCategory.SUBJECT_PLACEMENT,
        title: String = "Move slightly right",
        instruction: String = "Move slightly right",
        severity: Severity = Severity.MEDIUM,
    ) = Recommendation(
        id = "test.rec",
        category = category,
        priority = Priority.HIGH,
        confidence = 0.9f,
        severity = severity,
        title = title,
        instruction = instruction,
        direction = direction,
    )

    // --- Chip wording ----------------------------------------------------------------------------------

    @Test
    fun `directional chips use the spec's exact wording`() {
        assertEquals("Slightly right", GuidanceFormatter.chipText(rec(direction = Direction.RIGHT)))
        assertEquals("Slightly left", GuidanceFormatter.chipText(rec(direction = Direction.LEFT)))
        assertEquals("Raise", GuidanceFormatter.chipText(rec(direction = Direction.UP)))
        assertEquals("Lower", GuidanceFormatter.chipText(rec(direction = Direction.DOWN)))
        assertEquals("Closer", GuidanceFormatter.chipText(rec(direction = Direction.CLOSER)))
        assertEquals("Step back", GuidanceFormatter.chipText(rec(direction = Direction.BACK)))
        assertEquals("Level", GuidanceFormatter.chipText(rec(direction = Direction.ROTATE_CLOCKWISE)))
        assertEquals("Level", GuidanceFormatter.chipText(rec(direction = Direction.ROTATE_COUNTER_CLOCKWISE)))
    }

    @Test
    fun `directionless chips fall back to the category's wording`() {
        fun chip(category: MetricCategory) =
            GuidanceFormatter.chipText(rec(direction = Direction.NONE, category = category))

        assertEquals("Level", chip(MetricCategory.HORIZON))
        assertEquals("Clear background", chip(MetricCategory.BACKGROUND_DISTRACTION))
        assertEquals("Plainer background", chip(MetricCategory.SUBJECT_SEPARATION))
        assertEquals("Give looking room", chip(MetricCategory.LOOKING_ROOM))
        assertEquals("Too close to edge", chip(MetricCategory.EDGE_TENSION))
        assertEquals("Someone's cut off", chip(MetricCategory.CROPPING))
        assertEquals("Center it", chip(MetricCategory.SYMMETRY))
    }

    @Test
    fun `direction wins over category when a recommendation has both`() {
        // A cropping recommendation that also knows which way to move says the movement, not the problem:
        // the chip labels the chevron that is already on screen.
        val cropWithDirection = rec(direction = Direction.BACK, category = MetricCategory.CROPPING)
        assertEquals("Step back", GuidanceFormatter.chipText(cropWithDirection))
    }

    @Test
    fun `a category outside the table degrades to at most three words of the title`() {
        val exotic = rec(
            direction = Direction.NONE,
            category = MetricCategory.LEADING_LINES,
            title = "Follow the diagonal line into frame",
        )
        assertEquals("Follow the diagonal", GuidanceFormatter.chipText(exotic))
    }

    @Test
    fun `every chip is either verbatim from the spec's table or within the fallback's word budget`() {
        // The table is the spec's own wording, verbatim, however long a phrase it chose ("Too close to
        // edge" is four words). Anything *not* in the table is the title fallback, which the spec's
        // "1-3 words" rule does bind.
        val specTable = setOf(
            "Slightly right", "Slightly left", "Raise", "Lower", "Closer", "Step back", "Level",
            "Clear background", "Give looking room", "Too close to edge", "Someone's cut off",
            "Plainer background", "Center it",
        )
        for (category in MetricCategory.entries) {
            for (direction in Direction.entries) {
                val text = GuidanceFormatter.chipText(rec(direction = direction, category = category))
                if (text in specTable) continue
                val words = text.split(" ").size
                assertTrue("\"$text\" ($category/$direction) is $words words, over the 3-word budget", words <= 3)
            }
        }
    }

    @Test
    fun `awaiting-subject chip is the find-subject recommendation's title`() {
        val findSubject = rec().copy(title = "Looking for a face", instruction = "Move closer to your subject")
        assertEquals("Looking for a face", GuidanceFormatter.awaitingSubjectChipText(findSubject))
        assertEquals(GuidanceFormatter.LOOKING_FOR_SUBJECT, GuidanceFormatter.awaitingSubjectChipText(null))
    }

    @Test
    fun `TalkBack hears a spoken sentence, not the abbreviated chip`() {
        assertEquals("Move slightly right", GuidanceFormatter.chipDescription(rec(direction = Direction.RIGHT)))
        assertEquals("Raise the camera", GuidanceFormatter.chipDescription(rec(direction = Direction.UP)))
        assertEquals(
            "Reframe to clear the pole",
            GuidanceFormatter.chipDescription(
                rec(direction = Direction.NONE, instruction = "Reframe to clear the pole"),
            ),
        )
    }

    @Test
    fun `score chip announces the spec's phrasing`() {
        assertEquals("Composition score 82", GuidanceFormatter.scoreAnnouncement(82))
    }

    // --- Cue selection ---------------------------------------------------------------------------------

    @Test
    fun `each direction earns the cue the spec's table gives it`() {
        assertEquals(PrimaryCue.EdgeChevron(Direction.RIGHT), GuidanceFormatter.cueFor(rec(direction = Direction.RIGHT)))
        assertEquals(PrimaryCue.EdgeChevron(Direction.UP), GuidanceFormatter.cueFor(rec(direction = Direction.UP)))
        assertEquals(PrimaryCue.CornerBrackets(inward = true), GuidanceFormatter.cueFor(rec(direction = Direction.CLOSER)))
        assertEquals(PrimaryCue.CornerBrackets(inward = false), GuidanceFormatter.cueFor(rec(direction = Direction.BACK)))
        assertEquals(PrimaryCue.BubbleLevel, GuidanceFormatter.cueFor(rec(direction = Direction.ROTATE_CLOCKWISE)))
        assertEquals(
            PrimaryCue.BubbleLevel,
            GuidanceFormatter.cueFor(rec(direction = Direction.ROTATE_COUNTER_CLOCKWISE)),
        )
    }

    @Test
    fun `directionless advice is chip-only, except a horizon which still gets the level`() {
        assertEquals(
            PrimaryCue.ChipOnly,
            GuidanceFormatter.cueFor(rec(direction = Direction.NONE, category = MetricCategory.SUBJECT_SEPARATION)),
        )
        assertEquals(
            PrimaryCue.ChipOnly,
            GuidanceFormatter.cueFor(rec(direction = Direction.NONE, category = MetricCategory.SYMMETRY)),
        )
        assertEquals(
            PrimaryCue.BubbleLevel,
            GuidanceFormatter.cueFor(rec(direction = Direction.NONE, category = MetricCategory.HORIZON)),
        )
    }

    // --- Guidance level behaviour ----------------------------------------------------------------------

    @Test
    fun `minimal only speaks for medium or worse`() {
        val low = listOf(rec(severity = Severity.LOW))
        val medium = listOf(rec(severity = Severity.MEDIUM))
        val high = listOf(rec(severity = Severity.HIGH))

        assertNull(GuidanceFormatter.headline(low, GuidanceLevel.MINIMAL))
        assertEquals(medium.first(), GuidanceFormatter.headline(medium, GuidanceLevel.MINIMAL))
        assertEquals(high.first(), GuidanceFormatter.headline(high, GuidanceLevel.MINIMAL))
    }

    @Test
    fun `balanced and coach show the headline whatever its severity`() {
        val low = listOf(rec(severity = Severity.LOW))
        assertEquals(low.first(), GuidanceFormatter.headline(low, GuidanceLevel.BALANCED))
        assertEquals(low.first(), GuidanceFormatter.headline(low, GuidanceLevel.COACH))
    }

    @Test
    fun `no recommendations means no headline at any level`() {
        GuidanceLevel.entries.forEach { level ->
            assertNull(GuidanceFormatter.headline(emptyList(), level))
        }
    }

    @Test
    fun `secondary icon budget is none, one, two`() {
        assertEquals(0, GuidanceFormatter.secondaryIconCount(GuidanceLevel.MINIMAL))
        assertEquals(1, GuidanceFormatter.secondaryIconCount(GuidanceLevel.BALANCED))
        assertEquals(2, GuidanceFormatter.secondaryIconCount(GuidanceLevel.COACH))
    }

    @Test
    fun `secondary trims the list and never includes the headline itself`() {
        val all = listOf(rec(), rec().copy(id = "b"), rec().copy(id = "c"), rec().copy(id = "d"))
        assertTrue(GuidanceFormatter.secondary(all, GuidanceLevel.MINIMAL).isEmpty())
        assertEquals(listOf("b"), GuidanceFormatter.secondary(all, GuidanceLevel.BALANCED).map { it.id })
        assertEquals(listOf("b", "c"), GuidanceFormatter.secondary(all, GuidanceLevel.COACH).map { it.id })
    }

    @Test
    fun `only coach's secondary icons explain themselves when tapped`() {
        assertFalse(GuidanceFormatter.secondaryIconsTappable(GuidanceLevel.MINIMAL))
        assertFalse(GuidanceFormatter.secondaryIconsTappable(GuidanceLevel.BALANCED))
        assertTrue(GuidanceFormatter.secondaryIconsTappable(GuidanceLevel.COACH))
    }

    @Test
    fun `the tapped explanation is the recommendation's own instruction sentence`() {
        val recommendation = rec(instruction = "Move so the pole isn't behind your subject")
        assertEquals("Move so the pole isn't behind your subject", GuidanceFormatter.explanationText(recommendation))
    }

    // --- States ----------------------------------------------------------------------------------------

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
    fun `shoot-ready styling never shows while awaiting a subject`() {
        assertTrue(GuidanceFormatter.effectiveShootReady(isShootReady = true, awaitingSubject = false))
        assertFalse(GuidanceFormatter.effectiveShootReady(isShootReady = true, awaitingSubject = true))
        assertFalse(GuidanceFormatter.effectiveShootReady(isShootReady = false, awaitingSubject = false))
    }

    @Test
    fun `the quiet check mark needs a scene, no advice, and an already-good score`() {
        assertTrue(GuidanceFormatter.showsQuietCheck(hasHeadline = false, displayScore = 74, isShootReady = false, hasScene = true))
        // Still something to fix.
        assertFalse(GuidanceFormatter.showsQuietCheck(hasHeadline = true, displayScore = 74, isShootReady = false, hasScene = true))
        // Not good enough yet: there is always advice below 70, so this state shouldn't be reachable.
        assertFalse(GuidanceFormatter.showsQuietCheck(hasHeadline = false, displayScore = 69, isShootReady = false, hasScene = true))
        // Shoot-ready speaks through the shutter's green ring instead.
        assertFalse(GuidanceFormatter.showsQuietCheck(hasHeadline = false, displayScore = 95, isShootReady = true, hasScene = true))
        assertFalse(GuidanceFormatter.showsQuietCheck(hasHeadline = false, displayScore = 74, isShootReady = false, hasScene = false))
    }
}

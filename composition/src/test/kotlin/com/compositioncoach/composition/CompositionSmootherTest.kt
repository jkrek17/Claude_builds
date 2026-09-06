package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionSmoother
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CompositionSmootherTest {

    private fun recommendation(id: String, direction: Direction) = Recommendation(
        id = id,
        category = MetricCategory.SUBJECT_PLACEMENT,
        priority = Priority.MEDIUM,
        confidence = 0.8f,
        severity = Severity.MEDIUM,
        title = "t",
        instruction = "i",
        direction = direction,
    )

    private fun resultWith(recommendation: Recommendation?, score: Float): CompositionResult =
        CompositionResult.empty().copy(
            rawScore = score,
            score = Math.round(score),
            recommendations = listOfNotNull(recommendation),
            subjects = listOf(), // constant subject count (0) across updates unless a test overrides it
        )

    @Test
    fun `alternating left-right advice does not flip the displayed recommendation every frame`() {
        val smoother = CompositionSmoother()
        val left = recommendation("left", Direction.LEFT)
        val right = recommendation("right", Direction.RIGHT)

        val displayedIds = mutableListOf<String?>()
        repeat(20) { i ->
            val raw = if (i % 2 == 0) left else right
            val smoothed = smoother.update(resultWith(raw, 70f))
            displayedIds += smoothed.primaryRecommendation?.id
        }

        // The raw advice alternated every single frame; the displayed one must not have.
        val changes = displayedIds.zipWithNext().count { (a, b) -> a != b }
        assertTrue("displayed advice changed too often: $displayedIds", changes < 5)
    }

    @Test
    fun `score EMA converges toward a steady raw score`() {
        val smoother = CompositionSmoother()
        var last = 0
        repeat(30) {
            last = smoother.update(resultWith(null, 90f)).displayScore
        }
        assertTrue("expected score to converge near 90, was $last", abs(last - 90) <= 1)
    }

    @Test
    fun `a stable recommendation is held for at least the minimum frames even if briefly challenged`() {
        val smoother = CompositionSmoother()
        val steady = recommendation("steady", Direction.LEFT)
        val challenger = recommendation("challenger", Direction.UP)

        // Establish "steady" as current.
        smoother.update(resultWith(steady, 70f))
        // A single-frame challenge should not be enough to replace it (needs sustained confirmation).
        val afterOneChallenge = smoother.update(resultWith(challenger, 70f))
        assertEquals("steady", afterOneChallenge.primaryRecommendation?.id)
    }

    @Test
    fun `shoot ready needs a sustained high score and has hysteresis on exit`() {
        val smoother = CompositionSmoother()
        // Score EMA starts equal to the first raw score; a single high frame must not flash SHOOT.
        val first = smoother.update(resultWith(null, 95f))
        assertTrue(!first.isShootReady)
        // ~0.5 s of sustained high score (nominal 100 ms per update) lights it up.
        var ready = first
        repeat(6) { ready = smoother.update(resultWith(null, 95f)) }
        assertTrue(ready.isShootReady)

        // A small dip that stays above the exit threshold should not turn shoot-ready off.
        val stillReady = smoother.update(resultWith(null, 90f))
        assertTrue(stillReady.isShootReady)
    }

    @Test
    fun `displayed score ignores small jitter and holds between changes`() {
        val smoother = CompositionSmoother()
        val shown = mutableListOf<Int>()
        // Raw score jitters by a couple of points every frame, as detections wobble on a still subject.
        repeat(40) { i -> shown += smoother.update(resultWith(null, if (i % 2 == 0) 71f else 73f)).displayScore }
        val changes = shown.zipWithNext().count { (a, b) -> a != b }
        assertTrue("displayed score changed $changes times for jitter: $shown", changes <= 1)
    }

    @Test
    fun `a large real change shows quickly`() {
        val smoother = CompositionSmoother()
        repeat(5) { smoother.update(resultWith(null, 40f)) }
        var shown = 40
        repeat(15) { shown = smoother.update(resultWith(null, 90f)).displayScore } // 1.5 s at nominal rate
        assertTrue("expected the display to move well toward 90 within 1.5 s, was $shown", shown >= 65)
    }

    @Test
    fun `advice swaps only after the challenger has been consistent for the confirm time`() {
        val smoother = CompositionSmoother()
        val steady = recommendation("steady", Direction.LEFT)
        val challenger = recommendation("challenger", Direction.UP)
        repeat(25) { smoother.update(resultWith(steady, 70f)) } // 2.5 s: past the minimum hold
        var shownId: String? = null
        repeat(5) { shownId = smoother.update(resultWith(challenger, 70f)).primaryRecommendation?.id } // 0.5 s
        assertEquals("steady", shownId)
        repeat(5) { shownId = smoother.update(resultWith(challenger, 70f)).primaryRecommendation?.id } // 1.0 s total
        assertEquals("challenger", shownId)
    }

    private fun resultWithSubject(recommendation: Recommendation?, score: Float): CompositionResult =
        resultWith(recommendation, score).copy(
            subjects = listOf(
                com.compositioncoach.composition.model.DetectedSubject(
                    id = 0,
                    kind = com.compositioncoach.composition.model.SubjectKind.FACE,
                    bounds = com.compositioncoach.composition.model.NormalizedRect(0.4f, 0.3f, 0.6f, 0.5f),
                ),
            ),
        )

    @Test
    fun `advice is dropped once its issue is gone and it has been readable long enough`() {
        val smoother = CompositionSmoother()
        val steady = recommendation("steady", Direction.LEFT)
        repeat(3) { smoother.update(resultWithSubject(steady, 70f)) } // shown 0.3 s
        var shownId: String? = "steady"
        // Issue gone but the subject is still visible: must stay until it has been shown 1.5 s...
        repeat(10) { shownId = smoother.update(resultWithSubject(null, 80f)).primaryRecommendation?.id } // 1.3 s total
        assertEquals("steady", shownId)
        // ...then drop (issue absent >= 1.2 s and shown >= 1.5 s).
        repeat(4) { shownId = smoother.update(resultWithSubject(null, 80f)).primaryRecommendation?.id }
        assertEquals(null, shownId)
    }

    @Test
    fun `losing the subject briefly does not drop subject advice`() {
        val smoother = CompositionSmoother()
        val steady = recommendation("steady", Direction.LEFT)
        repeat(20) { smoother.update(resultWithSubject(steady, 70f)) }
        var shownId: String? = null
        // Detector blink: 1.5 s of frames with no subject at all.
        repeat(15) { shownId = smoother.update(resultWith(null, 70f)).primaryRecommendation?.id }
        assertEquals("steady", shownId)
        // Subject gone for good: dropped after 2.5 s without a subject.
        repeat(12) { shownId = smoother.update(resultWith(null, 70f)).primaryRecommendation?.id }
        assertEquals(null, shownId)
    }

    @Test
    fun `reset clears all smoother state`() {
        val smoother = CompositionSmoother()
        smoother.update(resultWith(recommendation("a", Direction.LEFT), 95f))
        smoother.reset()
        val afterReset = smoother.update(resultWith(null, 10f))
        assertEquals(10, afterReset.displayScore)
        assertTrue(!afterReset.isShootReady)
    }
}

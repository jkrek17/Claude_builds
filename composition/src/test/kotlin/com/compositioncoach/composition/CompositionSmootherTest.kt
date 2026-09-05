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
        // A single-frame challenge should not be enough to replace it (needs 3 consecutive confirmations).
        val afterOneChallenge = smoother.update(resultWith(challenger, 70f))
        assertEquals("steady", afterOneChallenge.primaryRecommendation?.id)
    }

    @Test
    fun `shoot ready has hysteresis around the enter and exit thresholds`() {
        val smoother = CompositionSmoother()
        // Score EMA starts equal to the first raw score, so jump straight to a high value.
        val ready = smoother.update(resultWith(null, 95f))
        assertTrue(ready.isShootReady)

        // A small dip that stays above the exit threshold should not turn shoot-ready off.
        val stillReady = smoother.update(resultWith(null, 90f))
        assertTrue(stillReady.isShootReady)
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

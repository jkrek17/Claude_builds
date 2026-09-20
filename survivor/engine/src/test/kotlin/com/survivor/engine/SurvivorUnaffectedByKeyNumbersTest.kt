package com.survivor.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The key-number margin model ([KeyNumbers]) is used only inside [BettingEngine]'s SPREAD market pricing.
 * Survivor win probabilities ([ProbabilityResolver], [Evaluator], [Optimizer], ...) must keep using
 * [Probability.winProbabilityFromSpread]'s smooth normal curve (sigma = 11.0) exactly as before.
 */
class SurvivorUnaffectedByKeyNumbersTest {
    private val kickoff = TestSeason.WEEK1_KICKOFF
    private val now = kickoff - 86_400_000L

    @Test fun `a spread-sourced win probability still comes from the plain normal curve, not KeyNumbers`() {
        val line = MarketLine("DraftKings", homeSpread = -3.0, fetchedAtEpochMs = 1L)
        val game = Game(id = "g1", week = 1, home = Team.SEA, away = Team.NE, kickoffEpochMs = kickoff, line = line)
        val settings = ModelSettings()
        val estimate = ProbabilityResolver.resolve(game, Team.SEA, isCurrentWeek = true, adjustment = null, ratings = emptyMap(), settings = settings)
        val expected = Probability.winProbabilityFromSpread(3.0, settings.marginSigma)
        assertEquals(expected, estimate.probability, 1e-9)
        // Sanity: this must differ from what the key-number model would say for the same spread (58.9%,
        // see KeyNumbersTest), confirming survivor really is on a different curve than the Betting tab's
        // spread market - not just coincidentally close.
        assertTrue(abs(expected - KeyNumbers.winProbability(3.0)) > 0.005)
    }

    @Test fun `a full evaluation over a built season is unchanged by the key-number model`() {
        val season = TestSeason.build(7)
        val e = Evaluator.evaluate(season, UserState(), now)
        assertEquals(32, e.grid.size)
        assertEquals(18, e.planner.size)
        assertEquals(1, e.currentWeek)
        // Every current-week probability in the grid still comes from ProbabilityResolver/Probability, and
        // matches recomputing it directly - proof nothing in the survivor path silently switched models.
        for (team in Team.entries) {
            val g = season.gameFor(team, 1) ?: continue
            val cell = e.grid[team]?.get(0) ?: continue
            val estimate = ProbabilityResolver.resolve(g, team, isCurrentWeek = true, adjustment = null, ratings = season.ratings, settings = ModelSettings())
            assertEquals(estimate.probability, cell.probability, 1e-9)
        }
    }
}

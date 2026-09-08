package com.survivor.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverTest {
    private val game = Game("g", 1, Team.SEA, Team.NE, 0L, line = MarketLine("DK", -3.0, -170, 142, 5L), fpi = FpiProjection(0.61, 5L))

    @Test fun `current week uses the no-vig moneyline over the spread and FPI`() {
        val e = ProbabilityResolver.resolve(game, Team.SEA, isCurrentWeek = true, adjustment = null, ratings = emptyMap(), settings = ModelSettings())
        assertEquals(ProbabilitySource.MARKET_MONEYLINE, e.source)
        assertEquals(Probability.noVig(-170, 142), e.probability, 1e-12)
        assertEquals(-3.0, e.teamSpread)
        assertEquals(-170, e.teamMoneyline)
        val away = ProbabilityResolver.resolve(game, Team.NE, true, null, emptyMap(), ModelSettings())
        assertEquals(1.0, e.probability + away.probability, 1e-12)
        assertEquals(3.0, away.teamSpread)
    }

    @Test fun `consensus moneyline beats the book moneyline`() {
        val g = game.copy(consensus = MarketLine("Consensus", null, -150, 130, 6L))
        val e = ProbabilityResolver.resolve(g, Team.SEA, true, null, emptyMap(), ModelSettings())
        assertEquals(ProbabilitySource.ODDS_API_MONEYLINE, e.source)
        assertEquals(Probability.noVig(-150, 130), e.probability, 1e-12)
    }

    @Test fun `future weeks blend market and FPI`() {
        val s = ModelSettings(futureMarketWeight = 0.6)
        val e = ProbabilityResolver.resolve(game, Team.SEA, false, null, emptyMap(), s)
        assertEquals(ProbabilitySource.MARKET_SPREAD_FPI_BLEND, e.source)
        assertEquals(0.6 * Probability.noVig(-170, 142) + 0.4 * 0.61, e.probability, 1e-12)
    }

    @Test fun `manual override wins over everything`() {
        val adj = Adjustment(1, Team.SEA, overrideWinProbability = 0.55, injuryPoints = -3.0)
        val e = ProbabilityResolver.resolve(game, Team.SEA, true, adj, emptyMap(), ModelSettings())
        assertEquals(ProbabilitySource.MANUAL_OVERRIDE, e.source)
        assertEquals(0.55, e.probability, 1e-12)
    }

    @Test fun `injury adjustment shifts the market number`() {
        val base = ProbabilityResolver.resolve(game, Team.SEA, true, null, emptyMap(), ModelSettings()).probability
        val hurt = ProbabilityResolver.resolve(game, Team.SEA, true, Adjustment(1, Team.SEA, injuryPoints = -3.0), emptyMap(), ModelSettings()).probability
        assertTrue(hurt < base)
    }

    @Test fun `falls back to FPI then to rating gap when no line exists`() {
        val noLine = game.copy(line = null)
        assertEquals(ProbabilitySource.FPI_PROJECTION, ProbabilityResolver.resolve(noLine, Team.SEA, true, null, emptyMap(), ModelSettings()).source)
        val nothing = noLine.copy(fpi = null)
        val ratings = mapOf(Team.SEA to TeamRating(Team.SEA, 3.5), Team.NE to TeamRating(Team.NE, -1.0))
        val e = ProbabilityResolver.resolve(nothing, Team.SEA, true, null, ratings, ModelSettings())
        assertEquals(ProbabilitySource.FPI_RATING_SPREAD, e.source)
        assertEquals(Probability.winProbabilityFromSpread(3.5 + 1.0 + 1.8), e.probability, 1e-12)
        assertEquals(ProbabilitySource.NONE, ProbabilityResolver.resolve(nothing, Team.SEA, true, null, emptyMap(), ModelSettings()).source)
    }
}

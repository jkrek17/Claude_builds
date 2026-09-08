package com.survivor.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvaluatorTest {
    private val now = TestSeason.WEEK1_KICKOFF - 86_400_000L

    @Test fun `covers all 32 teams and 18 weeks with byes as empty cells`() {
        val season = TestSeason.build(7)
        val e = Evaluator.evaluate(season, UserState(), now)
        assertEquals(32, e.grid.size)
        assertTrue(e.grid.values.all { it.size == 18 })
        val byes = e.grid.values.sumOf { row -> row.count { it == null } }
        assertEquals(32, byes) // 4 teams × 8 bye weeks
        assertEquals(18, e.planner.size)
        assertEquals(1, e.currentWeek)
    }

    @Test fun `route never repeats a team and rankings match the grid probabilities`() {
        val season = TestSeason.build(8)
        val e = Evaluator.evaluate(season, UserState(), now)
        assertEquals(e.route.steps.size, e.route.teams.size)
        for (r in e.rankings) {
            val cell = e.grid.getValue(r.team)[0]!!
            assertEquals(cell.probability, r.probability, 1e-12)
            assertTrue(r.probability > 0.0 && r.probability < 1.0)
            assertTrue(r.safetyScore in 0.0..100.0)
        }
        assertEquals(e.rankings.map { it.rank }, (1..e.rankings.size).toList())
        assertNotNull(e.recommended)
        assertEquals(3, e.alternatives.size)
        assertNotNull(e.explanation)
    }

    @Test fun `a used team is removed from rankings and the route immediately`() {
        val season = TestSeason.build(9)
        val before = Evaluator.evaluate(season, UserState(), now)
        val top = before.recommended!!.team
        // Pretend the top team was used in Week 1 and we are now in Week 2.
        val user = UserState(picks = listOf(Pick(1, top)), weekOverride = 2)
        val after = Evaluator.evaluate(season, user, now)
        assertTrue(after.rankings.none { it.team == top })
        assertTrue(after.route.steps.none { it.team == top })
        assertTrue(after.planner.first().team == top && after.planner.first().status == PlannerStatus.LOCKED)
        assertNotEquals(before.rankings.map { it.team }, after.rankings.map { it.team })
    }

    @Test fun `results and strikes are derived from final scores`() {
        val season = TestSeason.build(10, finalsThroughWeek = 2)
        val w1 = season.gamesInWeek(1).first()
        val w2 = season.gamesInWeek(2).first()
        val winner1 = w1.winner!!
        val loser2 = w2.opponentOf(w2.winner!!)
        val e = Evaluator.evaluate(season, UserState(picks = listOf(Pick(1, winner1), Pick(2, loser2))), now)
        assertEquals(3, e.currentWeek)
        assertEquals(PickResult.WIN, e.pickOutcomes[0].result)
        assertEquals(PickResult.LOSS, e.pickOutcomes[1].result)
        assertEquals(1, e.strikesUsed)
        assertEquals(0, e.strikesAllowed)
        assertEquals(1, e.planner[1].strikesAfter)
        assertTrue(!e.eliminated)
    }

    @Test fun `a strike makes the model more conservative`() {
        val season = TestSeason.build(11, finalsThroughWeek = 1)
        val w1 = season.gamesInWeek(1).first()
        val lossPick = Pick(1, w1.opponentOf(w1.winner!!))
        val winPick = Pick(1, w1.winner!!)
        val zero = Evaluator.evaluate(season, UserState(picks = listOf(winPick)), now)
        val one = Evaluator.evaluate(season, UserState(picks = listOf(lossPick)), now)
        assertEquals(0, zero.strikesUsed); assertEquals(1, one.strikesUsed)
        // Future cost shrinks after a strike, so the safest current team gains ground.
        val safest = zero.rankings.maxByOrNull { it.probability }!!.team
        val rankZero = zero.rankings.first { it.team == safest }.rank
        val rankOne = one.rankings.first { it.team == safest }.rank
        assertTrue(rankOne <= rankZero)
        val teamBoth = zero.rankings.first { it.opportunityCost > 1.0 }.team
        assertTrue(one.rankings.first { it.team == teamBoth }.components.futureCost < zero.rankings.first { it.team == teamBoth }.components.futureCost)
        // Survival now counts only zero-loss paths.
        assertEquals(one.seasonZeroLoss, one.seasonSurvival, 1e-12)
        assertTrue(zero.seasonSurvival > zero.seasonZeroLoss)
    }

    @Test fun `future value ignores past and current weeks`() {
        val season = TestSeason.build(12)
        val e = Evaluator.evaluate(season, UserState(weekOverride = 6), now)
        for ((_, fv) in e.futureValues) assertTrue(fv.games.all { it.week > 6 })
        assertTrue(e.route.steps.all { it.week >= 6 })
        assertTrue(e.planner.filter { it.week < 6 }.all { it.status == PlannerStatus.MISSED })
    }

    @Test fun `market line overrides the projection and a manual override beats both`() {
        val season = TestSeason.build(13)
        val g = season.gamesInWeek(1).first()
        val noLine = season.copy(games = season.games.map { if (it.id == g.id) it.copy(line = null) else it })
        val withLine = Evaluator.evaluate(season, UserState(), now).rankings.first { it.team == g.home }
        val projected = Evaluator.evaluate(noLine, UserState(), now).rankings.first { it.team == g.home }
        assertEquals(ProbabilitySource.MARKET_MONEYLINE, withLine.estimate.source)
        assertEquals(ProbabilitySource.FPI_PROJECTION, projected.estimate.source)
        assertTrue(projected.components.marketAdjustment < withLine.components.marketAdjustment)
        val user = UserState(adjustments = listOf(Adjustment(1, g.home, overrideWinProbability = 0.5)))
        val overridden = Evaluator.evaluate(season, user, now).rankings.first { it.team == g.home }
        assertEquals(0.5, overridden.probability, 1e-12)
    }

    @Test fun `opportunity cost recognises the spec example`() {
        // Build a season where LAC is 83% now with a 91% spot later that no other team can cover as well,
        // and JAX is 80% now with nothing better later. JAX should rank first.
        val season = TestSeason.build(14)
        fun setLine(g: Game, homeFav: Double) = g.copy(line = MarketLine("T", -homeFav, null, null, 1L), fpi = FpiProjection(Probability.winProbabilityFromSpread(homeFav), 1L))
        val lacW1 = season.gameFor(Team.LAC, 1)!!; val jaxW1 = season.gameFor(Team.JAX, 1)!!
        val lacFuture = (2..18).mapNotNull { season.gameFor(Team.LAC, it) }.first()
        val edited = season.games.map { g ->
            when (g.id) {
                lacW1.id -> if (g.home == Team.LAC) setLine(g, 12.9) else setLine(g, -12.9)          // ~83%
                jaxW1.id -> if (g.home == Team.JAX) setLine(g, 11.3) else setLine(g, -11.3)          // ~80%
                lacFuture.id -> if (g.home == Team.LAC) setLine(g, 18.0) else setLine(g, -18.0)      // ~91%
                else -> if (g.involves(Team.JAX) && g.week > 1) (if (g.home == Team.JAX) setLine(g, 3.0) else setLine(g, -3.0)) else g
            }
        }
        val e = Evaluator.evaluate(season.copy(games = edited), UserState(settings = ModelSettings(roadPenalty = 0.0, divisionalPenalty = 0.0, travelPenaltyPerTimeZone = 0.0, shortRestPenaltyPerDay = 0.0)), now)
        val lac = e.rankings.first { it.team == Team.LAC }
        val jax = e.rankings.first { it.team == Team.JAX }
        assertTrue(lac.probability > jax.probability)
        assertTrue(lac.opportunityCost > jax.opportunityCost)
        assertTrue(jax.safetyScore > lac.safetyScore, "jax=${jax.safetyScore} lac=${lac.safetyScore}")
    }

    @Test fun `ownership leverage only matters under contrarian strategies`() {
        val season = TestSeason.build(15)
        val top = Evaluator.evaluate(season, UserState(), now).recommended!!.team
        val adj = Adjustment(1, top, estimatedPickShare = 0.6)
        val conservative = Evaluator.evaluate(season, UserState(adjustments = listOf(adj)), now).rankings.first { it.team == top }
        val contrarian = Evaluator.evaluate(season, UserState(adjustments = listOf(adj), settings = ModelSettings(strategy = Strategy.CONTRARIAN)), now).rankings.first { it.team == top }
        assertEquals(0.0, conservative.components.leverageAdjustment, 1e-12)
        assertTrue(contrarian.components.leverageAdjustment < 0.0)
        assertNotNull(contrarian.leverage)
        assertNull(Evaluator.evaluate(season, UserState(), now).recommended!!.leverage)
    }

    @Test fun `strategy comparison produces four simulations with sane numbers`() {
        val season = TestSeason.build(16)
        val results = Strategies.compare(season, UserState(), now, iterations = 2000)
        assertEquals(4, results.size)
        for (r in results) {
            assertTrue(r.surviveSeason in 0.0..1.0)
            assertTrue(r.reachWeek10 >= r.reachWeek14 && r.reachWeek14 >= r.reachWeek18)
            assertEquals(r.route.steps.size, r.route.teams.size)
        }
        val optimized = results.first { it.strategy == Strategies.FUTURE_VALUE }
        val analytic = Survival.survive(optimized.route.probabilities, 1)
        assertEquals(analytic, optimized.surviveSeason, 0.05)
    }
}

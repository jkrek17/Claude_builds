package com.survivor.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicySimulatorTest {
    private val now = TestSeason.WEEK1_KICKOFF - 86_400_000L

    /** The exact greedy route the app would compute, undiscounted, from the current week onward. */
    private fun analyticGreedySurvival(season: Season, currentWeek: Int): Double {
        val cands = (currentWeek..REGULAR_SEASON_WEEKS).associateWith { w ->
            season.gamesInWeek(w).flatMap { g -> listOf(g.home, g.away) }.map { t ->
                val g = season.gameFor(t, w)!!
                Candidate(t, ProbabilityResolver.resolve(g, t, w <= currentWeek, null, season.ratings, ModelSettings()).probability)
            }
        }
        val route = Optimizer.greedy(cands, strikesAllowed = 1)
        return Survival.survive(route.probabilities, 1)
    }

    @Test fun `zero noise closed loop follows the fixed greedy route`() {
        val season = TestSeason.build(50)
        val user = UserState()
        val currentWeek = Evaluator.evaluate(season, user, now).currentWeek
        val analytic = analyticGreedySurvival(season, currentWeek)

        val cmp = PolicySimulator.simulate(season, user, now, listOf(Policy.Greedy), seasons = 4000, seed = 123, tauBase = 0.0, tauPerWeek = 0.0)
        val r = cmp.results.single()
        assertEquals(analytic, r.surviveSeason, 0.03)
    }

    @Test fun `same seed reproduces identical results`() {
        val season = TestSeason.build(60)
        val user = UserState()
        val policies = listOf(Policy.Greedy, Policy.Optimized(0.03), Policy.ZeroLoss)
        val a = PolicySimulator.simulate(season, user, now, policies, seasons = 40, seed = 99)
        val b = PolicySimulator.simulate(season, user, now, policies, seasons = 40, seed = 99)
        assertEquals(a.results, b.results)
    }

    @Test fun `policies are compared on the same seasons via common random numbers`() {
        val season = TestSeason.build(70)
        val user = UserState()
        val clone = Policy.Custom("Greedy clone") { ctx -> Policy.Greedy.decide(ctx) }
        val cmp = PolicySimulator.simulate(season, user, now, listOf(Policy.Greedy, clone), seasons = 150, seed = 17)
        val (g, c) = cmp.results
        assertEquals(cmp.seasons, g.seasons)
        assertEquals(cmp.seasons, c.seasons)
        assertEquals(g.surviveSeason, c.surviveSeason, 1e-12)
        assertEquals(g.zeroLossFinish, c.zeroLossFinish, 1e-12)
        assertEquals(g.reachWeek10, c.reachWeek10, 1e-12)
        assertEquals(g.reachWeek14, c.reachWeek14, 1e-12)
        assertEquals(g.reachWeek18, c.reachWeek18, 1e-12)
        assertEquals(g.expectedWeeksAlive, c.expectedWeeksAlive, 1e-12)
        assertEquals(g.expectedStrikes, c.expectedStrikes, 1e-12)
        assertEquals(g.expectedEliminationWeek, c.expectedEliminationWeek)
    }

    @Test fun `reach-week metrics are monotone for every policy`() {
        val season = TestSeason.build(80)
        val user = UserState()
        val policies = listOf(Policy.Greedy, Policy.Optimized(0.03), Policy.ZeroLoss, Policy.Threshold(0.65, 0.03))
        val cmp = PolicySimulator.simulate(season, user, now, policies, seasons = 30, seed = 21)
        for (r in cmp.results) {
            assertTrue(r.reachWeek10 >= r.reachWeek14 - 1e-9, "${r.label}: reach10=${r.reachWeek10} reach14=${r.reachWeek14}")
            assertTrue(r.reachWeek14 >= r.reachWeek18 - 1e-9, "${r.label}: reach14=${r.reachWeek14} reach18=${r.reachWeek18}")
            assertTrue(r.reachWeek18 >= r.surviveSeason - 1e-9, "${r.label}: reach18=${r.reachWeek18} survive=${r.surviveSeason}")
        }
    }

    @Test fun `a single remaining strike caps every zero-loss finish at survival`() {
        val season = TestSeason.build(40, finalsThroughWeek = 1)
        val w1 = season.gamesInWeek(1).first()
        val lossPick = Pick(1, w1.opponentOf(w1.winner!!))
        val user = UserState(picks = listOf(lossPick))
        val eval = Evaluator.evaluate(season, user, now)
        assertEquals(1, eval.strikesUsed)
        assertEquals(0, eval.strikesAllowed)

        val cmp = PolicySimulator.simulate(season, user, now, listOf(Policy.Greedy, Policy.Optimized(0.03)), seasons = 100, seed = 5)
        for (r in cmp.results) {
            // With no strikes left, staying alive requires exactly zero further losses: the two must match.
            assertEquals(r.surviveSeason, r.zeroLossFinish, 1e-12, r.label)
            assertTrue(r.surviveSeason in 0.0..1.0)
        }
    }

    @Test fun `used teams never appear as a decision or candidate`() {
        val season = TestSeason.build(30)
        val week1Team = Evaluator.evaluate(season, UserState(), now).recommended!!.team
        val user = UserState(picks = listOf(Pick(1, week1Team)), weekOverride = 2)

        val seenAvailable = mutableSetOf<Team>()
        val chosen = mutableListOf<Team>()
        val probe = Policy.Custom("probe") { ctx ->
            seenAvailable += ctx.available
            ctx.thisWeek.maxByOrNull { it.value }?.key?.also { chosen += it }
        }
        PolicySimulator.simulate(season, user, now, listOf(probe), seasons = 50, seed = 7)

        assertTrue(week1Team !in seenAvailable)
        assertTrue(week1Team !in chosen)
    }

    @Test fun `a future locked pick is honoured without ever being handed to the policy`() {
        val season = TestSeason.build(31)
        val week3Team = season.gamesInWeek(3).first().home
        val week1Team = Evaluator.evaluate(season, UserState(), now).recommended!!.team
        val user = UserState(picks = listOf(Pick(1, week1Team), Pick(3, week3Team)), weekOverride = 2)

        val visitedWeeks = mutableSetOf<Int>()
        val seenAvailable = mutableSetOf<Team>()
        val probe = Policy.Custom("probe") { ctx ->
            visitedWeeks += ctx.week
            seenAvailable += ctx.available
            ctx.thisWeek.maxByOrNull { it.value }?.key
        }
        PolicySimulator.simulate(season, user, now, listOf(probe), seasons = 30, seed = 11)

        assertTrue(3 !in visitedWeeks, "week 3 is locked and must never reach the policy")
        assertTrue(week1Team !in seenAvailable)
        assertTrue(week3Team !in seenAvailable)
    }

    @Test fun `summary table has one row per policy`() {
        val season = TestSeason.build(90)
        val cmp = PolicySimulator.simulate(season, UserState(), now, listOf(Policy.Greedy, Policy.ZeroLoss), seasons = 20, seed = 3)
        val lines = cmp.summaryTable().lines()
        assertEquals(2 + cmp.results.size, lines.size)
        assertTrue(lines[0].contains("Policy"))
    }

    @Test fun `Policy PoolWin runs alongside the other policies and does not change their results`() {
        val season = TestSeason.build(95)
        val user = UserState()
        val withoutPoolWin = PolicySimulator.simulate(season, user, now, listOf(Policy.Greedy, Policy.Optimized(0.03)), seasons = 60, seed = 41)
        val withPoolWin = PolicySimulator.simulate(
            season, user, now,
            listOf(Policy.Greedy, Policy.Optimized(0.03), Policy.PoolWin(discountPerWeek = 0.03, poolEntries = 20)),
            seasons = 60, seed = 41,
        )
        assertEquals(withoutPoolWin.results[0], withPoolWin.results[0])
        assertEquals(withoutPoolWin.results[1], withPoolWin.results[1])
        val poolWinResult = withPoolWin.results[2]
        assertTrue(poolWinResult.surviveSeason in 0.0..1.0)
        assertTrue(poolWinResult.expectedWeeksAlive in 0.0..(REGULAR_SEASON_WEEKS.toDouble()))
    }
}

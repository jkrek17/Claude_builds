package com.survivor.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptimizerTest {
    @Test fun `hungarian finds the minimum-cost assignment`() {
        val cost = arrayOf(doubleArrayOf(4.0, 1.0, 3.0), doubleArrayOf(2.0, 0.0, 5.0), doubleArrayOf(3.0, 2.0, 2.0))
        val a = Hungarian.solve(cost)
        assertEquals(listOf(1, 0, 2), a.toList())
    }

    @Test fun `hungarian handles rectangular matrices`() {
        val cost = arrayOf(doubleArrayOf(9.0, 9.0, 1.0, 9.0), doubleArrayOf(1.0, 9.0, 9.0, 9.0))
        assertEquals(listOf(2, 0), Hungarian.solve(cost).toList())
    }

    @Test fun `optimizer prefers saving a team for its elite future spot (spec example)`() {
        // Week 1: A 83%, B 80%. Week 2: A 91%, B 74%, C 70%. Greedy takes A in week 1 and gets 0.83*0.74;
        // the optimizer should take B then A: 0.80*0.91.
        val a = Team.LAC; val b = Team.JAX; val c = Team.DET
        val cands = mapOf(
            1 to listOf(Candidate(a, 0.83), Candidate(b, 0.80), Candidate(c, 0.60)),
            2 to listOf(Candidate(a, 0.91), Candidate(b, 0.74), Candidate(c, 0.70)),
        )
        val route = Optimizer.optimize(cands, strikesAllowed = 1)
        assertEquals(b, route.team(1))
        assertEquals(a, route.team(2))
        val greedy = Optimizer.greedy(cands, strikesAllowed = 1)
        assertEquals(a, greedy.team(1))
        assertTrue(route.survival > greedy.survival)
    }

    @Test fun `no team is used twice and every week gets a team`() {
        val season = TestSeason.build(3)
        val cands = (1..18).associateWith { w -> season.gamesInWeek(w).flatMap { g -> listOf(g.home, g.away).map { t -> Candidate(t, ProbabilityResolver.resolve(g, t, false, null, season.ratings, ModelSettings()).probability) } } }
        val route = Optimizer.optimize(cands, 1)
        assertEquals(18, route.steps.size)
        assertEquals(18, route.teams.size)
    }

    @Test fun `locked picks are honoured and excluded elsewhere`() {
        val season = TestSeason.build(4)
        val cands = (1..18).associateWith { w -> season.gamesInWeek(w).flatMap { g -> listOf(g.home, g.away).map { t -> Candidate(t, ProbabilityResolver.resolve(g, t, false, null, season.ratings, ModelSettings()).probability) } } }
        val lockedTeam = season.gamesInWeek(1).first().home
        val route = Optimizer.optimize(cands, 1, locked = mapOf(1 to lockedTeam))
        assertEquals(lockedTeam, route.team(1))
        assertEquals(1, route.steps.count { it.team == lockedTeam })
        assertTrue(route.steps.first { it.week == 1 }.locked)
    }

    @Test fun `local search never makes the objective worse than the hungarian start`() {
        for (seed in 1..5) {
            val season = TestSeason.build(seed)
            val cands = (1..18).associateWith { w -> season.gamesInWeek(w).flatMap { g -> listOf(g.home, g.away).map { t -> Candidate(t, ProbabilityResolver.resolve(g, t, false, null, season.ratings, ModelSettings()).probability) } } }
            val a = Optimizer.optimize(cands, 1, localSearch = false)
            val b = Optimizer.optimize(cands, 1, localSearch = true)
            assertTrue(b.survival >= a.survival - 1e-12)
        }
    }
}

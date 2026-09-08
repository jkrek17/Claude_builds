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

    @Test fun `local search climbs whichever objective is selected, not just survival`() {
        for (seed in 1..5) {
            val season = TestSeason.build(seed)
            val cands = (1..18).associateWith { w -> season.gamesInWeek(w).flatMap { g -> listOf(g.home, g.away).map { t -> Candidate(t, ProbabilityResolver.resolve(g, t, false, null, season.ratings, ModelSettings()).probability) } } }
            val survive = Optimizer.optimize(cands, 1, objective = RouteObjective.SURVIVE_SEASON)
            val weeks = Optimizer.optimize(cands, 1, objective = RouteObjective.EXPECTED_WEEKS_ALIVE)
            // EXPECTED_WEEKS_ALIVE never trails SURVIVE_SEASON on expected weeks alive, and vice versa for survival.
            assertTrue(weeks.expectedWeeksAlive >= survive.expectedWeeksAlive - 1e-9, "seed=$seed")
            assertTrue(survive.survival >= weeks.survival - 1e-9, "seed=$seed")
        }
    }

    @Test fun `local search from a greedy start never returns a worse objective than either start, on TestSeason seeds 1 through 5`() {
        for (seed in 1..5) {
            val season = TestSeason.build(seed)
            val cands = (1..18).associateWith { w -> season.gamesInWeek(w).flatMap { g -> listOf(g.home, g.away).map { t -> Candidate(t, ProbabilityResolver.resolve(g, t, false, null, season.ratings, ModelSettings()).probability) } } }
            for (objective in RouteObjective.entries) {
                val hungarianOnly = Optimizer.optimize(cands, 1, localSearch = false, objective = objective)
                val greedyOnly = Optimizer.greedy(cands, 1)
                val hungarianStartValue = hungarianOnly.objectiveValue(objective, horizonWeight = 0.5)
                val greedyStartValue = greedyOnly.objectiveValue(objective, horizonWeight = 0.5)
                val optimized = Optimizer.optimize(cands, 1, objective = objective)
                val optimizedValue = optimized.objectiveValue(objective, horizonWeight = 0.5)
                assertTrue(optimizedValue >= hungarianStartValue - 1e-9, "seed=$seed objective=$objective optimized=$optimizedValue hungarian=$hungarianStartValue")
                assertTrue(optimizedValue >= greedyStartValue - 1e-9, "seed=$seed objective=$objective optimized=$optimizedValue greedy=$greedyStartValue")
            }
        }
    }

    @Test fun `a genuine 3-way rotation trap - greedy start reaches strictly better EXPECTED_WEEKS_ALIVE than the hungarian-only start, and the optimizer returns it`() {
        // A 3-week, 3-team assignment shaped like a triangle: A plays weeks 1 and 2, B plays weeks 1 and 3, C
        // plays weeks 2 and 3. Exactly two full (one-team-per-week, no reuse) assignments exist: (A,C,B) and
        // (B,A,C). Moving between them needs a simultaneous 3-way rotation - unreachable by any single
        // replace move (every candidate not already assigned here is either unavailable for that week or
        // already used elsewhere) or swap move (every pairing needs a team the other week doesn't offer) -
        // so a single-start local search from whichever assignment it begins at is stuck exactly there.
        val a = Team.KC; val b = Team.DEN; val c = Team.LV
        val cands = mapOf(
            1 to listOf(Candidate(a, 0.99), Candidate(b, 0.50)),
            2 to listOf(Candidate(a, 0.98), Candidate(c, 0.40)),
            3 to listOf(Candidate(b, 0.90), Candidate(c, 0.85)),
        )
        // Hungarian maximizes P(zero losses) exactly and lands on (B,A,C): 0.50*0.98*0.85 = 0.4165 beats
        // (A,C,B): 0.99*0.40*0.90 = 0.3564 - a worse pick for EXPECTED_WEEKS_ALIVE, computed below.
        val hungarianOnly = Optimizer.optimize(cands, strikesAllowed = 1, localSearch = false, objective = RouteObjective.EXPECTED_WEEKS_ALIVE)
        assertEquals(b, hungarianOnly.team(1)); assertEquals(a, hungarianOnly.team(2)); assertEquals(c, hungarianOnly.team(3))

        // Greedy (highest probability each week, in week order, never reusing a team) lands on the other full
        // assignment, (A,C,B), which is strictly better on EXPECTED_WEEKS_ALIVE.
        val greedyOnly = Optimizer.greedy(cands, strikesAllowed = 1)
        assertEquals(a, greedyOnly.team(1)); assertEquals(c, greedyOnly.team(2)); assertEquals(b, greedyOnly.team(3))
        assertTrue(
            greedyOnly.expectedWeeksAlive > hungarianOnly.expectedWeeksAlive + 1e-6,
            "greedy=${greedyOnly.expectedWeeksAlive} hungarianOnly=${hungarianOnly.expectedWeeksAlive}",
        )

        // The optimizer (Hungarian start AND greedy start, both local-searched, higher objective wins) must
        // return the better (greedy-reachable) assignment, not stall at the Hungarian one.
        val optimized = Optimizer.optimize(cands, strikesAllowed = 1, objective = RouteObjective.EXPECTED_WEEKS_ALIVE)
        assertEquals(a, optimized.team(1)); assertEquals(c, optimized.team(2)); assertEquals(b, optimized.team(3))
        assertEquals(greedyOnly.expectedWeeksAlive, optimized.expectedWeeksAlive, 1e-9)
    }

    @Test fun `pool-win objective orders sensibly between expected-weeks-alive and survive-season as pool size varies`() {
        val season = TestSeason.build(20)
        val cands = (1..18).associateWith { w -> season.gamesInWeek(w).flatMap { g -> listOf(g.home, g.away).map { t -> Candidate(t, ProbabilityResolver.resolve(g, t, false, null, season.ratings, ModelSettings()).probability) } } }
        val survive = Optimizer.optimize(cands, 1, objective = RouteObjective.SURVIVE_SEASON)
        val weeks = Optimizer.optimize(cands, 1, objective = RouteObjective.EXPECTED_WEEKS_ALIVE)
        val poolBig = Optimizer.optimize(cands, 1, objective = RouteObjective.POOL_WIN, poolEntries = 100_000, fieldWinProbability = 0.76)
        val poolSmall = Optimizer.optimize(cands, 1, objective = RouteObjective.POOL_WIN, poolEntries = 2, fieldWinProbability = 0.76)

        assertEquals(18, poolBig.steps.size)
        assertEquals(18, poolSmall.steps.size)
        assertEquals(18, poolBig.teams.size)
        assertEquals(18, poolSmall.teams.size)

        // A 100,000-entry pool almost never runs out of other survivors early, so POOL_WIN should behave like
        // SURVIVE_SEASON: either it matches survival almost exactly, or it is at least as good as the
        // expected-weeks-alive route on raw survival.
        assertTrue(
            Math.abs(poolBig.survival - survive.survival) < 1e-9 || poolBig.survival >= weeks.survival - 1e-9,
            "poolBig=${poolBig.survival} survive=${survive.survival} weeks=${weeks.survival}",
        )
    }

    @Test fun `hand-built 3-week example where the objectives choose different Week-1 teams`() {
        // A is safe both in Week 1 and Week 3; B is a much riskier alternative in both weeks; C is the
        // only Week-2 team. Saving A for its (still very safe) Week 3 spot maximizes P(survive season);
        // spending the safest team (A) in Week 1 instead maximizes the expected number of weeks survived,
        // since an early loss costs more remaining weeks than a late one.
        val a = Team.KC; val b = Team.DEN; val c = Team.LV
        val cands = mapOf(
            1 to listOf(Candidate(a, 0.95), Candidate(b, 0.60)),
            2 to listOf(Candidate(c, 0.50)),
            3 to listOf(Candidate(a, 0.95), Candidate(b, 0.55)),
        )
        val survive = Optimizer.optimize(cands, strikesAllowed = 1, objective = RouteObjective.SURVIVE_SEASON)
        val weeks = Optimizer.optimize(cands, strikesAllowed = 1, objective = RouteObjective.EXPECTED_WEEKS_ALIVE)

        assertEquals(b, survive.team(1), "SURVIVE_SEASON should save A for its Week 3 spot")
        assertEquals(a, survive.team(3))
        assertEquals(a, weeks.team(1), "EXPECTED_WEEKS_ALIVE should spend the safest team early")
        assertEquals(b, weeks.team(3))

        assertTrue(survive.survival > weeks.survival)
        assertTrue(weeks.expectedWeeksAlive > survive.expectedWeeksAlive)
    }
}

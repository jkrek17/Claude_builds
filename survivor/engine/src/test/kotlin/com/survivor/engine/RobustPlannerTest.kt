package com.survivor.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RobustPlannerTest {

    @Test fun `zero noise picks the same current-week team every scenario`() {
        val season = TestSeason.build(3)
        val user = UserState()
        val report = RobustPlanner.plan(
            season, user, TestSeason.WEEK1_KICKOFF,
            scenarios = 25, seed = 7L, tauBase = 0.0, tauPerWeek = 0.0, computeLockedValues = false,
        )
        val eval = Evaluator.evaluate(season, user, TestSeason.WEEK1_KICKOFF)
        val expected = eval.unconstrainedRoute.team(eval.currentWeek)
        assertNotNull(expected)
        assertEquals(1.0, report.currentWeekShares[expected] ?: 0.0, 1e-9)
        assertEquals(setOf(expected), report.currentWeekShares.keys)
        assertEquals(1.0, report.routeStability, 1e-9)
    }

    @Test fun `current-week shares sum to one and week consensus shares sum to at most one`() {
        val season = TestSeason.build(5)
        val user = UserState()
        val report = RobustPlanner.plan(season, user, TestSeason.WEEK1_KICKOFF, scenarios = 40, seed = 11L, computeLockedValues = false)
        val total = report.currentWeekShares.values.sum()
        assertEquals(1.0, total, 1e-9)
        for ((_, shares) in report.weekConsensus) {
            assertTrue(shares.sumOf { it.share } <= 1.0 + 1e-9)
        }
    }

    @Test fun `same seed reproduces an identical report`() {
        val season = TestSeason.build(6)
        val user = UserState()
        val a = RobustPlanner.plan(season, user, TestSeason.WEEK1_KICKOFF, scenarios = 30, seed = 99L, lockedValueScenarios = 15)
        val b = RobustPlanner.plan(season, user, TestSeason.WEEK1_KICKOFF, scenarios = 30, seed = 99L, lockedValueScenarios = 15)
        assertEquals(a.currentWeekShares, b.currentWeekShares)
        assertEquals(a.weekConsensus, b.weekConsensus)
        assertEquals(a.teamScenarioValue, b.teamScenarioValue)
        assertEquals(a.robustPick, b.robustPick)
        assertEquals(a.routeStability, b.routeStability, 0.0)
    }

    @Test fun `used teams and locked picks never appear as current-week candidates, locked weeks are certain`() {
        // Finals through week 2 so week 3 is current; record picks for weeks 1-2 (used) and a future lock at week 4.
        val season = TestSeason.build(9, finalsThroughWeek = 2)
        val now = TestSeason.WEEK1_KICKOFF + 3L * 7L * 86_400_000L
        val week1Team = season.gamesInWeek(1).first().home
        val week2Team = season.gamesInWeek(2).first { !it.involves(week1Team) }.home
        val week4Team = season.gamesInWeek(4).first { it.home != week1Team && it.home != week2Team && it.away != week1Team && it.away != week2Team }.home
        val user = UserState(picks = listOf(Pick(1, week1Team), Pick(2, week2Team), Pick(4, week4Team)))
        val eval = Evaluator.evaluate(season, user, now)
        assertEquals(3, eval.currentWeek)

        val report = RobustPlanner.plan(season, user, now, scenarios = 30, seed = 3L, computeLockedValues = false)
        assertTrue(week1Team !in report.currentWeekShares.keys)
        assertTrue(week2Team !in report.currentWeekShares.keys)

        val week4Consensus = report.weekConsensus[4].orEmpty()
        assertEquals(listOf(TeamShare(week4Team, 1.0)), week4Consensus)
    }

    @Test fun `with noise the robust pick is a team playing this week and available`() {
        val season = TestSeason.build(4)
        val user = UserState()
        val report = RobustPlanner.plan(season, user, TestSeason.WEEK1_KICKOFF, scenarios = 30, seed = 21L, lockedValueScenarios = 20)
        val pick = report.robustPick
        assertNotNull(pick)
        val eval = Evaluator.evaluate(season, user, TestSeason.WEEK1_KICKOFF)
        assertNotNull(season.gameFor(pick, eval.currentWeek))
        assertTrue(pick !in eval.usedTeams)
    }

    @Test fun `200 scenarios without locked values finishes well under 5 seconds`() {
        val season = TestSeason.build(3)
        val user = UserState()
        val start = System.currentTimeMillis()
        val report = RobustPlanner.plan(season, user, TestSeason.WEEK1_KICKOFF, scenarios = 200, seed = 42L, computeLockedValues = false)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 5000, "took ${elapsed}ms")
        assertEquals(200, report.scenarios)
        assertTrue(report.teamScenarioValue.isEmpty())
    }
}

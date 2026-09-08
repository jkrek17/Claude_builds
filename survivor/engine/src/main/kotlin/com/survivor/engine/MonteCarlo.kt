package com.survivor.engine

import kotlin.random.Random

data class SimulationResult(
    val strategy: String,
    val iterations: Int,
    val surviveSeason: Double,
    val reachWeek10: Double,
    val reachWeek14: Double,
    val reachWeek18: Double,
    /** Mean elimination week among eliminated entries; null if no entry was eliminated. */
    val expectedEliminationWeek: Double?,
    val expectedStrikes: Double,
    val zeroLossFinish: Double,
    val route: Route,
)

/**
 * Simulates the remaining season along a fixed route: each week is an independent Bernoulli trial on
 * that week's (undiscounted) win probability. An entry is eliminated when total strikes reach 2.
 */
object MonteCarlo {
    fun simulate(
        strategy: String,
        route: Route,
        strikesUsed: Int,
        iterations: Int,
        seed: Long = 42L,
    ): SimulationResult {
        val rng = Random(seed)
        val steps = route.steps.sortedBy { it.week }
        var survive = 0
        var reach10 = 0
        var reach14 = 0
        var reach18 = 0
        var zeroLoss = 0
        var elimWeekSum = 0L
        var eliminated = 0
        var strikeSum = 0L
        val startWeek = steps.firstOrNull()?.week ?: (REGULAR_SEASON_WEEKS + 1)
        for (it in 0 until iterations) {
            var strikes = strikesUsed
            var elimWeek = -1
            for (s in steps) {
                if (rng.nextDouble() >= s.probability) {
                    strikes++
                    if (strikes >= 2) { elimWeek = s.week; break }
                }
            }
            val alive = elimWeek < 0
            if (alive) survive++ else { eliminated++; elimWeekSum += elimWeek }
            if (alive || elimWeek >= 10 || startWeek >= 10) reach10++
            if (alive || elimWeek >= 14 || startWeek >= 14) reach14++
            if (alive || elimWeek >= 18 || startWeek >= 18) reach18++
            if (alive && strikes == strikesUsed) zeroLoss++
            strikeSum += (strikes - strikesUsed).coerceAtLeast(0)
        }
        val n = iterations.toDouble()
        return SimulationResult(
            strategy = strategy,
            iterations = iterations,
            surviveSeason = survive / n,
            reachWeek10 = reach10 / n,
            reachWeek14 = reach14 / n,
            reachWeek18 = reach18 / n,
            expectedEliminationWeek = if (eliminated > 0) elimWeekSum.toDouble() / eliminated else null,
            expectedStrikes = strikeSum / n,
            zeroLossFinish = zeroLoss / n,
            route = route,
        )
    }
}

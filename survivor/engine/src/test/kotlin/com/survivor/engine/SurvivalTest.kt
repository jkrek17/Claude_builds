package com.survivor.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class SurvivalTest {
    private val ps = listOf(0.8, 0.7, 0.9, 0.6)

    /** Brute-force enumeration of every win/loss combination as ground truth. */
    private fun bruteForce(ps: List<Double>, losses: Int): Double {
        var total = 0.0
        for (mask in 0 until (1 shl ps.size)) {
            var prob = 1.0; var l = 0
            for (i in ps.indices) if (mask and (1 shl i) != 0) { prob *= (1 - ps[i]); l++ } else prob *= ps[i]
            if (l == losses) total += prob
        }
        return total
    }

    @Test fun `zero-loss probability is the product`() = assertEquals(0.8 * 0.7 * 0.9 * 0.6, Survival.zeroLoss(ps), 1e-12)

    @Test fun `exactly-one-loss matches enumeration`() = assertEquals(bruteForce(ps, 1), Survival.exactlyOneLoss(ps), 1e-12)

    @Test fun `double elimination survival is zero plus one loss, single elimination is zero only`() {
        assertEquals(bruteForce(ps, 0) + bruteForce(ps, 1), Survival.survive(ps, strikesAllowed = 1), 1e-12)
        assertEquals(bruteForce(ps, 0), Survival.survive(ps, strikesAllowed = 0), 1e-12)
    }

    @Test fun `loss distribution sums to one and matches enumeration`() {
        val dist = Survival.lossDistribution(ps)
        assertEquals(1.0, dist.sum(), 1e-12)
        for (k in 0..ps.size) assertEquals(bruteForce(ps, k), dist[k], 1e-12)
    }

    /** Brute-force P(at most [strikesAllowed] losses among the first [k] games) for the [k]-length prefix. */
    private fun bruteForceAliveAfter(ps: List<Double>, k: Int, strikesAllowed: Int): Double {
        val prefix = ps.subList(0, k)
        var total = 0.0
        for (losses in 0..minOf(strikesAllowed, prefix.size)) total += bruteForce(prefix, losses)
        return total
    }

    @Test fun `aliveAfterEachWeek matches brute-force enumeration for every prefix`() {
        for (strikesAllowed in 0..2) {
            val alive = Survival.aliveAfterEachWeek(ps, strikesAllowed)
            assertEquals(ps.size, alive.size)
            for (k in 1..ps.size) assertEquals(bruteForceAliveAfter(ps, k, strikesAllowed), alive[k - 1], 1e-12)
        }
    }

    @Test fun `expectedWeeksAlive is the sum of aliveAfterEachWeek and matches brute-force`() {
        for (strikesAllowed in 0..2) {
            val expected = (1..ps.size).sumOf { k -> bruteForceAliveAfter(ps, k, strikesAllowed) }
            assertEquals(expected, Survival.expectedWeeksAlive(ps, strikesAllowed), 1e-12)
            assertEquals(Survival.aliveAfterEachWeek(ps, strikesAllowed).sum(), Survival.expectedWeeksAlive(ps, strikesAllowed), 1e-12)
        }
    }

    @Test fun `an all-safe entry has expected weeks alive equal to season length`() {
        val allSafe = List(5) { 1.0 }
        assertEquals(5.0, Survival.expectedWeeksAlive(allSafe, strikesAllowed = 1), 1e-12)
        assertEquals(List(5) { 1.0 }, Survival.aliveAfterEachWeek(allSafe, strikesAllowed = 1))
    }

    @Test fun `objectiveValue matches survive, expectedWeeksAlive and the blend respectively`() {
        val strikesAllowed = 1
        assertEquals(Survival.survive(ps, strikesAllowed), Survival.objectiveValue(ps, strikesAllowed, RouteObjective.SURVIVE_SEASON, horizonWeight = 0.5), 1e-12)
        assertEquals(Survival.expectedWeeksAlive(ps, strikesAllowed), Survival.objectiveValue(ps, strikesAllowed, RouteObjective.EXPECTED_WEEKS_ALIVE, horizonWeight = 0.5), 1e-12)
        val horizonWeight = 0.3
        val expectedBlend = (1 - horizonWeight) * Survival.survive(ps, strikesAllowed) + horizonWeight * (Survival.expectedWeeksAlive(ps, strikesAllowed) / ps.size)
        assertEquals(expectedBlend, Survival.objectiveValue(ps, strikesAllowed, RouteObjective.BLENDED, horizonWeight), 1e-12)
    }
}

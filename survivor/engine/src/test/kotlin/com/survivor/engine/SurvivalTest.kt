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
}

package com.survivor.engine

/** Double-elimination survival math for a sequence of independent weekly win probabilities. */
object Survival {
    /** P(zero losses) = Π p_i. */
    fun zeroLoss(ps: List<Double>): Double = ps.fold(1.0) { acc, p -> acc * p }

    /** P(exactly one loss) = Σ_j (1 - p_j) Π_{i≠j} p_i. */
    fun exactlyOneLoss(ps: List<Double>): Double {
        if (ps.isEmpty()) return 0.0
        var total = 0.0
        for (j in ps.indices) {
            var term = 1.0 - ps[j]
            for (i in ps.indices) if (i != j) term *= ps[i]
            total += term
        }
        return total
    }

    /**
     * Probability of finishing the sequence with at most [strikesAllowed] more losses.
     * With one strike already used in a double-elimination pool, only the zero-loss path survives.
     */
    fun survive(ps: List<Double>, strikesAllowed: Int): Double = when {
        strikesAllowed <= 0 -> zeroLoss(ps)
        else -> zeroLoss(ps) + exactlyOneLoss(ps)
    }

    /** Distribution of the number of losses (0..n) for reporting: expected strikes etc. */
    fun lossDistribution(ps: List<Double>): DoubleArray {
        var dist = doubleArrayOf(1.0)
        for (p in ps) {
            val next = DoubleArray(dist.size + 1)
            for (k in dist.indices) {
                next[k] += dist[k] * p
                next[k + 1] += dist[k] * (1 - p)
            }
            dist = next
        }
        return dist
    }
}

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

    /**
     * For each prefix length `k = 1..n`, the probability of still being alive after week `k`: at most
     * [strikesAllowed] losses among the first `k` games. Computed incrementally from the loss-distribution
     * DP, O(n²). A pool rarely runs the full 18 weeks, so being alive longer has value even when the entry
     * eventually takes a fatal loss; this is the per-week building block for [expectedWeeksAlive].
     */
    fun aliveAfterEachWeek(ps: List<Double>, strikesAllowed: Int): List<Double> {
        var dist = doubleArrayOf(1.0)
        val alive = ArrayList<Double>(ps.size)
        for (p in ps) {
            val next = DoubleArray(dist.size + 1)
            for (k in dist.indices) {
                next[k] += dist[k] * p
                next[k + 1] += dist[k] * (1 - p)
            }
            dist = next
            alive += dist.indices.filter { it <= strikesAllowed }.sumOf { dist[it] }
        }
        return alive
    }

    /** Expected number of the sequence's weeks the entry gets through alive: Σ [aliveAfterEachWeek]. */
    fun expectedWeeksAlive(ps: List<Double>, strikesAllowed: Int): Double = aliveAfterEachWeek(ps, strikesAllowed).sum()

    /**
     * Value of a season-path objective for the sequence `ps`, used by both the optimizer's local search and
     * [Route.objectiveValue]. See [RouteObjective] for what each option maximizes.
     */
    fun objectiveValue(ps: List<Double>, strikesAllowed: Int, objective: RouteObjective, horizonWeight: Double): Double = when (objective) {
        RouteObjective.SURVIVE_SEASON -> survive(ps, strikesAllowed)
        RouteObjective.EXPECTED_WEEKS_ALIVE -> expectedWeeksAlive(ps, strikesAllowed)
        RouteObjective.BLENDED -> {
            val n = ps.size
            val normalizedWeeksAlive = if (n > 0) expectedWeeksAlive(ps, strikesAllowed) / n else 0.0
            (1 - horizonWeight) * survive(ps, strikesAllowed) + horizonWeight * normalizedWeeksAlive
        }
    }
}

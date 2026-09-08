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
     * Per-week alive probability of ONE other pool entry, for `k = 1..n` remaining weeks, under a simplified
     * field model: the entry starts the remaining season fresh (zero strikes already used, i.e. a full
     * double-elimination allowance of [strikesAllowedForField]) and its weekly win probability is the flat
     * pool-average [f] every week (we do not model which specific teams the field picks). This is the same
     * [aliveAfterEachWeek] math applied to `n` identical copies of `f`. In reality other entries enter the
     * remaining season with a mix of zero and one strike already used and with their own week-by-week
     * schedules; treating every other entry as fresh and flat is the documented simplification [poolWinProbability]
     * builds on.
     */
    fun fieldAliveCurve(n: Int, f: Double, strikesAllowedForField: Int = 1): List<Double> =
        aliveAfterEachWeek(List(n) { f }, strikesAllowedForField)

    /**
     * `D_0..D_n`: probability that ALL of [poolEntries] `- 1` other entries (each modeled by [fieldAliveCurve])
     * are eliminated by the end of week `k`, for `k = 0..n`. Entries are assumed independent, so
     * `D_k = (1 - a_k)^m` where `a_k` is [fieldAliveCurve]`[k]` (with `a_0 = 1`, nobody has played yet) and
     * `m = poolEntries - 1`. Exposed separately from [poolWinProbability] because [Evaluation.expectedPoolEndWeek]
     * needs the same increments.
     */
    fun otherEntriesEliminatedCdf(n: Int, f: Double, poolEntries: Int, strikesAllowedForField: Int = 1): DoubleArray {
        val m = (poolEntries - 1).coerceAtLeast(0)
        val a = fieldAliveCurve(n, f, strikesAllowedForField)
        val d = DoubleArray(n + 1)
        // (1 - a_0)^m with a_0 = 1: 0^m, which is 0 for m > 0 and (by convention) 1 for m = 0 - no other entries
        // means "all others eliminated" is vacuously true even before week 1.
        d[0] = Math.pow(0.0, m.toDouble())
        for (k in 1..n) d[k] = Math.pow(1.0 - a[k - 1], m.toDouble())
        return d
    }

    /**
     * P(win the pool), for a pool of [poolEntries] total entries (you plus `m = poolEntries - 1` others),
     * where the others are modeled by [fieldAliveCurve] at flat win probability [f]. Two disjoint ways to win:
     *
     * 1. **Outright**: you are alive after week `k` (at most [strikesAllowed] losses so far, per
     *    [aliveAfterEachWeek]) and the field's last survivor is eliminated exactly in week `k`:
     *    `Σ_{k=1..n} you_k · (D_k − D_{k−1})`, `you_0 = 1`. (`you_k` already allows you to have taken a loss in
     *    week `k` itself, as long as you're still within your strike allowance - so a shared "last week"
     *    elimination for both you and the field is correctly excluded: `you_k` is false for you in that case.)
     * 2. **End of season**: you are alive after week `n` sharing the pot with `K ~ Binomial(m, a_n)` other
     *    survivors, an equal share of `1/(1+K)`: `E[1/(1+K)] = (1 − (1 − a_n)^(m+1)) / ((m+1)·a_n)` (closed
     *    form for a Binomial; `1` when `a_n = 0`, i.e. the field is certainly wiped out). `E[1/(1+K)]` includes
     *    `K = 0` (probability `D_n`, i.e. you're the sole survivor at week `n`) which the outright sum already
     *    counted at `k = n`, so the end term is `you_n · (E[1/(1+K)] − D_n)`.
     *
     * [payoutSplit] does not change this formula: whether the pot is physically split `1/(1+K)` ways or
     * awarded by a tiebreaker you'd win with probability `1/(1+K)`, your *expected* share is identical, so the
     * setting is kept only so the UI can explain the assumption; the math here is honest about that and does
     * not branch on it.
     */
    fun poolWinProbability(ps: List<Double>, strikesAllowed: Int, poolEntries: Int, f: Double, payoutSplit: Boolean = true): Double {
        val n = ps.size
        if (n == 0) return 0.0
        val m = (poolEntries - 1).coerceAtLeast(0)
        val you = aliveAfterEachWeek(ps, strikesAllowed)
        val a = fieldAliveCurve(n, f)
        val d = otherEntriesEliminatedCdf(n, f, poolEntries)
        var outright = 0.0
        for (k in 1..n) outright += you[k - 1] * (d[k] - d[k - 1])
        val aN = a.last()
        val expectedInverseSurvivors = if (aN <= 0.0) 1.0 else (1.0 - Math.pow(1.0 - aN, (m + 1).toDouble())) / ((m + 1) * aN)
        val endOfSeason = you[n - 1] * (expectedInverseSurvivors - d[n])
        return outright + endOfSeason
    }

    /**
     * Value of a season-path objective for the sequence `ps`, used by both the optimizer's local search and
     * [Route.objectiveValue]. See [RouteObjective] for what each option maximizes. [poolEntries] and
     * [fieldWinProbability] are only used by [RouteObjective.POOL_WIN].
     */
    fun objectiveValue(
        ps: List<Double>,
        strikesAllowed: Int,
        objective: RouteObjective,
        horizonWeight: Double,
        poolEntries: Int = 50,
        fieldWinProbability: Double = 0.76,
    ): Double = when (objective) {
        RouteObjective.SURVIVE_SEASON -> survive(ps, strikesAllowed)
        RouteObjective.EXPECTED_WEEKS_ALIVE -> expectedWeeksAlive(ps, strikesAllowed)
        RouteObjective.BLENDED -> {
            val n = ps.size
            val normalizedWeeksAlive = if (n > 0) expectedWeeksAlive(ps, strikesAllowed) / n else 0.0
            (1 - horizonWeight) * survive(ps, strikesAllowed) + horizonWeight * normalizedWeeksAlive
        }
        RouteObjective.POOL_WIN -> poolWinProbability(ps, strikesAllowed, poolEntries, fieldWinProbability)
    }
}

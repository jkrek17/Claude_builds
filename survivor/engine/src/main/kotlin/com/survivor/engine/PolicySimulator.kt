package com.survivor.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Everything a [Policy] needs to make one week's decision.
 *
 * [observed] carries the market's noisy read of every remaining team-week's win probability, already
 * restricted to teams still eligible to be picked (not used before the simulation started, not already
 * used earlier in this simulated season, and not locked to a different week). [available] is the same
 * restriction applied to [week] alone - the candidate pool for this week's decision.
 */
data class PolicyContext(
    val week: Int,
    val strikesUsed: Int,
    val available: List<Team>,
    val observed: Map<Int, Map<Team, Double>>,
    /** Picks already recorded by the user for [week] or a later week; never re-decided. */
    val locked: Map<Int, Team>,
) {
    val strikesAllowed: Int get() = (1 - strikesUsed).coerceAtLeast(0)
    val thisWeek: Map<Team, Double> get() = observed[week].orEmpty()
}

/** A decision rule the [PolicySimulator] can drive week-by-week against noisy, re-observed information. */
sealed class Policy(val label: String) {
    abstract fun decide(ctx: PolicyContext): Team?

    /** Highest observed probability this week among available teams - no look-ahead at all. */
    object Greedy : Policy("Greedy (highest win % now)") {
        override fun decide(ctx: PolicyContext): Team? = ctx.thisWeek.maxByOrNull { it.value }?.key
    }

    /**
     * What the app recommends: discount every remaining week by [discountPerWeek] per week ahead, run the
     * season-path optimizer (Hungarian + local search when [localSearch]), and take its pick for this week.
     */
    data class Optimized(val discountPerWeek: Double, val localSearch: Boolean = true) :
        Policy(if (localSearch) "Optimized (discount ${"%.0f".format(discountPerWeek * 100)}%/wk)" else "Zero-loss path (Hungarian only)") {
        override fun decide(ctx: PolicyContext): Team? {
            val cands = candidateTable(ctx, discountPerWeek)
            val route = Optimizer.optimize(cands, ctx.strikesAllowed, locked = ctx.locked, localSearch = localSearch)
            return route.team(ctx.week)
        }
    }

    /**
     * Runs the season-path optimizer with the [RouteObjective.POOL_WIN] objective, for a pool of
     * [poolEntries] entries where the field's flat weekly win probability is [fieldWinProbability]. Does not
     * change any other policy's behavior - each policy decides independently.
     */
    data class PoolWin(val discountPerWeek: Double, val poolEntries: Int = 50, val fieldWinProbability: Double = 0.76) :
        Policy("Pool win ($poolEntries entries, discount ${"%.0f".format(discountPerWeek * 100)}%/wk)") {
        override fun decide(ctx: PolicyContext): Team? {
            val cands = candidateTable(ctx, discountPerWeek)
            val route = Optimizer.optimize(
                cands, ctx.strikesAllowed, locked = ctx.locked, objective = RouteObjective.POOL_WIN,
                poolEntries = poolEntries, fieldWinProbability = fieldWinProbability,
            )
            return route.team(ctx.week)
        }
    }

    /** Hungarian assignment only, undiscounted: maximizes P(zero losses) over the remaining schedule. */
    object ZeroLoss : Policy("Zero-loss path (Hungarian only)") {
        override fun decide(ctx: PolicyContext): Team? {
            val cands = candidateTable(ctx, discountPerWeek = 0.0)
            val route = Optimizer.optimize(cands, ctx.strikesAllowed, locked = ctx.locked, localSearch = false)
            return route.team(ctx.week)
        }
    }

    /**
     * Runs [Optimized] but overrides it with the safest available team when the optimizer's pick is below
     * [minProbability] and a safer team is available - a "don't get cute" guard.
     */
    data class Threshold(val minProbability: Double, val discountPerWeek: Double) :
        Policy("Threshold guard (min ${"%.0f".format(minProbability * 100)}%)") {
        override fun decide(ctx: PolicyContext): Team? {
            val optimized = Optimized(discountPerWeek).decide(ctx) ?: return ctx.thisWeek.maxByOrNull { it.value }?.key
            val optimizedP = ctx.thisWeek[optimized]
            if (optimizedP != null && optimizedP < minProbability) {
                val safest = ctx.thisWeek.maxByOrNull { it.value }
                if (safest != null && safest.value >= minProbability) return safest.key
            }
            return optimized
        }
    }

    /** A caller-supplied decision rule, for policies not built in above. */
    class Custom(label: String, private val rule: (PolicyContext) -> Team?) : Policy(label) {
        override fun decide(ctx: PolicyContext): Team? = rule(ctx)
    }

    companion object {
        /** [ctx.observed], discounted per week-ahead, ready to hand to [Optimizer.optimize]. */
        fun candidateTable(ctx: PolicyContext, discountPerWeek: Double): Map<Int, List<Candidate>> =
            ctx.observed.mapValues { (w, teams) ->
                teams.map { (t, p) -> Candidate(t, Probability.discount(p, w - ctx.week, discountPerWeek)) }
            }
    }
}

/** One policy's outcome, averaged over every simulated season. */
data class PolicyResult(
    val label: String,
    val seasons: Int,
    val surviveSeason: Double,
    val zeroLossFinish: Double,
    val reachWeek10: Double,
    val reachWeek14: Double,
    val reachWeek18: Double,
    val expectedWeeksAlive: Double,
    val expectedStrikes: Double,
    /** Mean elimination week among eliminated seasons; null if the policy was never eliminated. */
    val expectedEliminationWeek: Double?,
)

/** A paired comparison of several policies over the same simulated seasons. */
data class PolicyComparison(
    val results: List<PolicyResult>,
    val seasons: Int,
    val seed: Long,
    val tauBase: Double,
    val tauPerWeek: Double,
) {
    /** Aligned, fixed-width text table - one row per policy. */
    fun summaryTable(): String {
        val headers = listOf("Policy", "Survive", "ZeroLoss", "Wk10", "Wk14", "Wk18", "E[Wks]", "E[Strikes]", "E[ElimWk]")
        fun pct(x: Double) = "%.1f%%".format(x * 100)
        val rows = results.map { r ->
            listOf(
                r.label,
                pct(r.surviveSeason), pct(r.zeroLossFinish), pct(r.reachWeek10), pct(r.reachWeek14), pct(r.reachWeek18),
                "%.2f".format(r.expectedWeeksAlive), "%.2f".format(r.expectedStrikes),
                r.expectedEliminationWeek?.let { "%.1f".format(it) } ?: "-",
            )
        }
        val widths = headers.indices.map { c -> maxOf(headers[c].length, rows.maxOfOrNull { it[c].length } ?: 0) }
        fun line(cells: List<String>) = cells.mapIndexed { i, s -> s.padEnd(widths[i]) }.joinToString("  ").trimEnd()
        val sb = StringBuilder()
        sb.append(line(headers)).append('\n')
        sb.append(widths.joinToString("  ") { "-".repeat(it) }).append('\n')
        rows.forEach { sb.append(line(it)).append('\n') }
        return sb.toString().trimEnd('\n')
    }
}

/**
 * Closed-loop policy simulator: unlike [MonteCarlo], which replays one fixed route, this re-decides every
 * week from freshly observed (noisy) information, the way the app and its user actually behave over a
 * season. See docs/MODEL.md for the probability model this builds on.
 *
 * For every simulated season, a "truth" win probability is sampled for each remaining game around the
 * model's current estimate, and every decision week sees its own noisy *observation* of that truth which
 * converges to it as the game approaches. All policies passed in one call see the *same* simulated seasons
 * (common random numbers), so their results form a paired comparison.
 */
object PolicySimulator {
    const val DEFAULT_TAU_BASE = 0.08
    const val DEFAULT_TAU_PER_WEEK = 0.03
    const val MAX_SEASONS = 50_000

    private data class GameEntry(val gameId: String, val week: Int, val home: Team, val away: Team, val pEstHome: Double)

    fun simulate(
        season: Season,
        user: UserState,
        nowEpochMs: Long,
        policies: List<Policy>,
        seasons: Int,
        seed: Long = 42L,
        tauBase: Double = DEFAULT_TAU_BASE,
        tauPerWeek: Double = DEFAULT_TAU_PER_WEEK,
    ): PolicyComparison {
        require(seasons in 1..MAX_SEASONS) { "seasons must be between 1 and $MAX_SEASONS" }
        require(policies.isNotEmpty()) { "at least one policy is required" }

        val settings = user.settings
        val eval = Evaluator.evaluate(season, user, nowEpochMs)
        val currentWeek = eval.currentWeek
        val strikesUsed0 = eval.strikesUsed
        val usedTeams0 = eval.usedTeams
        val locked0: Map<Int, Team> = user.picks.filter { it.week >= currentWeek }.associate { it.week to it.team }
        val baseExcluded = usedTeams0 + locked0.values

        fun tau(weeksAhead: Int): Double = min(0.5, tauBase + tauPerWeek * weeksAhead)

        // Every remaining team-game, keyed by its (home-perspective) estimated probability.
        val gameEntries: List<GameEntry> = (currentWeek..REGULAR_SEASON_WEEKS).flatMap { w -> season.gamesInWeek(w) }.map { g ->
            val homeEstimate = ProbabilityResolver.resolve(
                g, g.home, isCurrentWeek = g.week <= currentWeek, adjustment = user.adjustment(g.week, g.home),
                ratings = season.ratings, settings = settings,
            )
            GameEntry(g.id, g.week, g.home, g.away, homeEstimate.probability)
        }
        val n = gameEntries.size
        // (team, week) -> game index, for reading off a policy's chosen team's result.
        val gameIndexFor = HashMap<Pair<Team, Int>, Int>(n * 2)
        gameEntries.forEachIndexed { i, g -> gameIndexFor[g.home to g.week] = i; gameIndexFor[g.away to g.week] = i }

        val results = policies.map { MutableAccumulator(it.label, seasons) }

        val rng = Random(seed)
        repeat(seasons) {
            // Common random numbers: draw the season's truth, every week's observation noise, and every
            // game's result uniform ONCE, then replay them identically for each policy below.
            val truthLogitHome = DoubleArray(n) { i ->
                val g = gameEntries[i]
                logit(g.pEstHome) + gaussian(rng) * tau(g.week - currentWeek)
            }
            // obsNoise[w][i]: only drawn where the game (index i) hasn't happened yet as of decision week w.
            val obsNoise = Array(REGULAR_SEASON_WEEKS + 1) { w ->
                DoubleArray(n) { i -> if (w in currentWeek..REGULAR_SEASON_WEEKS && gameEntries[i].week >= w) gaussian(rng) else 0.0 }
            }
            val resultU = DoubleArray(n) { rng.nextDouble() }
            val truthPHome = DoubleArray(n) { i -> sigmoid(truthLogitHome[i]) }

            fun observedTable(w: Int, usedSoFar: Set<Team>): Map<Int, Map<Team, Double>> {
                val out = HashMap<Int, MutableMap<Team, Double>>()
                for (i in 0 until n) {
                    val g = gameEntries[i]
                    if (g.week < w) continue
                    val pObsHome = sigmoid(truthLogitHome[i] + obsNoise[w][i] * tau(g.week - w))
                    val row = out.getOrPut(g.week) { linkedMapOf() }
                    if (g.home !in usedSoFar) row[g.home] = pObsHome
                    if (g.away !in usedSoFar) row[g.away] = 1.0 - pObsHome
                }
                return out
            }

            for ((pi, policy) in policies.withIndex()) {
                var strikes = strikesUsed0
                var weeksPlayed = 0
                var elimWeek: Int? = null
                val usedSoFar = baseExcluded.toMutableSet()
                for (w in currentWeek..REGULAR_SEASON_WEEKS) {
                    val lockedTeam = locked0[w]
                    val team: Team? = if (lockedTeam != null) {
                        lockedTeam
                    } else {
                        val obs = observedTable(w, usedSoFar)
                        val available = obs[w]?.keys?.toList().orEmpty()
                        if (available.isEmpty()) null
                        else {
                            val ctx = PolicyContext(w, strikes, available, obs, locked0.filterKeys { it >= w })
                            policy.decide(ctx)?.takeIf { it in available }
                        }
                    }
                    if (team == null) continue
                    val idx = gameIndexFor[team to w] ?: continue
                    val g = gameEntries[idx]
                    val homeWins = resultU[idx] < truthPHome[idx]
                    val win = (team == g.home) == homeWins
                    weeksPlayed++
                    if (lockedTeam == null) usedSoFar += team
                    if (!win) {
                        strikes++
                        if (strikes >= 2) { elimWeek = w; break }
                    }
                }
                results[pi].add(
                    alive = elimWeek == null,
                    extraStrikes = strikes - strikesUsed0,
                    weeksPlayed = weeksPlayed,
                    elimWeek = elimWeek,
                    reach10 = elimWeek == null || elimWeek >= 10 || currentWeek >= 10,
                    reach14 = elimWeek == null || elimWeek >= 14 || currentWeek >= 14,
                    reach18 = elimWeek == null || elimWeek >= 18 || currentWeek >= 18,
                )
            }
        }

        return PolicyComparison(results.map { it.result() }, seasons, seed, tauBase, tauPerWeek)
    }

    private fun logit(p: Double): Double = ln(p / (1.0 - p))
    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    /** Standard normal draw via Box-Muller, built on [Random.nextDouble] so it stays deterministic per seed. */
    private fun gaussian(rng: Random): Double {
        var u1: Double
        do { u1 = rng.nextDouble() } while (u1 <= 1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /** Running totals for one policy across all simulated seasons. */
    private class MutableAccumulator(val label: String, val seasons: Int) {
        var survive = 0
        var zeroLoss = 0
        var reach10 = 0
        var reach14 = 0
        var reach18 = 0
        var weeksSum = 0L
        var strikeSum = 0L
        var elimWeekSum = 0L
        var elimCount = 0

        fun add(alive: Boolean, extraStrikes: Int, weeksPlayed: Int, elimWeek: Int?, reach10: Boolean, reach14: Boolean, reach18: Boolean) {
            if (alive) survive++
            if (alive && extraStrikes == 0) zeroLoss++
            if (reach10) this.reach10++
            if (reach14) this.reach14++
            if (reach18) this.reach18++
            weeksSum += weeksPlayed
            strikeSum += extraStrikes.toLong().coerceAtLeast(0)
            if (elimWeek != null) { elimWeekSum += elimWeek; elimCount++ }
        }

        fun result(): PolicyResult {
            val n = seasons.toDouble()
            return PolicyResult(
                label = label,
                seasons = seasons,
                surviveSeason = survive / n,
                zeroLossFinish = zeroLoss / n,
                reachWeek10 = reach10 / n,
                reachWeek14 = reach14 / n,
                reachWeek18 = reach18 / n,
                expectedWeeksAlive = weeksSum / n,
                expectedStrikes = strikeSum / n,
                expectedEliminationWeek = if (elimCount > 0) elimWeekSum.toDouble() / elimCount else null,
            )
        }
    }
}

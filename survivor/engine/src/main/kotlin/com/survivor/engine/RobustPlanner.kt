package com.survivor.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** One team's share of a given week's scenario outcomes. */
data class TeamShare(val team: Team, val share: Double)

/**
 * Result of [RobustPlanner.plan]: how sensitive the optimizer's route is to lookahead-line noise.
 */
data class StabilityReport(
    val currentWeek: Int,
    val scenarios: Int,
    val seed: Long,
    /** Fraction of scenarios in which each team is the route's current-week pick. */
    val currentWeekShares: Map<Team, Double>,
    /** Per week: the teams chosen across scenarios and their share (top entries), i.e. route stability per week. */
    val weekConsensus: Map<Int, List<TeamShare>>,
    /** Average, over weeks [currentWeek]..18, of that week's top team's share. 1.0 = a perfectly stable route. */
    val routeStability: Double,
    /**
     * For each team playing this week: the mean, over scenarios, of the survival probability of the best
     * route with that team LOCKED in the current week — the "robust" expected value of picking it now.
     * Empty unless `computeLockedValues` was requested.
     */
    val teamScenarioValue: Map<Team, Double>,
    /** argmax of [teamScenarioValue]; falls back to the highest [currentWeekShares] entry when that's empty. */
    val robustPick: Team?,
) {
    fun summary(): String = buildString {
        appendLine("Robust plan: $scenarios scenarios (seed $seed), Week $currentWeek")
        appendLine("Route stability: ${pct(routeStability)}")
        val ranked = currentWeekShares.entries.sortedByDescending { it.value }
        ranked.firstOrNull()?.let { appendLine("Week $currentWeek consensus pick: ${it.key.abbr} (${pct(it.value)} of scenarios)") }
        robustPick?.let { appendLine("Robust pick (best mean locked value): ${it.abbr}") }
        if (ranked.size > 1) {
            appendLine("Week $currentWeek shares:")
            ranked.take(6).forEach { (t, s) -> appendLine("  ${t.abbr}: ${pct(s)}") }
        }
    }

    private fun pct(x: Double): String = "${"%.0f".format(x * 100.0)}%"
}

/**
 * Runs the season optimizer over many perturbed versions of the remaining lookahead lines to see how
 * fragile the resulting route is: whether a plausible line move would swap the current-week pick, and
 * (optionally) the mean value of locking in each current-week candidate across those scenarios.
 *
 * Current week, strikes, used teams and locked picks are resolved exactly like [Strategies.compare]; base
 * probabilities come from [ProbabilityResolver.resolve] the same way. Each remaining game gets one noise
 * draw per scenario, in logit space, applied to the home team's win probability (away = 1 − p); the noise
 * standard deviation [tau] grows with weeks ahead so distant lookahead lines move more than the current
 * week's. Deterministic for a given [seed].
 */
object RobustPlanner {

    fun plan(
        season: Season,
        user: UserState,
        nowEpochMs: Long,
        scenarios: Int = 200,
        seed: Long = 42L,
        tauBase: Double = 0.08,
        tauPerWeek: Double = 0.03,
        computeLockedValues: Boolean = true,
        /** Optimizer runs for the locked-value pass are candidates × this; keep it smaller than [scenarios] to bound runtime. */
        lockedValueScenarios: Int = 100,
    ): StabilityReport {
        val settings = user.settings
        val eval = Evaluator.evaluate(season, user, nowEpochMs)
        val currentWeek = eval.currentWeek
        val strikesAllowed = eval.strikesAllowed
        val locked = user.picks.filter { it.week >= currentWeek }.associate { it.week to it.team }
        val available = Team.entries.filter { it !in eval.usedTeams || it in locked.values }.toSet()

        fun raw(team: Team, week: Int): ProbabilityEstimate? {
            val g = season.gameFor(team, week) ?: return null
            return ProbabilityResolver.resolve(g, team, week <= currentWeek, user.adjustment(week, team), season.ratings, settings)
        }

        fun tau(weeksAhead: Int): Double = minOf(0.5, tauBase + tauPerWeek * weeksAhead)

        // One noise slot per remaining game that still has an available side, with its base (home) probability.
        data class Slot(val week: Int, val home: Team, val away: Team, val baseHomeP: Double)

        val slots = (currentWeek..REGULAR_SEASON_WEEKS).flatMap { w ->
            season.gamesInWeek(w).mapNotNull { g ->
                if (g.home !in available && g.away !in available) return@mapNotNull null
                val homeEst = raw(g.home, w)
                val awayEst = raw(g.away, w)
                val baseHomeP = when {
                    homeEst != null && homeEst.source != ProbabilitySource.NONE -> homeEst.probability
                    awayEst != null && awayEst.source != ProbabilitySource.NONE -> 1.0 - awayEst.probability
                    else -> null
                } ?: return@mapNotNull null
                Slot(w, g.home, g.away, baseHomeP)
            }
        }.sortedWith(compareBy({ it.week }, { it.home.abbr }))

        fun logit(p: Double): Double = ln(p / (1.0 - p))
        fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

        val rng = Random(seed)
        fun perturbedCandidates(): Map<Int, List<Candidate>> {
            val byWeek = HashMap<Int, MutableList<Candidate>>()
            for (slot in slots) {
                val k = slot.week - currentWeek
                val u = rng.nextDouble().coerceIn(1e-9, 1.0 - 1e-9)
                val noise = Probability.inverseNormalCdf(u) * tau(k)
                val homeP = sigmoid(logit(slot.baseHomeP) + noise).coerceIn(0.01, 0.99)
                val awayP = 1.0 - homeP
                if (slot.home in available) {
                    byWeek.getOrPut(slot.week) { mutableListOf() }
                        .add(Candidate(slot.home, Probability.discount(homeP, k, settings.futureDiscountPerWeek)))
                }
                if (slot.away in available) {
                    byWeek.getOrPut(slot.week) { mutableListOf() }
                        .add(Candidate(slot.away, Probability.discount(awayP, k, settings.futureDiscountPerWeek)))
                }
            }
            return byWeek
        }

        // Every scenario's perturbed candidate table, generated once so the locked-value pass can reuse a subset.
        val scenarioCandidates = (0 until scenarios).map { perturbedCandidates() }

        val currentWeekCounts = HashMap<Team, Int>()
        val weekCounts = HashMap<Int, HashMap<Team, Int>>()
        for (cands in scenarioCandidates) {
            val route = Optimizer.optimize(cands, strikesAllowed, locked)
            for (w in currentWeek..REGULAR_SEASON_WEEKS) {
                val t = route.team(w) ?: continue
                weekCounts.getOrPut(w) { HashMap() }.merge(t, 1, Int::plus)
            }
            route.team(currentWeek)?.let { currentWeekCounts.merge(it, 1, Int::plus) }
        }
        val n = scenarios.coerceAtLeast(1).toDouble()
        val currentWeekShares: Map<Team, Double> = currentWeekCounts.mapValues { it.value / n }
        val weekConsensus: Map<Int, List<TeamShare>> = (currentWeek..REGULAR_SEASON_WEEKS).associateWith { w ->
            weekCounts[w].orEmpty().entries.sortedByDescending { it.value }.take(6).map { TeamShare(it.key, it.value / n) }
        }
        val routeStability: Double = weekConsensus.values
            .mapNotNull { list -> list.maxByOrNull { it.share }?.share }
            .let { if (it.isEmpty()) 0.0 else it.average() }

        val teamScenarioValue: Map<Team, Double> = if (computeLockedValues) {
            val currentWeekCandidates = slots.filter { it.week == currentWeek }
                .flatMap { listOf(it.home, it.away) }
                .filter { it in available }
                .distinct()
            val subset = scenarioCandidates.take(lockedValueScenarios.coerceIn(1, scenarios))
            currentWeekCandidates.associateWith { team ->
                val lockedHere = locked + (currentWeek to team)
                subset.map { cands -> Optimizer.optimize(cands, strikesAllowed, lockedHere).survival }.average()
            }
        } else emptyMap()

        val robustPick: Team? = teamScenarioValue.maxByOrNull { it.value }?.key
            ?: currentWeekShares.maxByOrNull { it.value }?.key

        return StabilityReport(
            currentWeek = currentWeek,
            scenarios = scenarios,
            seed = seed,
            currentWeekShares = currentWeekShares,
            weekConsensus = weekConsensus,
            routeStability = routeStability,
            teamScenarioValue = teamScenarioValue,
            robustPick = robustPick,
        )
    }
}

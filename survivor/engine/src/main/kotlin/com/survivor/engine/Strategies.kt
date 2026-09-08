package com.survivor.engine

/** Builds the comparison routes for the Monte Carlo tab. All share one probability table. */
object Strategies {
    const val HIGHEST_WIN = "Highest win % each week"
    const val FUTURE_VALUE = "Future-value optimized"
    const val ZERO_LOSS = "Zero-loss path (undiscounted)"
    const val CONTRARIAN = "Contrarian (ownership-nudged)"

    fun compare(season: Season, user: UserState, nowEpochMs: Long, iterations: Int? = null, seed: Long = 42L): List<SimulationResult> {
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
        fun table(discount: Boolean, contrarian: Boolean): Map<Int, List<Candidate>> = (currentWeek..REGULAR_SEASON_WEEKS).associateWith { w ->
            available.mapNotNull { t ->
                val e = raw(t, w) ?: return@mapNotNull null
                if (e.source == ProbabilitySource.NONE) return@mapNotNull null
                var p = if (discount) Probability.discount(e.probability, w - currentWeek, settings.futureDiscountPerWeek) else e.probability
                if (contrarian) {
                    val lev = Safety.leverage(e.probability, user.adjustment(w, t)?.estimatedPickShare, settings.fieldAverageWinProbability)
                    if (lev != null) p = (p + 0.25 * (lev.expectedEquity - 1.0)).coerceIn(0.01, 0.99)
                }
                Candidate(t, p)
            }
        }
        fun rawRoute(route: Route) = Route(route.steps.map { s -> s.copy(probability = raw(s.team, s.week)?.probability ?: s.probability) }, strikesAllowed)

        val n = iterations ?: settings.monteCarloIterations
        val routes = listOf(
            HIGHEST_WIN to Optimizer.greedy(table(discount = false, contrarian = false), strikesAllowed, locked),
            FUTURE_VALUE to Optimizer.optimize(table(discount = true, contrarian = false), strikesAllowed, locked),
            ZERO_LOSS to Optimizer.optimize(table(discount = false, contrarian = false), strikesAllowed, locked, localSearch = false),
            CONTRARIAN to Optimizer.optimize(table(discount = true, contrarian = true), strikesAllowed, locked),
        )
        return routes.map { (name, r) -> MonteCarlo.simulate(name, rawRoute(r), eval.strikesUsed, n, seed) }
    }
}

package com.survivor.engine

/** Outcome of a recorded pick, derived automatically from the schedule's final scores. */
data class PickOutcome(val pick: Pick, val game: Game?, val result: PickResult) {
    val isStrike: Boolean get() = result == PickResult.LOSS || result == PickResult.TIE
}

data class TeamWeekEvaluation(
    val week: Int,
    val team: Team,
    val game: Game,
    val opponent: Team,
    val situation: Situation,
    val estimate: ProbabilityEstimate,
    val used: Boolean,
    val futureValue: FutureValue,
    /** Relative loss (%) of the remaining path's survival probability if this team is used now. */
    val opportunityCost: Double,
    val components: SafetyComponents,
    val leverage: Leverage?,
    val adjustment: Adjustment?,
    val rank: Int,
) {
    val probability: Double get() = estimate.probability
    val safetyScore: Double get() = components.total
    val grade: String get() = Safety.grade(safetyScore)
    val tier: Tier get() = Safety.tier(safetyScore)
    val futureCostLabel: String get() = Safety.futureCostLabel(opportunityCost)
}

enum class PlannerStatus(val label: String) {
    LOCKED("Locked"), RECOMMENDED("Recommended"), PROJECTED("Projected"), MISSED("No pick recorded"), NO_DATA("No data")
}

data class PlannerRow(
    val week: Int,
    val team: Team?,
    val opponent: Team?,
    val isHome: Boolean?,
    val teamSpread: Double?,
    val probability: Double?,
    val source: ProbabilitySource?,
    val status: PlannerStatus,
    val result: PickResult?,
    val strikesAfter: Int,
    val bestFuture: FutureGame?,
    val opportunityCost: Double?,
) {
    val grade: String? get() = probability?.let { Safety.grade(it * 100) }
}

data class GridCell(
    val week: Int,
    val team: Team,
    val opponent: Team,
    val isHome: Boolean,
    val neutral: Boolean,
    val teamSpread: Double?,
    val probability: Double,
    val source: ProbabilitySource,
    val state: GameState,
    val won: Boolean?,
    val pickedHere: Boolean,
    val isPast: Boolean,
)

data class Explanation(
    val headline: String,
    val whySafe: String,
    val whyNow: String,
    val whatWeGiveUp: String,
    val futureValueWarning: String?,
    val alternatives: List<Pair<TeamWeekEvaluation, String>>,
)

data class Evaluation(
    val season: Season,
    val currentWeek: Int,
    val strikesUsed: Int,
    val eliminated: Boolean,
    val usedTeams: Set<Team>,
    val pickOutcomes: List<PickOutcome>,
    val currentPick: Pick?,
    /** Available teams playing this week, ranked by Safety Score. */
    val rankings: List<TeamWeekEvaluation>,
    /** Teams playing this week that are already used (shown greyed out). */
    val unavailable: List<TeamWeekEvaluation>,
    val recommended: TeamWeekEvaluation?,
    val alternatives: List<TeamWeekEvaluation>,
    val explanation: Explanation?,
    /** Optimized route from the current week to Week 18 (discounted probabilities inside the optimizer). */
    val route: Route,
    /** Undiscounted probabilities along the route, for season survival reporting. */
    val routeRawProbabilities: List<Double>,
    val planner: List<PlannerRow>,
    val grid: Map<Team, List<GridCell?>>,
    val futureValues: Map<Team, FutureValue>,
    val settings: ModelSettings,
) {
    val strikesAllowed: Int get() = (1 - strikesUsed).coerceAtLeast(0)
    val seasonZeroLoss: Double get() = Survival.zeroLoss(routeRawProbabilities)
    val seasonAtMostOneLoss: Double get() = Survival.zeroLoss(routeRawProbabilities) + Survival.exactlyOneLoss(routeRawProbabilities)
    val seasonSurvival: Double get() = Survival.survive(routeRawProbabilities, strikesAllowed)
}

/**
 * Runs the whole model for one snapshot of data + user state. Pure and deterministic: the app calls it
 * whenever the season data, picks, adjustments or settings change.
 */
object Evaluator {

    fun pickOutcome(season: Season, pick: Pick): PickOutcome {
        val game = season.gameFor(pick.team, pick.week)
        val result = when {
            game == null -> PickResult.PENDING
            game.state != GameState.FINAL -> PickResult.PENDING
            game.isTie -> PickResult.TIE
            game.winner == pick.team -> PickResult.WIN
            else -> PickResult.LOSS
        }
        return PickOutcome(pick, game, result)
    }

    fun evaluate(season: Season, user: UserState, nowEpochMs: Long): Evaluation {
        val settings = user.settings.forStrategy(user.settings.strategy)
        val currentWeek = (user.weekOverride ?: season.inferCurrentWeek(nowEpochMs)).coerceIn(1, REGULAR_SEASON_WEEKS)
        val outcomes = user.picks.sortedBy { it.week }.map { pickOutcome(season, it) }
        val strikesUsed = outcomes.count { it.isStrike }
        val strikesAllowed = (1 - strikesUsed).coerceAtLeast(0)
        val usedTeams = user.usedTeams()
        val currentPick = user.pickFor(currentWeek)

        // Probability of every team in every week, resolved once.
        val estimates: Map<Team, Map<Int, ProbabilityEstimate>> = Team.entries.associateWith { team ->
            (1..REGULAR_SEASON_WEEKS).mapNotNull { w ->
                val g = season.gameFor(team, w) ?: return@mapNotNull null
                w to ProbabilityResolver.resolve(g, team, isCurrentWeek = w <= currentWeek, adjustment = user.adjustment(w, team), ratings = season.ratings, settings = settings)
            }.toMap()
        }

        fun futureValue(team: Team, afterWeek: Int): FutureValue = FutureValue(
            team,
            estimates.getValue(team).filter { (w, _) -> w > afterWeek }.map { (w, e) ->
                val g = season.gameFor(team, w)!!
                FutureGame(w, g.opponentOf(team), g.isHome(team), e.probability, e.source)
            }.sortedBy { it.week },
        )
        val futureValues = Team.entries.associateWith { futureValue(it, currentWeek) }

        // Candidates for the optimizer: discounted by distance from the current week.
        fun candidates(fromWeek: Int, excluded: Set<Team>): Map<Int, List<Candidate>> =
            (fromWeek..REGULAR_SEASON_WEEKS).associateWith { w ->
                Team.entries.filter { it !in excluded }.mapNotNull { t ->
                    val e = estimates.getValue(t)[w] ?: return@mapNotNull null
                    if (e.source == ProbabilitySource.NONE) return@mapNotNull null
                    Candidate(t, Probability.discount(e.probability, w - currentWeek, settings.futureDiscountPerWeek))
                }
            }

        // Locked picks for the current and any future week the user has already recorded.
        val locked = user.picks.filter { it.week >= currentWeek }.associate { it.week to it.team }
        val routeCandidates = candidates(currentWeek, usedTeams - locked.values.toSet())
        val route = Optimizer.optimize(routeCandidates, strikesAllowed, locked)

        // Opportunity cost of using each team this week = relative loss of the future path's survival.
        val futureWeek = currentWeek + 1
        val futureLocked = locked.filterKeys { it > currentWeek }
        val pathWith = if (futureWeek <= REGULAR_SEASON_WEEKS) Optimizer.optimize(candidates(futureWeek, usedTeams), strikesAllowed, futureLocked).survival else 1.0
        fun opportunityCost(team: Team): Double {
            if (futureWeek > REGULAR_SEASON_WEEKS || pathWith <= 0.0) return 0.0
            val without = Optimizer.optimize(candidates(futureWeek, usedTeams + team), strikesAllowed, futureLocked).survival
            return ((1.0 - without / pathWith) * 100.0).coerceAtLeast(0.0)
        }

        val weekGames = season.gamesInWeek(currentWeek)
        val evaluated = weekGames.flatMap { g -> listOf(g.home, g.away).map { t -> t to g } }.map { (team, game) ->
            val estimate = estimates.getValue(team).getValue(currentWeek)
            val situation = ScheduleAnalysis.situation(season, team, game)
            val adjustment = user.adjustment(currentWeek, team)
            val used = team in usedTeams && currentPick?.team != team
            val cost = if (used) 0.0 else opportunityCost(team)
            val fv = futureValues.getValue(team)
            val leverage = Safety.leverage(estimate.probability, adjustment?.estimatedPickShare, settings.fieldAverageWinProbability)
            val components = Safety.components(estimate, situation, adjustment, cost, fv.premiumSpots, leverage, strikesUsed, settings)
            TeamWeekEvaluation(currentWeek, team, game, game.opponentOf(team), situation, estimate, used, fv, cost, components, leverage, adjustment, rank = 0)
        }
        val rankKey: (TeamWeekEvaluation) -> Double = { e ->
            if (settings.strategy == Strategy.MAX_POOL_EQUITY && e.leverage != null) e.leverage.expectedEquity * 100.0 else e.safetyScore
        }
        val ranked = evaluated.filter { !it.used }.sortedByDescending(rankKey).mapIndexed { i, e -> e.copy(rank = i + 1) }
        val unavailable = evaluated.filter { it.used }.sortedByDescending { it.probability }

        val recommended = currentPick?.let { p -> ranked.firstOrNull { it.team == p.team } } ?: ranked.firstOrNull()
        val alternatives = ranked.filter { it.team != recommended?.team }.take(3)
        val explanation = recommended?.let { Explain.build(it, alternatives, ranked, strikesUsed, settings) }

        // Planner
        var strikes = 0
        val planner = (1..REGULAR_SEASON_WEEKS).map { w ->
            val outcome = outcomes.firstOrNull { it.pick.week == w }
            if (outcome != null) {
                if (outcome.isStrike) strikes++
                val t = outcome.pick.team
                val g = outcome.game
                val e = g?.let { estimates.getValue(t)[w] }
                PlannerRow(w, t, g?.opponentOf(t), g?.isHome(t), e?.teamSpread, e?.probability, e?.source, PlannerStatus.LOCKED, outcome.result, strikes, futureValue(t, w).best, null)
            } else if (w < currentWeek) {
                PlannerRow(w, null, null, null, null, null, null, PlannerStatus.MISSED, null, strikes, null, null)
            } else {
                val step = route.steps.firstOrNull { it.week == w }
                if (step == null) PlannerRow(w, null, null, null, null, null, null, PlannerStatus.NO_DATA, null, strikes, null, null)
                else {
                    val t = step.team
                    val g = season.gameFor(t, w)!!
                    val e = estimates.getValue(t).getValue(w)
                    val cost = if (w == currentWeek) ranked.firstOrNull { it.team == t }?.opportunityCost
                    else plannerOpportunityCost(w, t, route, usedTeams, strikesAllowed, ::candidates)
                    PlannerRow(w, t, g.opponentOf(t), g.isHome(t), e.teamSpread, e.probability, e.source,
                        if (w == currentWeek) PlannerStatus.RECOMMENDED else PlannerStatus.PROJECTED, null, strikes, futureValue(t, w).best, cost)
                }
            }
        }

        // Schedule grid
        val grid = Team.entries.associateWith { team ->
            (1..REGULAR_SEASON_WEEKS).map { w ->
                val g = season.gameFor(team, w) ?: return@map null
                val e = estimates.getValue(team).getValue(w)
                GridCell(w, team, g.opponentOf(team), g.isHome(team), g.neutralSite, e.teamSpread, e.probability, e.source, g.state,
                    g.winner?.let { it == team }, user.pickFor(w)?.team == team, w < currentWeek)
            }
        }

        val routeRaw = route.steps.map { s -> estimates.getValue(s.team)[s.week]?.probability ?: s.probability }

        return Evaluation(
            season = season, currentWeek = currentWeek, strikesUsed = strikesUsed, eliminated = strikesUsed >= 2,
            usedTeams = usedTeams, pickOutcomes = outcomes, currentPick = currentPick,
            rankings = ranked, unavailable = unavailable, recommended = recommended, alternatives = alternatives, explanation = explanation,
            route = route, routeRawProbabilities = routeRaw, planner = planner, grid = grid, futureValues = futureValues, settings = settings,
        )
    }

    /** Opportunity cost for a projected future week: how much the path after [week] loses without [team]. */
    private fun plannerOpportunityCost(
        week: Int,
        team: Team,
        route: Route,
        usedTeams: Set<Team>,
        strikesAllowed: Int,
        candidates: (Int, Set<Team>) -> Map<Int, List<Candidate>>,
    ): Double? {
        val next = week + 1
        if (next > REGULAR_SEASON_WEEKS) return 0.0
        val consumed = usedTeams + route.steps.filter { it.week < week }.map { it.team }
        val with = Optimizer.optimize(candidates(next, consumed), strikesAllowed, localSearch = false).survival
        if (with <= 0.0) return null
        val without = Optimizer.optimize(candidates(next, consumed + team), strikesAllowed, localSearch = false).survival
        return ((1.0 - without / with) * 100.0).coerceAtLeast(0.0)
    }
}

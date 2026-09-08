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
    /** Relative loss (%) of modeled season survival when this team is locked in now vs. the optimizer's unconstrained best path. */
    val seasonPathLoss: Double,
    /** True when the unconstrained optimizer also uses this team in the current week. */
    val onOptimalPath: Boolean,
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
    /** Optimized route from the current week to Week 18, with the recommended (or recorded) pick locked in for this week. */
    val route: Route,
    /** The optimizer's best path with no current-week constraint; differs from [route] only when the Safety ranking disagrees. */
    val unconstrainedRoute: Route,
    /** Undiscounted probabilities along the route, for season survival reporting. */
    val routeRawProbabilities: List<Double>,
    val planner: List<PlannerRow>,
    val grid: Map<Team, List<GridCell?>>,
    val futureValues: Map<Team, FutureValue>,
    val settings: ModelSettings,
    /** Pick-share-weighted average win probability of the field's current-week picks, `Σ share_t·p_t / Σ share_t`
     *  over teams playing this week with a known effective pick share (see `Evaluator.effectivePickShare`).
     *  Null when no pick shares are known for [currentWeek] at all. Overrides [settings.fieldAverageWinProbability]
     *  for the current week only in [poolWinProbability] and route optimization; see [Survival.poolWinProbability]. */
    val fieldWinProbabilityThisWeek: Double? = null,
    /** Where [fieldWinProbabilityThisWeek] came from (e.g. [Season.pickSharesSource]); null when it's null. */
    val ownershipSource: String? = null,
    val ownershipFetchedAtEpochMs: Long? = null,
) {
    val strikesAllowed: Int get() = (1 - strikesUsed).coerceAtLeast(0)
    val seasonZeroLoss: Double get() = Survival.zeroLoss(routeRawProbabilities)
    val seasonAtMostOneLoss: Double get() = Survival.zeroLoss(routeRawProbabilities) + Survival.exactlyOneLoss(routeRawProbabilities)
    val seasonSurvival: Double get() = Survival.survive(routeRawProbabilities, strikesAllowed)
    /** Expected number of the route's remaining weeks the entry gets through alive. */
    val seasonExpectedWeeksAlive: Double get() = Survival.expectedWeeksAlive(routeRawProbabilities, strikesAllowed)
    /** Per-week field win probability aligned to [route]'s weeks: [fieldWinProbabilityThisWeek] for
     *  [currentWeek] (when known), [settings.fieldAverageWinProbability] elsewhere. Null (flat model,
     *  unchanged) when [fieldWinProbabilityThisWeek] is null. */
    private val routeFieldWinProbabilities: List<Double>?
        get() = fieldWinProbabilityThisWeek?.let { fw ->
            route.steps.sortedBy { it.week }.map { s -> if (s.week == currentWeek) fw else settings.fieldAverageWinProbability }
        }
    /** P(win the pool) along [route], undiscounted; see [Survival.poolWinProbability]. Independent of
     *  [settings.routeObjective] - always reported, regardless of what the optimizer is climbing. Uses
     *  [fieldWinProbabilityThisWeek] for the current week when known, the flat field average otherwise. */
    val poolWinProbability: Double
        get() = Survival.poolWinProbability(
            routeRawProbabilities, strikesAllowed, settings.poolEntries, settings.fieldAverageWinProbability,
            fieldWinProbabilities = routeFieldWinProbabilities,
        )
    /** For each week along [route], the expected number of OTHER pool entries still alive after that week
     *  (`m · a_k`; see [Survival.fieldAliveCurve]). Non-increasing by construction. */
    val expectedFieldSurvivors: List<Pair<Int, Double>>
        get() {
            val n = routeRawProbabilities.size
            if (n == 0) return emptyList()
            val weeks = route.steps.sortedBy { it.week }.map { it.week }
            val m = (settings.poolEntries - 1).coerceAtLeast(0)
            val a = Survival.fieldAliveCurve(n, settings.fieldAverageWinProbability)
            return weeks.zip(a) { w, ak -> w to m * ak }
        }
    /**
     * Expected week in which the field's last other entry is eliminated, from the increments of
     * [Survival.otherEntriesEliminatedCdf]: `Σ_{k=1..n} week(k) · (D_k − D_{k−1}) + (lastWeek+1) · (1 − D_n)`.
     * The `lastWeek + 1` term is a "survives past the route" convention - it is not a real week number, just a
     * marker that the field never fully clears out within the route. Null only when the route is empty.
     */
    val expectedPoolEndWeek: Double?
        get() {
            val n = routeRawProbabilities.size
            if (n == 0) return null
            val weeks = route.steps.sortedBy { it.week }.map { it.week }
            val d = Survival.otherEntriesEliminatedCdf(n, settings.fieldAverageWinProbability, settings.poolEntries)
            var expectation = 0.0
            for (k in 1..n) expectation += weeks[k - 1] * (d[k] - d[k - 1])
            expectation += (weeks.last() + 1) * (1.0 - d[n])
            return expectation
        }
}

/**
 * Runs the whole model for one snapshot of data + user state. Pure and deterministic: the app calls it
 * whenever the season data, picks, adjustments or settings change.
 */
object Evaluator {

    /**
     * Effective pick share for one team-week: a manual [Adjustment.estimatedPickShare] always wins when set,
     * else [Season.pickShares] (Yahoo Survival Football), else null (no ownership estimate at all).
     */
    fun effectivePickShare(season: Season, user: UserState, week: Int, team: Team): Double? =
        user.adjustment(week, team)?.estimatedPickShare ?: season.pickShares[week]?.get(team)

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

        // Field model: this week's pick-weighted field win probability, from effective pick shares (manual
        // adjustment, else Yahoo) over the teams actually playing this week. Null with no shares at all.
        val weekGames = season.gamesInWeek(currentWeek)
        val weekTeams = weekGames.flatMap { g -> listOf(g.home, g.away) }
        val currentWeekShares = weekTeams.mapNotNull { t -> effectivePickShare(season, user, currentWeek, t)?.let { t to it.coerceIn(0.0, 1.0) } }.toMap()
        val shareSum = currentWeekShares.values.sum()
        val fieldWinProbabilityThisWeek: Double? = if (currentWeekShares.isEmpty() || shareSum <= 0.0) null else {
            val weighted = currentWeekShares.entries.sumOf { (t, share) -> share * (estimates.getValue(t)[currentWeek]?.probability ?: 0.0) }
            (weighted / shareSum).coerceIn(0.0, 1.0)
        }
        val fieldWinProbabilitiesForRoute: Map<Int, Double>? = fieldWinProbabilityThisWeek?.let { mapOf(currentWeek to it) }
        fun routeFieldProbs(route: Route): List<Double>? = fieldWinProbabilitiesForRoute?.let { m ->
            route.steps.sortedBy { it.week }.map { s -> m[s.week] ?: settings.fieldAverageWinProbability }
        }
        val yahooSharesForWeek = season.pickShares[currentWeek]
        val ownershipSource = if (!yahooSharesForWeek.isNullOrEmpty()) season.pickSharesSource.ifBlank { null } else null
        val ownershipFetchedAtEpochMs = if (!yahooSharesForWeek.isNullOrEmpty()) season.pickSharesFetchedAtEpochMs else null

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
        val unconstrainedRoute = Optimizer.optimize(
            routeCandidates, strikesAllowed, locked, objective = settings.routeObjective, horizonWeight = settings.horizonWeight,
            poolEntries = settings.poolEntries, fieldWinProbability = settings.fieldAverageWinProbability, fieldWinProbabilities = fieldWinProbabilitiesForRoute,
        )
        fun routeWith(team: Team): Route = if (currentPick != null) unconstrainedRoute else Optimizer.optimize(
            routeCandidates, strikesAllowed, locked + (currentWeek to team), objective = settings.routeObjective, horizonWeight = settings.horizonWeight,
            poolEntries = settings.poolEntries, fieldWinProbability = settings.fieldAverageWinProbability, fieldWinProbabilities = fieldWinProbabilitiesForRoute,
        )
        val unconstrainedObjective = unconstrainedRoute.objectiveValue(settings.routeObjective, settings.horizonWeight, settings.poolEntries, settings.fieldAverageWinProbability, routeFieldProbs(unconstrainedRoute))
        fun seasonPathLoss(team: Team): Double {
            if (unconstrainedRoute.team(currentWeek) == team || unconstrainedObjective <= 0.0) return 0.0
            val withRoute = routeWith(team)
            val withTeam = withRoute.objectiveValue(settings.routeObjective, settings.horizonWeight, settings.poolEntries, settings.fieldAverageWinProbability, routeFieldProbs(withRoute))
            return ((1.0 - withTeam / unconstrainedObjective) * 100.0).coerceAtLeast(0.0)
        }

        // Opportunity cost of using each team this week = relative loss of the future path's objective value.
        val futureWeek = currentWeek + 1
        val futureLocked = locked.filterKeys { it > currentWeek }
        val pathWith = if (futureWeek <= REGULAR_SEASON_WEEKS) {
            Optimizer.optimize(
                candidates(futureWeek, usedTeams), strikesAllowed, futureLocked, objective = settings.routeObjective, horizonWeight = settings.horizonWeight,
                poolEntries = settings.poolEntries, fieldWinProbability = settings.fieldAverageWinProbability,
            ).objectiveValue(settings.routeObjective, settings.horizonWeight, settings.poolEntries, settings.fieldAverageWinProbability)
        } else 1.0
        fun opportunityCost(team: Team): Double {
            if (futureWeek > REGULAR_SEASON_WEEKS || pathWith <= 0.0) return 0.0
            val without = Optimizer.optimize(
                candidates(futureWeek, usedTeams + team), strikesAllowed, futureLocked, objective = settings.routeObjective, horizonWeight = settings.horizonWeight,
                poolEntries = settings.poolEntries, fieldWinProbability = settings.fieldAverageWinProbability,
            ).objectiveValue(settings.routeObjective, settings.horizonWeight, settings.poolEntries, settings.fieldAverageWinProbability)
            return ((1.0 - without / pathWith) * 100.0).coerceAtLeast(0.0)
        }

        val evaluated = weekGames.flatMap { g -> listOf(g.home, g.away).map { t -> t to g } }.map { (team, game) ->
            val estimate = estimates.getValue(team).getValue(currentWeek)
            val situation = ScheduleAnalysis.situation(season, team, game)
            val adjustment = user.adjustment(currentWeek, team)
            val used = team in usedTeams && currentPick?.team != team
            val cost = if (used) 0.0 else opportunityCost(team)
            val pathLoss = if (used) 0.0 else seasonPathLoss(team)
            val fv = futureValues.getValue(team)
            val leverage = Safety.leverage(estimate.probability, effectivePickShare(season, user, currentWeek, team), settings.fieldAverageWinProbability)
            val components = Safety.components(estimate, situation, adjustment, cost, pathLoss, fv.premiumSpots, leverage, strikesUsed, settings)
            TeamWeekEvaluation(currentWeek, team, game, game.opponentOf(team), situation, estimate, used, fv, cost, pathLoss, unconstrainedRoute.team(currentWeek) == team, components, leverage, adjustment, rank = 0)
        }
        val rankKey: (TeamWeekEvaluation) -> Double = { e ->
            if (settings.strategy == Strategy.MAX_POOL_EQUITY && e.leverage != null) e.leverage.expectedEquity * 100.0 else e.safetyScore
        }
        val ranked = evaluated.filter { !it.used }.sortedByDescending(rankKey).mapIndexed { i, e -> e.copy(rank = i + 1) }
        val unavailable = evaluated.filter { it.used }.sortedByDescending { it.probability }

        val recommended = currentPick?.let { p -> ranked.firstOrNull { it.team == p.team } } ?: ranked.firstOrNull()
        val alternatives = ranked.filter { it.team != recommended?.team }.take(3)
        // The route shown everywhere starts from the pick we are actually recommending (or the one recorded).
        val route = recommended?.let { routeWith(it.team) } ?: unconstrainedRoute
        val explanation = recommended?.let { Explain.build(it, alternatives, ranked, unconstrainedRoute, route, strikesUsed, settings) }

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
                    else plannerOpportunityCost(w, t, route, usedTeams, strikesAllowed, settings.routeObjective, settings.horizonWeight, settings.poolEntries, settings.fieldAverageWinProbability, ::candidates)
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
            route = route, unconstrainedRoute = unconstrainedRoute, routeRawProbabilities = routeRaw, planner = planner, grid = grid, futureValues = futureValues, settings = settings,
            fieldWinProbabilityThisWeek = fieldWinProbabilityThisWeek, ownershipSource = ownershipSource, ownershipFetchedAtEpochMs = ownershipFetchedAtEpochMs,
        )
    }

    /** Opportunity cost for a projected future week: how much the path after [week] loses without [team]. */
    private fun plannerOpportunityCost(
        week: Int,
        team: Team,
        route: Route,
        usedTeams: Set<Team>,
        strikesAllowed: Int,
        objective: RouteObjective,
        horizonWeight: Double,
        poolEntries: Int,
        fieldWinProbability: Double,
        candidates: (Int, Set<Team>) -> Map<Int, List<Candidate>>,
    ): Double? {
        val next = week + 1
        if (next > REGULAR_SEASON_WEEKS) return 0.0
        val consumed = usedTeams + route.steps.filter { it.week < week }.map { it.team }
        val with = Optimizer.optimize(
            candidates(next, consumed), strikesAllowed, localSearch = false, objective = objective, horizonWeight = horizonWeight,
            poolEntries = poolEntries, fieldWinProbability = fieldWinProbability,
        ).objectiveValue(objective, horizonWeight, poolEntries, fieldWinProbability)
        if (with <= 0.0) return null
        val without = Optimizer.optimize(
            candidates(next, consumed + team), strikesAllowed, localSearch = false, objective = objective, horizonWeight = horizonWeight,
            poolEntries = poolEntries, fieldWinProbability = fieldWinProbability,
        ).objectiveValue(objective, horizonWeight, poolEntries, fieldWinProbability)
        return ((1.0 - without / with) * 100.0).coerceAtLeast(0.0)
    }
}

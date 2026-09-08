package com.survivor.engine

import kotlin.math.ln

/** A candidate for one week inside the optimizer: a team and its (possibly discounted) win probability. */
data class Candidate(val team: Team, val probability: Double)

data class RouteStep(val week: Int, val team: Team, val probability: Double, val locked: Boolean = false)

data class Route(val steps: List<RouteStep>, val strikesAllowed: Int) {
    val probabilities: List<Double> get() = steps.map { it.probability }
    val zeroLoss: Double get() = Survival.zeroLoss(probabilities)
    val exactlyOneLoss: Double get() = Survival.exactlyOneLoss(probabilities)
    /** Probability of surviving the whole route under the remaining strike allowance (reporting only). */
    val survival: Double get() = Survival.survive(probabilities, strikesAllowed)
    /** Expected number of the route's weeks the entry gets through alive. */
    val expectedWeeksAlive: Double get() = Survival.expectedWeeksAlive(probabilities, strikesAllowed)
    /** Value of [objective] for this route; what the optimizer's local search actually climbs.
     *  [fieldWinProbabilities], when given, must be sized to [steps] (in week order) - see
     *  [Survival.poolWinProbability]. */
    fun objectiveValue(
        objective: RouteObjective,
        horizonWeight: Double,
        poolEntries: Int = 50,
        fieldWinProbability: Double = 0.76,
        fieldWinProbabilities: List<Double>? = null,
    ): Double = Survival.objectiveValue(probabilities, strikesAllowed, objective, horizonWeight, poolEntries, fieldWinProbability, fieldWinProbabilities)
    fun team(week: Int): Team? = steps.firstOrNull { it.week == week }?.team
    val teams: Set<Team> get() = steps.map { it.team }.toSet()
}

/**
 * Season-path optimizer. Assigns at most one team per week and uses each team at most once.
 *
 * 1. Hungarian assignment on cost = -ln(p) finds the path maximizing P(zero losses) exactly.
 * 2. Local search (replace-with-unused and pairwise swap moves) then climbs the true objective from TWO
 *    starting points - the Hungarian assignment above and the plain greedy assignment (highest probability
 *    per week, in week order, never reusing a team) - and returns whichever finished assignment scores
 *    higher. The Hungarian start is a hill-climb from a P(zero losses)-optimal point, which is an excellent
 *    seed for [RouteObjective.SURVIVE_SEASON] but can stall in a local optimum for objectives that value
 *    something else (e.g. [RouteObjective.EXPECTED_WEEKS_ALIVE] or [RouteObjective.POOL_WIN]); the greedy
 *    start gives the search a second, structurally different point to climb from.
 *
 * Weeks with no candidate (e.g. every team with data already used) are dropped from the route.
 */
object Optimizer {
    private const val UNAVAILABLE = 1.0e6

    fun optimize(
        candidatesByWeek: Map<Int, List<Candidate>>,
        strikesAllowed: Int,
        locked: Map<Int, Team> = emptyMap(),
        localSearch: Boolean = true,
        /** Which season-path quantity the local search climbs; the starting assignments are unaffected by this. */
        objective: RouteObjective = RouteObjective.SURVIVE_SEASON,
        horizonWeight: Double = 0.5,
        /** [RouteObjective.POOL_WIN] only. */
        poolEntries: Int = 50,
        /** [RouteObjective.POOL_WIN] only. */
        fieldWinProbability: Double = 0.76,
        /** [RouteObjective.POOL_WIN] only: per-week override of [fieldWinProbability], keyed by week number,
         *  for weeks where it's known more precisely (e.g. the current week's Yahoo pick-weighted win rate).
         *  Weeks not present fall back to [fieldWinProbability]. */
        fieldWinProbabilities: Map<Int, Double>? = null,
    ): Route {
        val weeks = candidatesByWeek.keys.sorted().filter { w -> locked.containsKey(w) || candidatesByWeek[w].orEmpty().isNotEmpty() }
        if (weeks.isEmpty()) return Route(emptyList(), strikesAllowed)
        val lockedTeams = locked.values.toSet()
        val teams = Team.entries.filter { it !in lockedTeams }
        val teamIndex = teams.withIndex().associate { it.value to it.index }
        val freeWeeks = weeks.filter { !locked.containsKey(it) }

        // Probability lookup, excluding locked teams from free weeks.
        val prob: Map<Int, Map<Team, Double>> = weeks.associateWith { w ->
            candidatesByWeek[w].orEmpty().filter { it.team !in lockedTeams }.associate { it.team to it.probability }
        }

        val baseAssignment = HashMap<Int, Team>()
        locked.forEach { (w, t) -> if (w in weeks) baseAssignment[w] = t }

        val hungarianStart = HashMap(baseAssignment)
        if (freeWeeks.isNotEmpty()) {
            val cost = Array(freeWeeks.size) { r ->
                DoubleArray(teams.size) { c ->
                    val p = prob[freeWeeks[r]]?.get(teams[c])
                    if (p == null || p <= 0.0) UNAVAILABLE else -ln(p)
                }
            }
            val cols = Hungarian.solve(cost)
            for (r in freeWeeks.indices) {
                val t = teams[cols[r]]
                if (prob[freeWeeks[r]]?.containsKey(t) == true) hungarianStart[freeWeeks[r]] = t
            }
        }

        fun climb(assignment: HashMap<Int, Team>): HashMap<Int, Team> {
            if (localSearch && freeWeeks.size > 1) improve(assignment, freeWeeks, prob, strikesAllowed, teamIndex.keys, objective, horizonWeight, poolEntries, fieldWinProbability, fieldWinProbabilities)
            return assignment
        }

        val hungarianResult = climb(HashMap(hungarianStart))
        var assignment = hungarianResult
        if (localSearch && freeWeeks.size > 1) {
            val greedyResult = climb(greedyAssignment(baseAssignment, freeWeeks, prob))
            val hungarianScore = score(hungarianResult, prob, strikesAllowed, objective, horizonWeight, poolEntries, fieldWinProbability, fieldWinProbabilities)
            val greedyScore = score(greedyResult, prob, strikesAllowed, objective, horizonWeight, poolEntries, fieldWinProbability, fieldWinProbabilities)
            if (greedyScore > hungarianScore + 1e-12) assignment = greedyResult
        }

        val steps = weeks.mapNotNull { w ->
            val t = assignment[w] ?: return@mapNotNull null
            val p = if (locked.containsKey(w)) candidatesByWeek[w]?.firstOrNull { it.team == t }?.probability ?: 1.0 else prob[w]?.get(t) ?: return@mapNotNull null
            RouteStep(w, t, p, locked = locked.containsKey(w))
        }
        return Route(steps, strikesAllowed)
    }

    /** Highest available probability per week, in week order, never reusing a team; the second local-search start. */
    private fun greedyAssignment(base: Map<Int, Team>, freeWeeks: List<Int>, prob: Map<Int, Map<Team, Double>>): HashMap<Int, Team> {
        val assignment = HashMap(base)
        val used = base.values.toMutableSet()
        for (w in freeWeeks) {
            val pick = prob[w]?.entries?.filter { it.key !in used }?.maxByOrNull { it.value } ?: continue
            used += pick.key
            assignment[w] = pick.key
        }
        return assignment
    }

    /** Objective value of an assignment, in week order (order matters for [RouteObjective.EXPECTED_WEEKS_ALIVE]/BLENDED/POOL_WIN). */
    private fun score(
        assignment: Map<Int, Team>,
        prob: Map<Int, Map<Team, Double>>,
        strikesAllowed: Int,
        objective: RouteObjective,
        horizonWeight: Double,
        poolEntries: Int,
        fieldWinProbability: Double,
        fieldWinProbabilities: Map<Int, Double>? = null,
    ): Double {
        val weeks = assignment.entries.sortedBy { it.key }
        val ps = weeks.map { (w, t) -> prob[w]?.get(t) ?: 1.0 }
        val fieldPs = fieldWinProbabilities?.let { m -> weeks.map { (w, _) -> m[w] ?: fieldWinProbability } }
        return Survival.objectiveValue(ps, strikesAllowed, objective, horizonWeight, poolEntries, fieldWinProbability, fieldPs)
    }

    private fun improve(
        assignment: HashMap<Int, Team>,
        freeWeeks: List<Int>,
        prob: Map<Int, Map<Team, Double>>,
        strikesAllowed: Int,
        allTeams: Set<Team>,
        objective: RouteObjective,
        horizonWeight: Double,
        poolEntries: Int,
        fieldWinProbability: Double,
        fieldWinProbabilities: Map<Int, Double>? = null,
    ) {
        var best = score(assignment, prob, strikesAllowed, objective, horizonWeight, poolEntries, fieldWinProbability, fieldWinProbabilities)
        var improved = true
        var rounds = 0
        while (improved && rounds < 50) {
            improved = false
            rounds++
            // Replace moves: week w takes an unused team u.
            for (w in freeWeeks) {
                val current = assignment[w] ?: continue
                val used = assignment.values.toSet()
                for (u in allTeams) {
                    if (u in used) continue
                    val pu = prob[w]?.get(u) ?: continue
                    assignment[w] = u
                    val s = score(assignment, prob, strikesAllowed, objective, horizonWeight, poolEntries, fieldWinProbability, fieldWinProbabilities)
                    if (s > best + 1e-12) { best = s; improved = true } else assignment[w] = current
                }
            }
            // Swap moves: exchange teams between two weeks.
            for (i in freeWeeks.indices) for (j in i + 1 until freeWeeks.size) {
                val w1 = freeWeeks[i]; val w2 = freeWeeks[j]
                val t1 = assignment[w1] ?: continue
                val t2 = assignment[w2] ?: continue
                if (prob[w1]?.containsKey(t2) != true || prob[w2]?.containsKey(t1) != true) continue
                assignment[w1] = t2; assignment[w2] = t1
                val s = score(assignment, prob, strikesAllowed, objective, horizonWeight, poolEntries, fieldWinProbability, fieldWinProbabilities)
                if (s > best + 1e-12) { best = s; improved = true } else { assignment[w1] = t1; assignment[w2] = t2 }
            }
        }
    }

    /** Baseline for comparison: the highest available probability each week, never reusing a team. */
    fun greedy(candidatesByWeek: Map<Int, List<Candidate>>, strikesAllowed: Int, locked: Map<Int, Team> = emptyMap()): Route {
        val used = locked.values.toMutableSet()
        val steps = mutableListOf<RouteStep>()
        for (w in candidatesByWeek.keys.sorted()) {
            val lockedTeam = locked[w]
            if (lockedTeam != null) {
                val p = candidatesByWeek[w]?.firstOrNull { it.team == lockedTeam }?.probability ?: 1.0
                steps += RouteStep(w, lockedTeam, p, locked = true); continue
            }
            val pick = candidatesByWeek[w].orEmpty().filter { it.team !in used }.maxByOrNull { it.probability } ?: continue
            used += pick.team
            steps += RouteStep(w, pick.team, pick.probability)
        }
        return Route(steps, strikesAllowed)
    }
}

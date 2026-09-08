package com.survivor.engine

import kotlin.math.ln

/** A candidate for one week inside the optimizer: a team and its (possibly discounted) win probability. */
data class Candidate(val team: Team, val probability: Double)

data class RouteStep(val week: Int, val team: Team, val probability: Double, val locked: Boolean = false)

data class Route(val steps: List<RouteStep>, val strikesAllowed: Int) {
    val probabilities: List<Double> get() = steps.map { it.probability }
    val zeroLoss: Double get() = Survival.zeroLoss(probabilities)
    val exactlyOneLoss: Double get() = Survival.exactlyOneLoss(probabilities)
    /** Objective: probability of surviving the whole route under the remaining strike allowance. */
    val survival: Double get() = Survival.survive(probabilities, strikesAllowed)
    fun team(week: Int): Team? = steps.firstOrNull { it.week == week }?.team
    val teams: Set<Team> get() = steps.map { it.team }.toSet()
}

/**
 * Season-path optimizer. Assigns at most one team per week and uses each team at most once.
 *
 * 1. Hungarian assignment on cost = -ln(p) finds the path maximizing P(zero losses) exactly.
 * 2. Local search (replace-with-unused and pairwise swap moves) then climbs the true
 *    double-elimination objective P(0 losses) + P(exactly 1 loss) when a strike is still in hand.
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

        val assignment = HashMap<Int, Team>()
        locked.forEach { (w, t) -> if (w in weeks) assignment[w] = t }

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
                if (prob[freeWeeks[r]]?.containsKey(t) == true) assignment[freeWeeks[r]] = t
            }
        }

        if (localSearch && freeWeeks.size > 1) improve(assignment, freeWeeks, prob, strikesAllowed, teamIndex.keys)

        val steps = weeks.mapNotNull { w ->
            val t = assignment[w] ?: return@mapNotNull null
            val p = if (locked.containsKey(w)) candidatesByWeek[w]?.firstOrNull { it.team == t }?.probability ?: 1.0 else prob[w]?.get(t) ?: return@mapNotNull null
            RouteStep(w, t, p, locked = locked.containsKey(w))
        }
        return Route(steps, strikesAllowed)
    }

    private fun objective(assignment: Map<Int, Team>, prob: Map<Int, Map<Team, Double>>, strikesAllowed: Int): Double {
        val ps = assignment.entries.map { (w, t) -> prob[w]?.get(t) ?: 1.0 }
        return Survival.survive(ps, strikesAllowed)
    }

    private fun improve(
        assignment: HashMap<Int, Team>,
        freeWeeks: List<Int>,
        prob: Map<Int, Map<Team, Double>>,
        strikesAllowed: Int,
        allTeams: Set<Team>,
    ) {
        var best = objective(assignment, prob, strikesAllowed)
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
                    val score = objective(assignment, prob, strikesAllowed)
                    if (score > best + 1e-12) { best = score; improved = true } else assignment[w] = current
                }
            }
            // Swap moves: exchange teams between two weeks.
            for (i in freeWeeks.indices) for (j in i + 1 until freeWeeks.size) {
                val w1 = freeWeeks[i]; val w2 = freeWeeks[j]
                val t1 = assignment[w1] ?: continue
                val t2 = assignment[w2] ?: continue
                if (prob[w1]?.containsKey(t2) != true || prob[w2]?.containsKey(t1) != true) continue
                assignment[w1] = t2; assignment[w2] = t1
                val score = objective(assignment, prob, strikesAllowed)
                if (score > best + 1e-12) { best = score; improved = true } else { assignment[w1] = t1; assignment[w2] = t2 }
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

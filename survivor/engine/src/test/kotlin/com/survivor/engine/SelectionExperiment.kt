package com.survivor.engine

import com.survivor.engine.data.EspnParser
import com.survivor.engine.data.SeasonMerge
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test

/** Data-science evaluation of the selection process on recorded ESPN data. Env-gated like RealDataSmokeTest. */
class SelectionExperiment {
    private fun logit(p: Double) = ln(p / (1 - p))
    private fun sigmoid(x: Double) = 1 / (1 + exp(-x))
    private fun gauss(r: Random): Double { var u: Double; var v: Double; var s: Double; do { u = r.nextDouble() * 2 - 1; v = r.nextDouble() * 2 - 1; s = u * u + v * v } while (s >= 1 || s == 0.0); return u * kotlin.math.sqrt(-2 * ln(s) / s) }

    @Test fun `evaluate season selection process`() {
        val dir = System.getenv("SURVIVOR_REAL_DATA_DIR")?.let { File(it) }?.takeIf { it.isDirectory } ?: return
        val now = 1_757_300_000_000L
        var games = SeasonMerge.mergeGames(emptyList(), (1..18).flatMap { w -> EspnParser.parseScoreboard(File(dir, "week_$w.json").readText(), now).games })
        games = games.map { g -> File(dir, "pred/${g.id}.json").takeIf { it.exists() }?.let { f -> EspnParser.parsePredictor(f.readText(), now)?.let { g.copy(fpi = it) } } ?: g }
        val season = Season(2026, games, EspnParser.parsePowerIndex(File(dir, "powerindex.json").readText()).associateBy { it.team }, now, now, now)

        fun table(settings: ModelSettings): Map<Int, Map<Team, Double>> = (1..18).associateWith { w ->
            season.gamesInWeek(w).flatMap { g -> listOf(g.home, g.away).map { t -> t to ProbabilityResolver.resolve(g, t, w <= 1, null, season.ratings, settings).probability } }.toMap()
        }
        val base = table(ModelSettings())

        println("=== 1. SCARCITY OF ELITE SPOTS BY WEEK (market/FPI blend, undiscounted) ===")
        println("wk  >80%  75-80%  70-75%  best")
        for (w in 1..18) {
            val ps = base.getValue(w).values
            println("%2d   %2d     %2d      %2d    %s".format(w, ps.count { it > 0.80 }, ps.count { it in 0.75..0.80 }, ps.count { it in 0.70..0.75 }, base.getValue(w).maxByOrNull { it.value }!!.let { "${it.key.abbr} ${(it.value * 100).toInt()}%" }))
        }
        val premiumTeams = Team.entries.filter { t -> (2..18).any { w -> (base[w]?.get(t) ?: 0.0) > 0.80 } }
        println("teams with >=1 future >80% spot: ${premiumTeams.size} (${premiumTeams.joinToString { it.abbr }})")
        val eliteSpots = (1..18).sumOf { w -> base.getValue(w).values.count { it > 0.80 } }
        println("total >80% team-games across season: $eliteSpots ; weeks needing a pick: 18")

        println()
        println("=== 2. SENSITIVITY: discount d x market weight w -> W1 pick (safety), unconstrained W1, raw P(<=1 loss), E[weeks alive] ===")
        for (w in listOf(0.0, 0.3, 0.6, 1.0)) for (d in listOf(0.0, 0.01, 0.03, 0.06, 0.10)) {
            val s = ModelSettings(futureMarketWeight = w, futureDiscountPerWeek = d)
            val e = Evaluator.evaluate(season, UserState(settings = s), now)
            val raw = e.routeRawProbabilities
            val ew = expectedWeeks(raw, 1)
            println("w=%.1f d=%.2f  safety#1=%-4s unconstrained=%-4s  P<=1=%.2f%%  P0=%.2f%%  E[weeks]=%.2f  route=%s".format(w, d, e.recommended!!.team.abbr, e.unconstrainedRoute.team(1)!!.abbr, e.seasonAtMostOneLoss * 100, e.seasonZeroLoss * 100, ew, e.unconstrainedRoute.steps.joinToString(",") { it.team.abbr }))
        }

        println()
        println("=== 3. CLOSED-LOOP POLICY SIMULATION (line noise tau(k)=min(0.5, 0.08+0.03k) in logit space, common random numbers) ===")
        val seasons = 600
        val rng = Random(7)
        val weeks = (1..18).toList()
        val gameIds = season.games.associateBy { it.id }
        data class Draws(val truth: Map<String, Double>, val obs: Map<Int, Map<String, Double>>, val u: Map<String, Double>)
        fun tau(k: Int) = min(0.5, 0.08 + 0.03 * k)
        val draws = List(seasons) {
            val truth = season.games.associate { g -> g.id to sigmoid(logit(base.getValue(g.week).getValue(g.home)) + gauss(rng) * tau(g.week - 1)) }
            val obs = weeks.associateWith { w -> season.games.filter { it.week >= w }.associate { g -> g.id to sigmoid(logit(truth.getValue(g.id)) + gauss(rng) * tau(g.week - w)) } }
            val u = season.games.associate { it.id to rng.nextDouble() }
            Draws(truth, obs, u)
        }
        fun pOf(pHome: Double, g: Game, t: Team) = if (t == g.home) pHome else 1 - pHome
        data class Pol(val name: String, val decide: (w: Int, strikes: Int, used: Set<Team>, obs: Map<String, Double>) -> Team?)
        fun candidates(fromWeek: Int, used: Set<Team>, obs: Map<String, Double>, d: Double, w0: Int) = (fromWeek..18).associateWith { w ->
            season.gamesInWeek(w).flatMap { g -> listOf(g.home, g.away).filter { it !in used }.map { t -> Candidate(t, Probability.discount(pOf(obs.getValue(g.id), g, t), w - w0, d)) } }
        }
        val policies = listOf(
            Pol("greedy: max p this week") { w, _, used, obs -> candidates(w, used, obs, 0.0, w)[w]!!.maxByOrNull { it.probability }?.team },
            Pol("optimized d=0.00") { w, s, used, obs -> Optimizer.optimize(candidates(w, used, obs, 0.0, w), 1 - s).team(w) },
            Pol("optimized d=0.03 (app)") { w, s, used, obs -> Optimizer.optimize(candidates(w, used, obs, 0.03, w), 1 - s).team(w) },
            Pol("optimized d=0.06") { w, s, used, obs -> Optimizer.optimize(candidates(w, used, obs, 0.06, w), 1 - s).team(w) },
            Pol("optimized d=0.10") { w, s, used, obs -> Optimizer.optimize(candidates(w, used, obs, 0.10, w), 1 - s).team(w) },
            Pol("zero-loss hungarian d=0.03") { w, s, used, obs -> Optimizer.optimize(candidates(w, used, obs, 0.03, w), 1 - s, localSearch = false).team(w) },
            Pol("opt d=0.03 + floor 0.70 guard") { w, s, used, obs ->
                val c = candidates(w, used, obs, 0.03, w); val t = Optimizer.optimize(c, 1 - s).team(w)
                val best = c[w]!!.maxByOrNull { it.probability }
                if (t != null && best != null && c[w]!!.first { it.team == t }.probability < 0.70 && best.probability >= 0.70) best.team else t
            },
            Pol("opt d=0.03, P0-only after strike... (same as app)") { w, s, used, obs -> Optimizer.optimize(candidates(w, used, obs, 0.03, w), (1 - s).coerceAtLeast(0)).team(w) },
        )
        println("%-42s %8s %8s %8s %8s %9s %7s".format("policy", "survive", "zeroL", "reach10", "reach14", "E[weeks]", "strikes"))
        for (pol in policies) {
            var surv = 0; var zero = 0; var r10 = 0; var r14 = 0; var weeksAlive = 0L; var strikesTot = 0L
            for (dr in draws) {
                var strikes = 0; val used = HashSet<Team>(); var alive = true; var wk = 0
                for (w in weeks) {
                    val t = pol.decide(w, strikes, used, dr.obs.getValue(w)) ?: break
                    used += t
                    val g = season.gameFor(t, w)!!
                    val pt = pOf(dr.truth.getValue(g.id), g, t)
                    if (dr.u.getValue(g.id) >= pt) strikes++
                    if (strikes >= 2) { alive = false; break }
                    wk = w
                }
                if (alive) { surv++; if (strikes == 0) zero++ }
                if (alive || wk >= 9) r10++
                if (alive || wk >= 13) r14++
                weeksAlive += if (alive) 18 else wk
                strikesTot += strikes
            }
            println("%-42s %7.1f%% %7.1f%% %7.1f%% %7.1f%% %9.2f %7.2f".format(pol.name, surv * 100.0 / seasons, zero * 100.0 / seasons, r10 * 100.0 / seasons, r14 * 100.0 / seasons, weeksAlive.toDouble() / seasons, strikesTot.toDouble() / seasons))
        }

        println()
        println("=== 4. W1 DECISION UNDER SCENARIOS: how often is each team the unconstrained optimum's W1 pick (200 perturbed tables) ===")
        val counts = HashMap<Team, Int>()
        val r2 = Random(11)
        repeat(200) {
            val obs = season.games.associate { g -> g.id to sigmoid(logit(base.getValue(g.week).getValue(g.home)) + gauss(r2) * tau(g.week - 1)) }
            val t = Optimizer.optimize(candidates(1, emptySet(), obs, 0.03, 1), 1).team(1)!!
            counts[t] = (counts[t] ?: 0) + 1
        }
        println(counts.entries.sortedByDescending { it.value }.joinToString("  ") { "${it.key.abbr} ${it.value / 2}%" })
    }

    private fun expectedWeeks(ps: List<Double>, strikesAllowed: Int): Double {
        var total = 0.0
        for (k in 1..ps.size) total += Survival.survive(ps.take(k), strikesAllowed)
        return total
    }
}

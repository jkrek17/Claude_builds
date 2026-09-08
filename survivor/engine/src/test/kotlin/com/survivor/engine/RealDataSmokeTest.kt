package com.survivor.engine

import com.survivor.engine.data.EspnParser
import com.survivor.engine.data.SavedState
import com.survivor.engine.data.SeasonMerge
import com.survivor.engine.data.StateCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end run on recorded ESPN responses for a full season. Only runs when SURVIVOR_REAL_DATA_DIR
 * points at a directory holding week_1..18.json, powerindex.json and pred/<eventId>.json; otherwise
 * it passes trivially so CI stays hermetic. Prints the Week-1 dashboard so the output can be eyeballed.
 */
class RealDataSmokeTest {
    @Test fun `full season from recorded ESPN data evaluates cleanly`() {
        val dir = System.getenv("SURVIVOR_REAL_DATA_DIR")?.let { File(it) }?.takeIf { it.isDirectory } ?: return
        val now = 1_757_300_000_000L // 2026-09-08
        var games = (1..18).flatMap { w -> EspnParser.parseScoreboard(File(dir, "week_$w.json").readText(), now).games }
        games = SeasonMerge.mergeGames(emptyList(), games)
        games = games.map { g -> File(dir, "pred/${g.id}.json").takeIf { it.exists() }?.let { f -> EspnParser.parsePredictor(f.readText(), now)?.let { g.copy(fpi = it) } } ?: g }
        val ratings = EspnParser.parsePowerIndex(File(dir, "powerindex.json").readText()).associateBy { it.team }
        val season = Season(2026, games, ratings, now, now, now)

        assertEquals(272, games.size)
        assertEquals(32, ratings.size)
        for (t in Team.entries) assertEquals(17, games.count { it.involves(t) }, "games for $t")
        assertTrue(games.all { it.line != null && it.fpi != null })

        val e = Evaluator.evaluate(season, UserState(), now)
        assertEquals(1, e.currentWeek)
        assertEquals(32, e.grid.size)
        assertEquals(18, e.route.steps.size)
        assertEquals(18, e.route.teams.size)
        assertTrue(e.rankings.all { it.probability in 0.01..0.99 && it.safetyScore in 0.0..100.0 })
        assertTrue(e.rankings.all { it.estimate.source == ProbabilitySource.MARKET_MONEYLINE })
        assertTrue(e.futureValues.values.all { fv -> fv.games.all { it.source == ProbabilitySource.MARKET_SPREAD_FPI_BLEND } })

        // JSON round trip of the real season.
        val text = StateCodec.encode(SavedState(season, UserState(), now))
        assertEquals(season, StateCodec.decode(text).season)

        println("=== WEEK ${e.currentWeek} DASHBOARD (real 2026 lines, ${text.length / 1024} KB state) ===")
        println(e.explanation?.headline)
        println("WHY SAFE: " + e.explanation?.whySafe)
        println("WHY NOW: " + e.explanation?.whyNow)
        println("GIVE UP: " + e.explanation?.whatWeGiveUp)
        println("WARNING: " + e.explanation?.futureValueWarning)
        e.explanation?.alternatives?.forEach { (a, r) -> println("ALT #${a.rank} ${a.team.abbr}: $r") }
        println("%-3s %-4s %-8s %6s %6s %7s %8s %6s %5s %s".format("#", "Team", "Opp", "Spread", "Win%", "Safety", "OppCost", "Prem", "Grade", "Best future"))
        e.rankings.forEach { r ->
            println("%-3d %-4s %-8s %6s %5.1f%% %7.1f %7.1f%% %6d %5s W%d %s".format(r.rank, r.team.abbr, Explain.venue(r), Explain.spread(r.estimate.teamSpread), r.probability * 100, r.safetyScore, r.opportunityCost, r.futureValue.premiumSpots, r.grade, r.futureValue.best?.week ?: 0, r.futureValue.best?.let { Explain.pct(it.probability) } ?: ""))
        }
        println("ROUTE: " + e.route.steps.joinToString(" | ") { s -> "W${s.week} ${s.team.abbr} ${Explain.pct(e.routeRawProbabilities[e.route.steps.indexOf(s)])}" })
        println("SEASON: P0=${Explain.pct(e.seasonZeroLoss)} P<=1=${Explain.pct(e.seasonAtMostOneLoss)}")
        val sims = Strategies.compare(season, UserState(), now, iterations = 20_000)
        sims.forEach { println("SIM %-32s survive=%.1f%% reach10=%.1f%% reach14=%.1f%% reach18=%.1f%% strikes=%.2f elim=%s".format(it.strategy, it.surviveSeason * 100, it.reachWeek10 * 100, it.reachWeek14 * 100, it.reachWeek18 * 100, it.expectedStrikes, it.expectedEliminationWeek?.let { w -> "%.1f".format(w) } ?: "-")) }
        // Timing of a full evaluation, which the app runs on every state change.
        val t0 = System.nanoTime(); repeat(3) { Evaluator.evaluate(season, UserState(), now) }
        println("EVAL avg ms: " + (System.nanoTime() - t0) / 3 / 1_000_000)
    }
}

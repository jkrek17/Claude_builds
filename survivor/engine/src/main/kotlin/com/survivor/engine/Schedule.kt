package com.survivor.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/** Schedule-derived situational factors that the market line may not fully price. */
data class Situation(
    val isHome: Boolean,
    val neutral: Boolean,
    val divisional: Boolean,
    val restDays: Int,
    val opponentRestDays: Int,
    val timeZonesCrossed: Int,
) {
    /** Positive = team is better rested than the opponent. */
    val restAdvantageDays: Int get() = (restDays - opponentRestDays).coerceIn(-7, 7)
}

object ScheduleAnalysis {
    private const val DAY_MS = 86_400_000L

    /** Days since the team's previous game; 7 for Week 1 or when there is no earlier game on record. */
    fun restDays(season: Season, team: Team, game: Game): Int {
        val previous = season.games
            .filter { it.involves(team) && it.kickoffEpochMs < game.kickoffEpochMs }
            .maxByOrNull { it.kickoffEpochMs } ?: return 7
        return ((game.kickoffEpochMs - previous.kickoffEpochMs).toDouble() / DAY_MS).roundToInt().coerceIn(3, 21)
    }

    fun situation(season: Season, team: Team, game: Game): Situation {
        val opp = game.opponentOf(team)
        val home = game.isHome(team)
        val tz = if (game.neutralSite) 0 else if (home) 0 else abs(team.tzOffsetHours - game.home.tzOffsetHours)
        return Situation(
            isHome = home,
            neutral = game.neutralSite,
            divisional = game.isDivisional,
            restDays = restDays(season, team, game),
            opponentRestDays = restDays(season, opp, game),
            timeZonesCrossed = tz,
        )
    }
}

/** Summary of a team's remaining (strictly future) schedule, used for future value and the grid. */
data class FutureValue(
    val team: Team,
    val games: List<FutureGame>,
) {
    val best: FutureGame? get() = games.maxByOrNull { it.probability }
    val secondBest: FutureGame? get() = games.sortedByDescending { it.probability }.drop(1).firstOrNull()
    val average: Double? get() = games.takeIf { it.isNotEmpty() }?.map { it.probability }?.average()
    fun countAbove(threshold: Double) = games.count { it.probability > threshold }
    val premiumSpots: Int get() = countAbove(0.80)
}

data class FutureGame(val week: Int, val opponent: Team, val isHome: Boolean, val probability: Double, val source: ProbabilitySource)

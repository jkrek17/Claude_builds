package com.survivor.engine

import kotlin.random.Random

/** Synthetic but structurally realistic season: 32 teams, 18 weeks, byes in Weeks 5-14, lines on every game. */
object TestSeason {
    const val WEEK1_KICKOFF = 1_788_000_000_000L // ~Sept 2026
    private const val WEEK_MS = 7L * 86_400_000L

    fun build(seed: Int = 1, ratings: Map<Team, Double>? = null, withFpi: Boolean = true, finalsThroughWeek: Int = 0): Season {
        val rng = Random(seed)
        val r = ratings ?: Team.entries.associateWith { rng.nextDouble(-8.0, 8.0) }
        val games = mutableListOf<Game>()
        val byeOrder = Team.entries.shuffled(rng)
        for (w in 1..REGULAR_SEASON_WEEKS) {
            val onBye = if (w in 5..12) byeOrder.drop((w - 5) * 4).take(4).toSet() else emptySet()
            val playing = Team.entries.filter { it !in onBye }.shuffled(rng)
            playing.chunked(2).forEachIndexed { i, (home, away) ->
                val gap = r.getValue(home) - r.getValue(away) + 1.8
                val homeSpread = -(Math.round(gap * 2) / 2.0)
                val pHome = Probability.winProbabilityFromSpread(-homeSpread)
                val mlHome = if (pHome >= 0.5) -Math.round(100 * pHome / (1 - pHome) * 1.08).toInt() else Math.round(100 * (1 - pHome) / pHome / 1.08).toInt()
                val mlAway = if (pHome < 0.5) -Math.round(100 * (1 - pHome) / pHome * 1.08).toInt() else Math.round(100 * pHome / (1 - pHome) / 1.08).toInt()
                val final = w <= finalsThroughWeek
                val homeWins = rng.nextDouble() < pHome
                games += Game(
                    id = "w${w}g$i", week = w, home = home, away = away,
                    kickoffEpochMs = WEEK1_KICKOFF + (w - 1) * WEEK_MS + i * 3_600_000L,
                    state = if (final) GameState.FINAL else GameState.SCHEDULED,
                    homeScore = if (final) (if (homeWins) 27 else 17) else null,
                    awayScore = if (final) (if (homeWins) 17 else 27) else null,
                    line = MarketLine("Test", homeSpread, mlHome, mlAway, 1L),
                    fpi = if (withFpi) FpiProjection(pHome, 1L) else null,
                )
            }
        }
        return Season(2026, games, Team.entries.associate { it to TeamRating(it, r.getValue(it)) }, 1L, 1L, 1L)
    }
}

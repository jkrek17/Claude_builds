package com.survivor.app.data

import com.survivor.engine.Game
import com.survivor.engine.GameState
import com.survivor.engine.REGULAR_SEASON_WEEKS
import com.survivor.engine.Season
import com.survivor.engine.Team
import com.survivor.engine.TeamRating
import com.survivor.engine.data.EspnEndpoints
import com.survivor.engine.data.EspnParser
import com.survivor.engine.data.SeasonMerge
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Pulls schedule, scores, DraftKings lines and FPI projections from ESPN's public JSON. */
class EspnClient(private val http: HttpFetcher, private val now: () -> Long = System::currentTimeMillis) {

    /** Season year and current week from the un-parameterised scoreboard. */
    suspend fun currentSeasonAndWeek(): Pair<Int, Int?> {
        val body = http.get(EspnEndpoints.CURRENT_SCOREBOARD)
        val page = EspnParser.parseScoreboard(body, now())
        val year = page.seasonYear ?: java.time.Year.now().value
        return year to page.week
    }

    /**
     * All 18 regular-season weeks of games with lines. Runs 4 requests at a time.
     * [onProgress] receives a short status string for the UI.
     */
    suspend fun fetchSchedule(year: Int, onProgress: (String) -> Unit = {}): List<Game> = coroutineScope {
        val gate = Semaphore(4)
        val weeks = (1..REGULAR_SEASON_WEEKS).map { w ->
            async {
                gate.withPermit {
                    onProgress("Schedule and lines: week $w of $REGULAR_SEASON_WEEKS")
                    EspnParser.parseScoreboard(http.get(EspnEndpoints.scoreboard(year, w)), now()).games
                }
            }
        }
        weeks.flatMap { it.await() }.sortedWith(compareBy({ it.week }, { it.kickoffEpochMs }))
    }

    /** FPI game projections for every non-final game, 8 at a time. Failures leave the game's FPI untouched. */
    suspend fun fetchProjections(games: List<Game>, onProgress: (String) -> Unit = {}): List<Game> = coroutineScope {
        val gate = Semaphore(8)
        val pending = games.filter { it.state != GameState.FINAL }
        var done = 0
        val updated = pending.map { g ->
            async {
                gate.withPermit {
                    val fpi = runCatching { EspnParser.parsePredictor(http.get(EspnEndpoints.predictor(g.id)), now()) }.getOrNull()
                    done++
                    if (done % 16 == 0 || done == pending.size) onProgress("FPI projections: $done of ${pending.size}")
                    if (fpi != null) g.copy(fpi = fpi) else g
                }
            }
        }.map { it.await() }
        SeasonMerge.mergeGames(games, updated)
    }

    suspend fun fetchRatings(): List<TeamRating> = EspnParser.parsePowerIndex(http.get(EspnEndpoints.POWER_INDEX))

    /** Full refresh: schedule + lines + ratings + projections, merged over [existing]. */
    suspend fun refresh(existing: Season?, includeProjections: Boolean, onProgress: (String) -> Unit): Season {
        onProgress("Checking current season")
        val (year, _) = currentSeasonAndWeek()
        val base = if (existing != null && existing.year == year) existing else Season(year)
        val fetched = fetchSchedule(year, onProgress)
        val merged = SeasonMerge.mergeGames(base.games, fetched)
        val t = now()
        var season = base.copy(games = merged, scheduleFetchedAtEpochMs = t, oddsFetchedAtEpochMs = t)
        onProgress("Team power ratings")
        runCatching { fetchRatings() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { r ->
            season = season.copy(ratings = r.associateBy { it.team }, fpiFetchedAtEpochMs = t)
        }
        if (includeProjections) {
            season = season.copy(games = fetchProjections(season.games, onProgress), fpiFetchedAtEpochMs = now())
        }
        return season
    }

    companion object {
        fun teamsMissing(season: Season): List<Team> = Team.entries.filter { t -> season.games.none { it.involves(t) } }
    }
}

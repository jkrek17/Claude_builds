package com.survivor.engine.data

import com.survivor.engine.FpiProjection
import com.survivor.engine.Game
import com.survivor.engine.GameState
import com.survivor.engine.MarketLine
import com.survivor.engine.Team
import com.survivor.engine.TeamRating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField

data class ScoreboardPage(val seasonYear: Int?, val week: Int?, val games: List<Game>)

/**
 * Parsers for ESPN's public NFL JSON. Pure functions over strings so they are unit-testable with
 * recorded fixtures. Endpoint URLs live in [EspnEndpoints].
 */
object EspnParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }
    private val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.contentOrNull
    private val JsonElement?.dbl: Double? get() = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    private val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private val JsonElement?.arr: JsonArray? get() = this as? JsonArray

    /** ESPN dates look like "2026-09-10T00:20Z" (no seconds), which java.time rejects without help. */
    fun parseInstant(text: String): Long {
        val fixed = if (Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}Z$""").matches(text)) text.dropLast(1) + ":00Z" else text
        return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(fixed)).toEpochMilli()
    }

    /** Parses American odds text such as "-170", "+142", "EVEN". */
    fun parseAmerican(text: String?): Int? {
        val t = text?.trim()?.uppercase() ?: return null
        if (t == "EVEN" || t == "EV" || t == "PK") return 100
        return t.removePrefix("+").toIntOrNull()
    }

    fun parseScoreboard(body: String, fetchedAtEpochMs: Long): ScoreboardPage {
        val root = json.parseToJsonElement(body).jsonObject
        val seasonYear = root["season"]["year"].int
        val pageWeek = root["week"]["number"].int
        val games = root["events"].arr.orEmpty().mapNotNull { ev -> parseEvent(ev, pageWeek, fetchedAtEpochMs) }
        return ScoreboardPage(seasonYear, pageWeek, games)
    }

    private fun parseEvent(ev: JsonElement, pageWeek: Int?, fetchedAt: Long): Game? {
        val id = ev["id"].str ?: return null
        val week = ev["week"]["number"].int ?: pageWeek ?: return null
        val comp = ev["competitions"].arr?.firstOrNull() ?: return null
        val competitors = comp["competitors"].arr ?: return null
        var home: Team? = null; var away: Team? = null
        var homeScore: Int? = null; var awayScore: Int? = null
        for (c in competitors) {
            val team = c["team"]["abbreviation"].str?.let { Team.fromAbbr(it) } ?: return null
            val score = c["score"].int ?: c["score"]["value"].int
            when (c["homeAway"].str) {
                "home" -> { home = team; homeScore = score }
                "away" -> { away = team; awayScore = score }
            }
        }
        val h = home ?: return null
        val a = away ?: return null
        val statusName = comp["status"]["type"]["name"].str ?: ev["status"]["type"]["name"].str
        val completed = comp["status"]["type"]["completed"]?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: false
        val state = when {
            completed || statusName == "STATUS_FINAL" -> GameState.FINAL
            statusName == "STATUS_SCHEDULED" || statusName == "STATUS_POSTPONED" || statusName == null -> GameState.SCHEDULED
            else -> GameState.IN_PROGRESS
        }
        val kickoff = (comp["date"].str ?: ev["date"].str)?.let { parseInstant(it) } ?: return null
        val neutral = comp["neutralSite"]?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: false
        val line = comp["odds"].arr?.firstOrNull()?.let { parseOdds(it, h, fetchedAt) }
        return Game(
            id = id, week = week, home = h, away = a, kickoffEpochMs = kickoff, neutralSite = neutral, state = state,
            homeScore = if (state == GameState.SCHEDULED) null else homeScore,
            awayScore = if (state == GameState.SCHEDULED) null else awayScore,
            line = line,
        )
    }

    private fun parseOdds(o: JsonElement, home: Team, fetchedAt: Long): MarketLine? {
        val provider = o["provider"]["name"].str ?: "Book"
        var spread = o["spread"].dbl ?: o["pointSpread"]["home"]["close"]["line"].dbl
        if (spread == null) {
            // Fall back to the "SEA -3" details string.
            val details = o["details"].str
            val m = details?.let { Regex("""^([A-Z]{2,4})\s+([+-]?\d+(?:\.\d+)?)$""").find(it.trim()) }
            if (m != null) {
                val team = Team.fromAbbr(m.groupValues[1]); val v = m.groupValues[2].toDoubleOrNull()
                if (team != null && v != null) spread = if (team == home) v else -v
            } else if (details == "EVEN") spread = 0.0
        }
        val homeMl = parseAmerican(o["moneyline"]["home"]["close"]["odds"].str) ?: o["homeTeamOdds"]["moneyLine"].int
        val awayMl = parseAmerican(o["moneyline"]["away"]["close"]["odds"].str) ?: o["awayTeamOdds"]["moneyLine"].int
        if (spread == null && homeMl == null && awayMl == null) return null
        return MarketLine(provider, spread, homeMl, awayMl, fetchedAt)
    }

    fun parsePredictor(body: String, fetchedAtEpochMs: Long): FpiProjection? {
        val root = json.parseToJsonElement(body).jsonObject
        val stats = root["homeTeam"]["statistics"].arr ?: return null
        val proj = stats.firstOrNull { it["name"].str == "gameProjection" }?.get("value").dbl ?: return null
        return FpiProjection((proj / 100.0).coerceIn(0.01, 0.99), fetchedAtEpochMs)
    }

    fun parsePowerIndex(body: String): List<TeamRating> {
        val root = json.parseToJsonElement(body).jsonObject
        return root["teams"].arr.orEmpty().mapNotNull { t ->
            val team = t["team"]["abbreviation"].str?.let { Team.fromAbbr(it) } ?: return@mapNotNull null
            val cat = t["categories"].arr?.firstOrNull { it["name"].str == "fpi" } ?: return@mapNotNull null
            val values = cat["values"].arr ?: return@mapNotNull null
            val fpi = values.getOrNull(0).dbl ?: return@mapNotNull null
            val rank = values.getOrNull(4).dbl?.toInt()
            TeamRating(team, fpi, rank)
        }
    }

    /** Current-week hint from the scoreboard page's top-level "week" object. */
    fun currentWeekHint(body: String): Int? = json.parseToJsonElement(body).jsonObject["week"]["number"].int
}

object EspnEndpoints {
    fun scoreboard(year: Int, week: Int) = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?seasontype=2&week=$week&dates=$year"
    const val CURRENT_SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"
    fun predictor(eventId: String) = "https://sports.core.api.espn.com/v2/sports/football/leagues/nfl/events/$eventId/competitions/$eventId/predictor"
    const val POWER_INDEX = "https://site.web.api.espn.com/apis/fitt/v3/sports/football/nfl/powerindex?region=us&lang=en"
}

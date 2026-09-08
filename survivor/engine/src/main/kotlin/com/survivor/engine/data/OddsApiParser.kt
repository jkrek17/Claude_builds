package com.survivor.engine.data

import com.survivor.engine.Game
import com.survivor.engine.MarketLine
import com.survivor.engine.Probability
import com.survivor.engine.Team
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** One upcoming game from The Odds API with the consensus (mean implied probability) moneyline. */
data class ConsensusLine(val home: Team, val away: Team, val commenceEpochMs: Long, val books: Int, val line: MarketLine)

object OddsApiParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }
    private val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.contentOrNull
    private val JsonElement?.arr: JsonArray? get() = this as? JsonArray

    fun url(apiKey: String) = "https://api.the-odds-api.com/v4/sports/americanfootball_nfl/odds?regions=us&markets=h2h&oddsFormat=american&apiKey=$apiKey"

    /** American odds for a fair probability, used to store an averaged consensus as a moneyline. */
    fun americanFromProbability(p: Double): Int {
        val q = p.coerceIn(0.01, 0.99)
        return if (q >= 0.5) -(100 * q / (1 - q)).roundToInt() else (100 * (1 - q) / q).roundToInt()
    }

    fun parse(body: String, fetchedAtEpochMs: Long): List<ConsensusLine> {
        val root = json.parseToJsonElement(body).arr ?: return emptyList()
        return root.mapNotNull { ev ->
            val home = ev["home_team"].str?.let { Team.fromFullName(it) } ?: return@mapNotNull null
            val away = ev["away_team"].str?.let { Team.fromFullName(it) } ?: return@mapNotNull null
            val commence = ev["commence_time"].str?.let { EspnParser.parseInstant(it) } ?: return@mapNotNull null
            val homeImplied = mutableListOf<Double>()
            val awayImplied = mutableListOf<Double>()
            for (book in ev["bookmakers"].arr.orEmpty()) {
                val market = book["markets"].arr?.firstOrNull { it["key"].str == "h2h" } ?: continue
                var h: Int? = null; var a: Int? = null
                for (out in market["outcomes"].arr.orEmpty()) {
                    val price = (out["price"] as? JsonPrimitive)?.doubleOrNull?.roundToInt() ?: continue
                    when (out["name"].str?.let { Team.fromFullName(it) }) { home -> h = price; away -> a = price; else -> {} }
                }
                if (h != null && a != null) {
                    homeImplied += Probability.noVig(h, a)
                    awayImplied += Probability.noVig(a, h)
                }
            }
            if (homeImplied.isEmpty()) return@mapNotNull null
            val ph = homeImplied.average()
            val pa = awayImplied.average()
            ConsensusLine(home, away, commence, homeImplied.size, MarketLine("Consensus of ${homeImplied.size} books", null, americanFromProbability(ph), americanFromProbability(pa), fetchedAtEpochMs))
        }
    }

    /** Attaches consensus lines to the matching ESPN games (same teams, kickoff within 3 days). */
    fun attach(games: List<Game>, lines: List<ConsensusLine>): List<Game> = games.map { g ->
        val match = lines.firstOrNull { it.home == g.home && it.away == g.away && abs(it.commenceEpochMs - g.kickoffEpochMs) < 3L * 86_400_000L }
        if (match != null) g.copy(consensus = match.line) else g
    }
}

package com.survivor.app.data

import com.survivor.engine.Season
import com.survivor.engine.data.OddsApiParser

/** Optional multi-book consensus moneylines from The Odds API. Requires a user-supplied key. */
class OddsApiClient(private val http: HttpFetcher, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun refresh(season: Season, apiKey: String): Season {
        require(apiKey.isNotBlank()) { "No Odds API key configured" }
        val lines = OddsApiParser.parse(http.get(OddsApiParser.url(apiKey.trim())), now())
        return season.copy(games = OddsApiParser.attach(season.games, lines), consensusFetchedAtEpochMs = now())
    }
}

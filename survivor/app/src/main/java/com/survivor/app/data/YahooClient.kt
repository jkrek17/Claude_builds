package com.survivor.app.data

import com.survivor.engine.Team
import com.survivor.engine.data.YahooPickDistributionParser

/**
 * Fetches Yahoo Survival Football's public pick-distribution page: the share of ALL Yahoo Survival Football
 * entries (not just this app's user) that picked each team, for whichever week the page currently renders.
 * No login or key is required. Parsing (and what was learned about week selection recording the fixture) lives
 * in [YahooPickDistributionParser].
 */
class YahooClient(private val http: HttpFetcher) {
    /**
     * Fetches the current week's pick distribution. Yahoo only ever populates the table for the week the field
     * is actively picking (other weeks render a "not yet available" placeholder, which [YahooPickDistributionParser.parse]
     * simply turns into an empty map) - so, deliberately, this fetches only the plain (no `?week=`) URL rather
     * than iterating all 18 weeks.
     *
     * @return the week the returned shares are for (from the page's embedded `current_week`, if found) paired
     * with each team's share (0..1); the share map is empty if the table wasn't present.
     */
    suspend fun fetchPickDistribution(): Pair<Int?, Map<Team, Double>> {
        val html = http.get(URL, BROWSER_HEADERS)
        return YahooPickDistributionParser.parseWeek(html) to YahooPickDistributionParser.parse(html)
    }

    companion object {
        const val URL = "https://football.fantasysports.yahoo.com/survival/pickdistribution/"
        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        )
    }
}

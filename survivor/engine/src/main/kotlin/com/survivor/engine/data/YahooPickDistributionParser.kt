package com.survivor.engine.data

import com.survivor.engine.Team

/**
 * Parses Yahoo Survival Football's public pick-distribution page:
 * `https://football.fantasysports.yahoo.com/survival/pickdistribution/`. No login is required; the page is
 * server-rendered HTML reporting, for one week, the share of ALL Yahoo Survival Football entries that picked
 * each team, e.g. `<table class="ysf-pick-distribution-table">` with rows shaped like:
 * ```
 * <tr class=""><td ...>1</td>
 *   <td ...><img .../><span class="D(ib) Va(m)">LA Chargers</span></td>
 *   <td ... smartphone_D(n)>Arizona</td>
 *   <td ...><span>33.46%</span></td>
 *   <td ...>...progress bar...</td>
 * </tr>
 * ```
 *
 * **What was found recording the fixture (`engine/src/test/resources/yahoo_pickdistribution.html`, 2026-09-08,
 * before Week 1 kickoff):**
 * - The page has 22 tabs across the top: weeks 1-18, then "P1".."P4" for the four playoff rounds Yahoo's
 *   survival contest also runs pools for. Only one week's table is rendered per page load; the selected tab is
 *   the one carrying the extra `Fw(b)` (bold) CSS class in the tab strip. `?week=N` selects a different week's
 *   tab, but Yahoo only ever populates the table for the week the field is actively picking - every other week
 *   renders the placeholder text "Pick distribution is not yet available for week N" instead of a table (e.g.
 *   `?week=2` for a page that landed on week 1). Since [parse] returns an empty map when no
 *   `ysf-pick-distribution-table` is found, calling it on an unavailable week's page is harmless - it just
 *   yields no shares - so this parser only ever needs to fetch the *current* week's page (the plain URL with no
 *   `?week=` parameter already lands there).
 * - The page also embeds a `"pickDistribution":{"1":[...], "2":[...], ..., "22":[...]}` JSON blob (one array of
 *   `{team, opponent, pick_percentage}` objects per week/playoff-round key) covering every week in a single
 *   payload - a possible way to fetch multiple weeks at once without extra requests - but at recording time
 *   every key past the active week was an empty array, consistent with the per-week HTML: Yahoo does not
 *   publish distributions for weeks the field hasn't started picking yet. [parse] intentionally sticks to the
 *   rendered table (no HTML/JSON library needed) rather than this JSON blob, since the table is what's
 *   documented and it degrades to an empty map exactly when the JSON would too.
 * - The current week is also available as a plain `"current_week":"N"` string elsewhere on the page (part of
 *   the fantasy game's state, unrelated to the pick-distribution widget) - a simpler and more robust signal
 *   than scanning the tab strip for the bolded one, so [parseWeek] uses that.
 * - Yahoo's team names in the table are its own "city-style" display names, which mostly - but not always -
 *   match [Team.city] exactly (e.g. "Jacksonville", "Green Bay", "Kansas City"); the four teams whose market
 *   shares a two-letter city ("Los Angeles" ×2, "New York" ×2) are abbreviated ("LA Chargers", "LA Rams",
 *   "NY Giants", "NY Jets"). [teamFromYahooName] handles all 32.
 */
object YahooPickDistributionParser {
    private val TABLE_REGEX = Regex("""<table class="ysf-pick-distribution-table">(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
    private val ROW_REGEX = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val TEAM_SPAN_REGEX = Regex("""<span class="D\(ib\) Va\(m\)">([^<]+)</span>""")
    private val PERCENT_SPAN_REGEX = Regex("""<span>([\d.]+)%</span>""")
    private val CURRENT_WEEK_REGEX = Regex(""""current_week"\s*:\s*"?(\d{1,2})"?""")

    /**
     * Yahoo's own display name for each team, mapped to [Team]. Most names are just [Team.city] (e.g.
     * "Jacksonville", "Detroit", "New England", "Green Bay", "Kansas City", "Las Vegas", "Tampa Bay",
     * "San Francisco", "Washington", "New Orleans"); the two-team cities are abbreviated.
     */
    private val CITY_ALIASES: Map<String, Team> = mapOf(
        "LA Chargers" to Team.LAC,
        "LA Rams" to Team.LAR,
        "NY Giants" to Team.NYG,
        "NY Jets" to Team.NYJ,
    )

    /** Resolves one of Yahoo's pick-distribution display names to a [Team]. Handles every one of the 32 teams. */
    fun teamFromYahooName(name: String): Team? {
        val trimmed = name.trim()
        CITY_ALIASES[trimmed]?.let { return it }
        Team.entries.firstOrNull { it.city.equals(trimmed, ignoreCase = true) }?.let { return it }
        // Fallback in case Yahoo ever renders "City Nickname" instead of just the city.
        return Team.fromFullName(trimmed)
    }

    /**
     * Every team's share of Yahoo Survival Football entries for whichever week the fetched page rendered
     * (0..1 per team). Empty when the table is absent (a week the field hasn't started picking yet, or an
     * unrecognized page). No HTML library is used - a regex tag scan over the `ysf-pick-distribution-table`
     * markup, matching each row's team `<span>` and percent `<span>` in document order.
     */
    fun parse(html: String): Map<Team, Double> {
        val tableHtml = TABLE_REGEX.find(html)?.groupValues?.get(1) ?: return emptyMap()
        val shares = LinkedHashMap<Team, Double>()
        for (rowMatch in ROW_REGEX.findAll(tableHtml)) {
            val row = rowMatch.groupValues[1]
            val teamName = TEAM_SPAN_REGEX.find(row)?.groupValues?.get(1) ?: continue
            val percentText = PERCENT_SPAN_REGEX.find(row)?.groupValues?.get(1) ?: continue
            val percent = percentText.toDoubleOrNull() ?: continue
            val team = teamFromYahooName(teamName) ?: continue
            shares[team] = (percent / 100.0).coerceIn(0.0, 1.0)
        }
        return shares
    }

    /** The page's current NFL week, from the embedded `"current_week":"N"` game-state field; null if absent. */
    fun parseWeek(html: String): Int? = CURRENT_WEEK_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
}

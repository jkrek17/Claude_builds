package com.survivor.engine

import kotlinx.serialization.Serializable

const val REGULAR_SEASON_WEEKS = 18

enum class GameState { SCHEDULED, IN_PROGRESS, FINAL }

/** Where a win probability came from, in priority order (lower ordinal wins). */
enum class ProbabilitySource(val label: String, val priority: Int) {
    MANUAL_OVERRIDE("Manual override", 0),
    ODDS_API_MONEYLINE("Consensus moneyline (Odds API)", 1),
    MARKET_MONEYLINE("DraftKings moneyline (no-vig)", 2),
    MARKET_SPREAD_FPI_BLEND("Lookahead spread + FPI blend", 3),
    MARKET_SPREAD("Market spread", 4),
    FPI_PROJECTION("ESPN FPI projection", 5),
    FPI_RATING_SPREAD("FPI rating gap", 6),
    NONE("No data", 9),
}

/** A sportsbook line for one game, always stored from the HOME team's perspective. */
@Serializable
data class MarketLine(
    val provider: String,
    /** Negative when the home team is favored, e.g. -3.0 for "SEA -3" with SEA at home. */
    val homeSpread: Double? = null,
    val homeMoneyline: Int? = null,
    val awayMoneyline: Int? = null,
    val fetchedAtEpochMs: Long,
)

/** ESPN FPI game-level projection (home win probability, 0..1). */
@Serializable
data class FpiProjection(val homeWinProbability: Double, val fetchedAtEpochMs: Long)

@Serializable
data class Game(
    val id: String,
    val week: Int,
    val home: Team,
    val away: Team,
    val kickoffEpochMs: Long,
    val neutralSite: Boolean = false,
    val state: GameState = GameState.SCHEDULED,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val line: MarketLine? = null,
    /** Optional consensus moneyline from The Odds API (multiple books), higher priority than [line]. */
    val consensus: MarketLine? = null,
    val fpi: FpiProjection? = null,
) {
    val isDivisional: Boolean get() = home.division == away.division
    fun involves(team: Team) = home == team || away == team
    fun opponentOf(team: Team): Team = if (team == home) away else home
    fun isHome(team: Team) = team == home && !neutralSite

    /** Winner if final; null for a tie or an unfinished game. */
    val winner: Team?
        get() {
            if (state != GameState.FINAL || homeScore == null || awayScore == null) return null
            return when {
                homeScore > awayScore -> home
                awayScore > homeScore -> away
                else -> null
            }
        }
    val isTie: Boolean get() = state == GameState.FINAL && homeScore != null && homeScore == awayScore
}

/** ESPN Football Power Index team rating: expected margin vs. an average opponent on a neutral field. */
@Serializable
data class TeamRating(val team: Team, val fpi: Double, val rank: Int? = null)

@Serializable
data class Season(
    val year: Int,
    val games: List<Game> = emptyList(),
    val ratings: Map<Team, TeamRating> = emptyMap(),
    val scheduleFetchedAtEpochMs: Long? = null,
    val oddsFetchedAtEpochMs: Long? = null,
    val fpiFetchedAtEpochMs: Long? = null,
    val consensusFetchedAtEpochMs: Long? = null,
) {
    fun gamesInWeek(week: Int): List<Game> = games.filter { it.week == week }
    fun gameFor(team: Team, week: Int): Game? = games.firstOrNull { it.week == week && it.involves(team) }
    fun isBye(team: Team, week: Int) = gameFor(team, week) == null

    /** The first regular-season week that still has an unfinished game; 18 once everything is final. */
    fun inferCurrentWeek(nowEpochMs: Long): Int {
        for (w in 1..REGULAR_SEASON_WEEKS) {
            val wg = gamesInWeek(w)
            if (wg.isEmpty()) continue
            if (wg.any { it.state != GameState.FINAL }) return w
        }
        return REGULAR_SEASON_WEEKS
    }
}

enum class PickResult { PENDING, WIN, LOSS, TIE }

@Serializable
data class Pick(val week: Int, val team: Team)

/** Per team-week user adjustments. All optional; the model works with none of them set. */
@Serializable
data class Adjustment(
    val week: Int,
    val team: Team,
    /** Replaces every automated source when set (0..1). */
    val overrideWinProbability: Double? = null,
    /** Points added to the team's side of the spread (negative = worse). Injury/QB news that the line hasn't absorbed. */
    val injuryPoints: Double = 0.0,
    val qbPoints: Double = 0.0,
    val weatherPoints: Double = 0.0,
    /** Estimated share of the pool picking this team this week, 0..1. */
    val estimatedPickShare: Double? = null,
    val note: String = "",
)

enum class Strategy(val label: String, val description: String) {
    CONSERVATIVE("Conservative", "Maximize your own survival. Ownership is ignored; future value still matters."),
    BALANCED("Balanced", "Survival first, with a modest bonus for lightly-owned picks."),
    CONTRARIAN("Contrarian", "Accept slightly more risk to fade heavily-owned teams."),
    MAX_POOL_EQUITY("Max Pool Equity", "Rank by expected pool equity: win probability divided by the share of the pool that survives with you."),
}

@Serializable
data class ModelSettings(
    /** Standard deviation of NFL final margin around the closing spread. ~13.5 points historically. */
    val marginSigma: Double = 13.45,
    /** Home-field advantage in points, used only when a rating gap has to stand in for a line. */
    val homeFieldPoints: Double = 1.8,
    /** For future weeks: weight on the lookahead market spread vs. the FPI projection. */
    val futureMarketWeight: Double = 0.6,
    /** Per-week shrink of future probabilities toward 50% inside the optimizer (0 = no discount). */
    val futureDiscountPerWeek: Double = 0.03,
    /** How strongly opportunity cost (in win-probability points) reduces the Safety Score. */
    val futureValueWeight: Double = 0.55,
    /** Extra scarcity penalty when a team has several premium future spots. */
    val futureScarcityWeight: Double = 1.5,
    val roadPenalty: Double = 1.0,
    val divisionalPenalty: Double = 1.5,
    val shortRestPenaltyPerDay: Double = 0.6,
    val travelPenaltyPerTimeZone: Double = 0.4,
    val qbUncertaintyPenalty: Double = 4.0,
    val injuryWeight: Double = 1.0,
    val weatherPenalty: Double = 1.0,
    val marketConfidenceBonus: Double = 2.0,
    val projectionOnlyPenalty: Double = 3.0,
    /** Below this current-week win probability a pick is flagged regardless of future value. */
    val minimumAcceptableWinProbability: Double = 0.62,
    /** Multiplier on future-value weight after the first strike (double elimination → single). */
    val strikeFutureWeightMultiplier: Double = 0.4,
    val ownershipLeverageWeight: Double = 0.0,
    val strategy: Strategy = Strategy.CONSERVATIVE,
    /** Average win probability of the field's picks, used as the pool-equity proxy when ownership is entered. */
    val fieldAverageWinProbability: Double = 0.76,
    val monteCarloIterations: Int = 20_000,
) {
    fun forStrategy(strategy: Strategy): ModelSettings = copy(
        strategy = strategy,
        ownershipLeverageWeight = when (strategy) {
            Strategy.CONSERVATIVE -> 0.0
            Strategy.BALANCED -> 4.0
            Strategy.CONTRARIAN -> 9.0
            Strategy.MAX_POOL_EQUITY -> 0.0
        },
    )

    companion object {
        /** Human-readable descriptions shown next to every parameter in Settings. */
        val descriptions: List<Pair<String, String>> = listOf(
            "marginSigma" to "Std. deviation of final margin vs. the spread (points). Drives spread → win %. 13.45 fits 2000-2024 closing lines.",
            "homeFieldPoints" to "Home edge in points when a rating gap must replace a missing line. Markets already include it.",
            "futureMarketWeight" to "Future weeks: weight on the lookahead spread; the rest goes to ESPN FPI.",
            "futureDiscountPerWeek" to "Shrinks future win % toward 50% by this much per week ahead inside the optimizer only.",
            "futureValueWeight" to "Safety points removed per point of opportunity cost. Higher = protect elite teams more.",
            "futureScarcityWeight" to "Extra safety penalty per premium (>80%) future spot the team would lose.",
            "roadPenalty" to "Safety points removed for a road game (variance not captured by the line).",
            "divisionalPenalty" to "Safety points removed for a divisional game (historically more upsets).",
            "shortRestPenaltyPerDay" to "Safety points removed per day of rest disadvantage vs. the opponent.",
            "travelPenaltyPerTimeZone" to "Safety points removed per time zone crossed for the road team.",
            "qbUncertaintyPenalty" to "Safety points removed when a QB adjustment is flagged for the game.",
            "injuryWeight" to "Multiplier on manual injury point adjustments.",
            "weatherPenalty" to "Multiplier on manual weather point adjustments.",
            "marketConfidenceBonus" to "Safety bonus when a real moneyline backs the number.",
            "projectionOnlyPenalty" to "Safety penalty when only a projection (no line) backs the number.",
            "minimumAcceptableWinProbability" to "Picks below this win % are flagged as risky regardless of future value.",
            "strikeFutureWeightMultiplier" to "After a strike, future value weight is multiplied by this (be conservative).",
            "ownershipLeverageWeight" to "Safety bonus per unit of leverage when pick share is entered. Set by strategy.",
            "fieldAverageWinProbability" to "Assumed win % of the rest of the pool's picks in the equity proxy.",
            "monteCarloIterations" to "Simulated seasons per strategy in the Monte Carlo tab.",
        )
    }
}

@Serializable
data class UserState(
    val picks: List<Pick> = emptyList(),
    val adjustments: List<Adjustment> = emptyList(),
    val settings: ModelSettings = ModelSettings(),
    val oddsApiKey: String = "",
    /** Manually chosen week; null = infer from the schedule. */
    val weekOverride: Int? = null,
) {
    fun pickFor(week: Int): Pick? = picks.firstOrNull { it.week == week }
    fun adjustment(week: Int, team: Team): Adjustment? = adjustments.firstOrNull { it.week == week && it.team == team }
    fun usedTeams(): Set<Team> = picks.map { it.team }.toSet()
}

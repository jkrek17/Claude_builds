package com.survivor.engine

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.sqrt

/** Which empirical key-number window a teased leg fell into - see docs/TEASERS.md. The two windows are
 *  priced from their own empirical rate, not from [KeyNumbers], because the historical win rate at exactly
 *  these windows (75.3% combined, 2010-2025) runs well above what the symmetric margin model predicts
 *  (71.5%) - see docs/TEASERS.md for why. */
enum class TeaserWindow(val label: String) {
    FAV("Favorite -7.5 to -8.5, teased to -1.5..-2.5"),
    DOG("Underdog +1.5 to +2.5, teased to +7.5..+8.5"),
}

/** One leg of a 6-point two-team teaser. [originalPoint]/[teasedPoint] are this team's own line, in the
 *  ordinary betting convention (negative = favored). [legRate] is the empirical window win rate already
 *  net of [ModelSettings.teaserLegHaircut]. */
@Serializable
data class TeaserLeg(
    val gameId: String,
    val team: Team,
    val teamIsHome: Boolean,
    val originalPoint: Double,
    val teasedPoint: Double,
    val window: TeaserWindow,
    val legRate: Double,
    val gameTotal: Double? = null,
    /** True when [gameTotal] is above [ModelSettings.teaserMaxTotal] - Wong's original filter for a
     *  likely shootout, which teasing key numbers on the spread doesn't protect against. Still shown,
     *  just flagged. */
    val highTotal: Boolean = false,
    val kickoffEpochMs: Long,
    val rationale: String,
)

/** One candidate two-team teaser: a pairing of two [legs] from distinct games, assuming independence
 *  (see docs/TEASERS.md) so [winProbability] is simply the product of both legs' [TeaserLeg.legRate]. */
data class TeaserCandidate(
    val legs: List<TeaserLeg>,
    val winProbability: Double,
    val price: Int,
    val ev: Double,
    val kellyFraction: Double,
    val stake: Double,
    val score: Double,
    val grade: String,
    val tier: Tier,
)

/** This week's Wong-teaser opportunity set: every qualifying leg (from every not-yet-kicked-off game of
 *  the week) plus every pairing of two of them, ranked by EV. */
data class TeaserBoard(
    val week: Int,
    val generatedAtEpochMs: Long,
    val legs: List<TeaserLeg>,
    val candidates: List<TeaserCandidate>,
    /** Set when fewer than 2 legs qualify this week - too few to pair into a teaser. */
    val note: String? = null,
)

/** One recorded two-team teaser wager. */
@Serializable
data class TeaserBet(
    val id: String,
    val placedAtEpochMs: Long,
    val week: Int,
    val legs: List<TeaserLeg>,
    val price: Int,
    val stake: Double,
)

data class TeaserLedgerRow(val bet: TeaserBet, val result: BetResult, val profit: Double)

data class TeaserLedgerTotals(val staked: Double, val profit: Double, val roi: Double?, val wins: Int, val losses: Int, val pushes: Int) {
    val record: String get() = "$wins-$losses-$pushes"
}

data class TeaserLedger(val rows: List<TeaserLedgerRow>, val totals: TeaserLedgerTotals)

/**
 * Wong teaser finder: 6-point two-team teasers that buy a favorite off -7.5..-8.5 down to -1.5..-2.5, or an
 * underdog off +1.5..+2.5 up to +7.5..+8.5 - the two windows that (2010-2025, 895 legs) won 73.1% and 76.4%
 * of the time respectively, well above the 71.5% the symmetric [KeyNumbers] margin model alone predicts for
 * the same legs. See docs/TEASERS.md for the full empirical table, the haircut, and why the window rate -
 * not the margin model - prices these legs. Entirely separate from [BettingEngine]'s straight-bet board.
 */
object Teasers {

    /** Empirical, 2010-2025, before [ModelSettings.teaserLegHaircut]. */
    private const val FAV_WINDOW_RATE = 0.731
    private const val DOG_WINDOW_RATE = 0.764

    private const val FAV_LOW = -8.5
    private const val FAV_HIGH = -7.5
    private const val DOG_LOW = 1.5
    private const val DOG_HIGH = 2.5

    /** Break-even leg rate for a two-team teaser at American [price]: a pairing of two legs each at this
     *  rate has exactly 0 EV, since win probability is the product of both legs' rates. */
    fun breakEvenLegRate(price: Int): Double = sqrt(1.0 / BettingEngine.decimalOdds(price))

    /** This team's own current line (ordinary convention, negative = favored): the multi-book consensus
     *  spread when [board] has one, else the ESPN/DraftKings line. Mirrors [BettingEngine]'s own fallback. */
    private fun consensusTeamSpread(game: Game, board: GameBoard?, team: Team): Double? {
        val quotes = board?.quotes?.filter { it.market == Market.SPREAD && it.point != null }.orEmpty()
        if (quotes.isNotEmpty()) {
            val homeSpreads = quotes.map { q -> if (q.side == game.home.abbr) q.point!! else -q.point!! }
            val consensusHome = homeSpreads.average()
            return if (team == game.home) consensusHome else -consensusHome
        }
        val homeSpread = game.line?.homeSpread ?: return null
        return if (team == game.home) homeSpread else -homeSpread
    }

    private fun consensusTotal(board: GameBoard?): Double? {
        val points = board?.quotes?.filter { it.market == Market.TOTAL && it.point != null }?.map { it.point!! }.orEmpty()
        return if (points.isEmpty()) null else points.average()
    }

    private fun windowRationale(team: Team, original: Double, teased: Double, window: TeaserWindow, rate: Double): String =
        String.format(
            Locale.US, "%s %s teased to %s (%s window, empirical %.1f%%).",
            team.abbr, formatSignedPoint(original), formatSignedPoint(teased), window.label, rate * 100.0,
        )

    private fun formatSignedPoint(v: Double): String {
        val n = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
        return (if (v > 0) "+" else "") + n
    }

    /** Every qualifying leg for one not-yet-kicked-off [game], for both teams independently. */
    private fun legsFor(game: Game, board: GameBoard?, settings: ModelSettings): List<TeaserLeg> {
        val total = consensusTotal(board)
        val highTotal = total != null && total > settings.teaserMaxTotal
        val legs = mutableListOf<TeaserLeg>()
        for (team in listOf(game.home, game.away)) {
            val point = consensusTeamSpread(game, board, team) ?: continue
            val window = when {
                point in FAV_LOW..FAV_HIGH -> TeaserWindow.FAV
                point in DOG_LOW..DOG_HIGH -> TeaserWindow.DOG
                else -> null
            } ?: continue
            val teased = point + settings.teaserPoints
            val rate = (if (window == TeaserWindow.FAV) FAV_WINDOW_RATE else DOG_WINDOW_RATE) - settings.teaserLegHaircut
            legs += TeaserLeg(
                gameId = game.id, team = team, teamIsHome = team == game.home, originalPoint = point, teasedPoint = teased,
                window = window, legRate = rate, gameTotal = total, highTotal = highTotal, kickoffEpochMs = game.kickoffEpochMs,
                rationale = windowRationale(team, point, teased, window, rate),
            )
        }
        return legs
    }

    /** Every pairing of two [legs] from distinct games, priced at [settings.teaserPrice] and staked with
     *  fractional Kelly, ranked by EV descending. */
    private fun candidatesFrom(legs: List<TeaserLeg>, settings: ModelSettings): List<TeaserCandidate> {
        val out = mutableListOf<TeaserCandidate>()
        for (i in legs.indices) for (j in (i + 1) until legs.size) {
            val a = legs[i]; val b = legs[j]
            if (a.gameId == b.gameId) continue
            val winProbability = a.legRate * b.legRate
            val decimal = BettingEngine.decimalOdds(settings.teaserPrice)
            val ev = winProbability * decimal - 1.0
            val kelly = BettingEngine.kellyFraction(winProbability, decimal).coerceAtLeast(0.0)
            val stake = BettingEngine.stakeFor(kelly, settings)
            val score = (50.0 + 12.5 * ev * 100.0).coerceIn(0.0, 100.0)
            out += TeaserCandidate(
                legs = listOf(a, b), winProbability = winProbability, price = settings.teaserPrice, ev = ev,
                kellyFraction = kelly, stake = stake, score = score, grade = Safety.grade(score), tier = Safety.tier(score),
            )
        }
        return out.sortedByDescending { it.ev }
    }

    /**
     * Every qualifying leg and candidate pairing for the current week's not-yet-kicked-off games.
     * Qualifying legs come from the multi-book consensus spread when [Season.board] has one for a game,
     * else the ESPN/DraftKings line - see [consensusTeamSpread].
     */
    fun find(season: Season, user: UserState, nowEpochMs: Long): TeaserBoard {
        val settings = user.settings
        val currentWeek = (user.weekOverride ?: season.inferCurrentWeek(nowEpochMs)).coerceIn(1, REGULAR_SEASON_WEEKS)
        val games = season.gamesInWeek(currentWeek).filter { it.kickoffEpochMs > nowEpochMs && it.state == GameState.SCHEDULED }
        val legs = games.flatMap { g -> legsFor(g, season.board[g.id], settings) }.sortedByDescending { it.legRate }
        val candidates = candidatesFrom(legs, settings)
        val note = if (legs.size < 2) "Fewer than 2 qualifying legs this week - a teaser needs at least two games." else null
        return TeaserBoard(currentWeek, nowEpochMs, legs, candidates, note)
    }

    /**
     * WIN if every leg covers its [TeaserLeg.teasedPoint], PUSH if any leg lands exactly on it and none
     * lose (most books reduce a push leg out of the teaser rather than losing the whole ticket - see
     * docs/TEASERS.md), LOSS if any leg fails to cover, PENDING until every leg's game is final.
     */
    fun grade(bet: TeaserBet, season: Season): BetResult {
        var anyPush = false
        for (leg in bet.legs) {
            val game = season.games.firstOrNull { it.id == leg.gameId } ?: return BetResult.PENDING
            if (game.state != GameState.FINAL || game.homeScore == null || game.awayScore == null) return BetResult.PENDING
            val sideScore = if (leg.teamIsHome) game.homeScore else game.awayScore
            val otherScore = if (leg.teamIsHome) game.awayScore else game.homeScore
            val margin = (sideScore + leg.teasedPoint) - otherScore
            when {
                margin > 0.0 -> {}
                margin == 0.0 -> anyPush = true
                else -> return BetResult.LOSS
            }
        }
        return if (anyPush) BetResult.PUSH else BetResult.WIN
    }

    /** Realized profit for a graded [bet]: a push refunds the stake (0), same convention as
     *  [BettingEngine.profit]. */
    fun profit(bet: TeaserBet, result: BetResult): Double = when (result) {
        BetResult.WIN -> bet.stake * (BettingEngine.decimalOdds(bet.price) - 1.0)
        BetResult.LOSS -> -bet.stake
        BetResult.PUSH, BetResult.PENDING -> 0.0
    }

    /** Grades every recorded [UserState.teaserBets] against [season], with running totals. */
    fun ledger(season: Season, user: UserState): TeaserLedger {
        val rows = user.teaserBets.map { bet ->
            val result = grade(bet, season)
            TeaserLedgerRow(bet, result, profit(bet, result))
        }
        val staked = rows.sumOf { it.bet.stake }
        val profitSum = rows.sumOf { it.profit }
        val roi = if (staked > 0.0) profitSum / staked else null
        val totals = TeaserLedgerTotals(
            staked = staked, profit = profitSum, roi = roi,
            wins = rows.count { it.result == BetResult.WIN },
            losses = rows.count { it.result == BetResult.LOSS },
            pushes = rows.count { it.result == BetResult.PUSH },
        )
        return TeaserLedger(rows, totals)
    }
}

package com.survivor.engine

import com.survivor.engine.data.OddsApiParser
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Which market a [Quote] or [BetPick] covers. */
enum class Market(val label: String) {
    MONEYLINE("Moneyline"), SPREAD("Spread"), TOTAL("Total"),
}

/**
 * Where a [BetPick] came from. The two signals are deliberately never merged: [LINE_SHOP] compares
 * one book's price to a no-vig consensus built from other books (a real, small, low-variance edge),
 * while [MODEL] compares this engine's own win-probability estimate to the market (a much noisier,
 * speculative signal - the market is usually right). See docs/BETTING.md.
 */
enum class Signal(val label: String) {
    LINE_SHOP("Line shopping (no-vig edge)"),
    MODEL("Model vs. market (speculative)"),
}

/**
 * One sportsbook's price for one side of one market on one game, from The Odds API. [side] is a
 * [Team.abbr] for MONEYLINE/SPREAD or "OVER"/"UNDER" for TOTAL. [point] is null for MONEYLINE.
 */
@Serializable
data class Quote(
    val book: String,
    val market: Market,
    val side: String,
    val point: Double? = null,
    val price: Int,
    val updatedAtEpochMs: Long? = null,
)

/** One game's full multi-book odds board (moneyline, spread, total) from The Odds API. */
@Serializable
data class GameBoard(
    /** The Odds API's own event id; not the ESPN [Game.id] used as the key in [Season.board]. */
    val gameId: String? = null,
    val home: Team,
    val away: Team,
    val commenceEpochMs: Long,
    val quotes: List<Quote> = emptyList(),
    val fetchedAtEpochMs: Long,
)

/** One suggested bet from [BettingEngine.evaluate], with its full pricing rationale. */
data class BetPick(
    val gameId: String,
    val week: Int,
    val kickoffEpochMs: Long,
    val market: Market,
    /** [Team.abbr] for MONEYLINE/SPREAD, "OVER"/"UNDER" for TOTAL. */
    val side: String,
    val sideTeam: Team?,
    val point: Double?,
    val bestBook: String,
    val bestPrice: Int,
    /** No-vig consensus probability (LINE_SHOP) or model probability (MODEL) behind [ev]. */
    val fairProbability: Double,
    val fairPrice: Int,
    /** Books averaged into [fairProbability] for LINE_SHOP; 1 for MODEL (a single book's price). */
    val consensusBooks: Int,
    val ev: Double,
    val kellyFraction: Double,
    val stake: Double,
    val signal: Signal,
    val note: String = "",
    val rationale: String,
)

/** Every current-week bet suggestion, ranked line-shop-first then by [BetPick.ev] descending. */
data class BettingBoard(
    val week: Int,
    val generatedAtEpochMs: Long,
    val picks: List<BetPick>,
    /** Age of [Season.boardFetchedAtEpochMs] at evaluation time; null if the board was never fetched. */
    val boardAgeMs: Long?,
    /** Distinct books quoting any of this week's games. */
    val booksSeen: Int,
)

/** One recorded wager, entered by the user (typically from a [BetPick]). */
@Serializable
data class Bet(
    val id: String,
    val placedAtEpochMs: Long,
    val gameId: String,
    val week: Int,
    val market: Market,
    /** [Team.abbr] for MONEYLINE/SPREAD, "OVER"/"UNDER" for TOTAL. */
    val side: String,
    val point: Double? = null,
    val price: Int,
    val stake: Double,
    val book: String,
    /** Which signal produced this bet, if any; null for a bet entered without a suggestion. */
    val signal: Signal? = null,
    val note: String = "",
)

/** Settlement outcome of a [Bet] once its game is final. */
enum class BetResult { PENDING, WIN, LOSS, PUSH }

/** One graded [Bet]: its game, result, realized profit, and closing-line value. */
data class LedgerRow(
    val bet: Bet,
    val game: Game?,
    val result: BetResult,
    val profit: Double,
    /**
     * How much the line moved in the bettor's favor after the bet, using the last known ("closing")
     * DraftKings line on [game]: spread/total in points (closing point − bet point, the side's own
     * perspective), moneyline in probability (closing no-vig probability of the side − the raw implied
     * probability of the bet's own price, since a logged [Bet] does not retain the opposing price
     * needed to no-vig itself). Null when the game isn't graded yet, has no line, or (always, for
     * TOTAL) when no closing total is available - ESPN's line carries only spread and moneyline.
     */
    val closingLineValue: Double?,
)

/** Aggregate record and profitability over a set of [LedgerRow]s. */
data class LedgerTotals(
    val staked: Double,
    val profit: Double,
    /** profit / staked; null when nothing has been staked. */
    val roi: Double?,
    val wins: Int,
    val losses: Int,
    val pushes: Int,
) {
    val record: String get() = "$wins-$losses-$pushes"
}

/** Every logged [Bet], graded, with totals overall and split by [Signal] and by [Market]. */
data class Ledger(
    val rows: List<LedgerRow>,
    val totals: LedgerTotals,
    /** Only bets with a non-null [Bet.signal]; a bet logged without one is excluded here. */
    val bySignal: Map<Signal, LedgerTotals>,
    val byMarket: Map<Market, LedgerTotals>,
)

/** Every term of one [SideAssessment]'s Bet Score, kept separately so the UI can show the full
 *  breakdown - mirrors [SafetyComponents]'s role for the survivor Safety Score. [lineShopEvPct] and
 *  [modelEvPct] are already the exact percentage-point contributions summed (with [ModelSettings.modelWeight]
 *  applied to the model term) into [base], so the model's actual share of the score stays visible even
 *  though it's "speculative" - see docs/BETTING.md. */
data class BetScoreComponents(
    val base: Double,
    val bookConfidencePenalty: Double,
    val dispersionPenalty: Double,
    val movementAdjustment: Double,
    val staleBoardPenalty: Double,
    val lineShopEvPct: Double,
    val modelEvPct: Double,
) {
    val total: Double get() = (base - bookConfidencePenalty - dispersionPenalty + movementAdjustment - staleBoardPenalty).coerceIn(0.0, 100.0)
}

/**
 * One side of one market on one game, fully priced and graded 0-100. [side] is a [Team.abbr] for
 * MONEYLINE/SPREAD or "OVER"/"UNDER" for TOTAL, matching [BetPick.side]'s convention. See
 * [BettingEngine.board] and docs/BETTING.md for how every field here is computed.
 */
data class SideAssessment(
    val side: String,
    val sideTeam: Team?,
    /** Consensus line for SPREAD/TOTAL (this side's own point); null for MONEYLINE. */
    val point: Double?,
    val bestBook: String,
    val bestPrice: Int,
    val booksQuoting: Int,
    /** Multi-book no-vig mean at the consensus point; the ESPN/DraftKings line's own no-vig
     *  probability (or an assumed 50% at standard juice for SPREAD) when there's no odds board. */
    val fairProbability: Double?,
    val fairPrice: Int?,
    /** fairProbability x decimal(bestPrice) - 1; null when [fairProbability] is null. */
    val lineShopEv: Double?,
    /** Blended FPI/market win probability for MONEYLINE, P(cover) for SPREAD, null for TOTAL. */
    val modelProbability: Double?,
    val modelEv: Double?,
    /** Standard deviation of the consensus books' own no-vig probabilities for this side; 0.0 for a
     *  single paired book; null when there's no board to disperse at all. */
    val lineDispersion: Double?,
    /** Spread/total point move (positive = better for this side) or, for MONEYLINE, the no-vig
     *  probability move in percentage points, since the earliest [LineHistory] snapshot; null with
     *  no history. */
    val lineMovePoints: Double?,
    val score: Double,
    val grade: String,
    val tier: Tier,
    val components: BetScoreComponents,
    val rationale: String,
)

/** One market's both sides on one game, ranked against every other game's same market this week. */
data class MarketAssessment(
    val market: Market,
    /** Always both sides of the market, in a stable (home-first, or OVER-first) order. */
    val sides: List<SideAssessment>,
    /** The higher-[SideAssessment.score] side; what the UI leads with. */
    val best: SideAssessment,
    /** 1 = best [best].score across every game's [market] this week. */
    val rank: Int,
)

/** One current-week game with every market ([Market.MONEYLINE]/[Market.SPREAD] always attempted,
 *  [Market.TOTAL] only when priced) assessed and graded. Every SCHEDULED, not-yet-kicked-off game of
 *  the week appears, even one with an empty [markets] list (no odds anywhere for it yet). */
data class GameAssessment(
    val gameId: String,
    val kickoffEpochMs: Long,
    val home: Team,
    val away: Team,
    val markets: List<MarketAssessment>,
)

/** The full graded board for the current week: [BettingEngine.board]'s result, the replacement for the
 *  "edges only" [BettingBoard] as the Bets screen's primary data source. */
data class BetBoard(
    val week: Int,
    val generatedAtEpochMs: Long,
    /** Age of [Season.boardFetchedAtEpochMs] at generation time; null if the multi-book board was
     *  never fetched (every side still gets a score, priced off the ESPN/DraftKings line alone). */
    val boardAgeMs: Long?,
    /** Distinct books quoting any of this week's games' multi-book board. */
    val booksSeen: Int,
    val games: List<GameAssessment>,
) {
    /** The best-scoring sides across every game and market this week, highest score first. */
    fun topPicks(n: Int): List<SideAssessment> =
        games.asSequence().flatMap { it.markets.asSequence() }.map { it.best }.sortedByDescending { it.score }.take(n).toList()

    /**
     * Fractional-Kelly stake for [side] under [settings], zero unless its blended EV (the same
     * `lineShopEv + modelWeight x modelEv` the Bet Score is built from, recovered from
     * [SideAssessment.components]) is positive. Uses [SideAssessment.fairProbability], falling back to
     * [SideAssessment.modelProbability], as Kelly's probability.
     */
    fun suggestedStake(side: SideAssessment, settings: ModelSettings): Double {
        val blendedEv = (side.components.lineShopEvPct + side.components.modelEvPct) / 100.0
        if (blendedEv <= 0.0) return 0.0
        val p = side.fairProbability ?: side.modelProbability ?: return 0.0
        val kelly = BettingEngine.kellyFraction(p, BettingEngine.decimalOdds(side.bestPrice))
        return BettingEngine.stakeFor(kelly, settings)
    }
}

/**
 * Finds and prices bets: multi-book line shopping against a no-vig consensus (the primary, reliable
 * signal) and this engine's own model vs. the market (a secondary, clearly weaker signal), stakes them
 * with fractional Kelly, and grades a logged bet history from final scores. See docs/BETTING.md for the
 * full write-up of the formulas below.
 */
object BettingEngine {

    /** American odds → decimal odds: 1 + 100/|a| for a negative price, 1 + a/100 for positive. */
    fun decimalOdds(american: Int): Double =
        if (american < 0) 1.0 + 100.0 / abs(american) else 1.0 + american / 100.0

    /** Kelly fraction for a bet at probability [p] and [decimal] odds: (b·p − q)/b, b = decimal − 1. */
    fun kellyFraction(p: Double, decimal: Double): Double {
        val b = decimal - 1.0
        if (b <= 0.0) return 0.0
        return (b * p - (1.0 - p)) / b
    }

    /**
     * Bankroll stake for a [kellyFraction]: `bankroll × kellyFraction × kellyMultiplier`, capped at
     * `bankroll × maxStakePct` and rounded to whole dollars; zero when [kellyFraction] is not positive.
     */
    fun stakeFor(kellyFraction: Double, settings: ModelSettings): Double {
        if (kellyFraction <= 0.0) return 0.0
        val raw = settings.bankroll * kellyFraction * settings.kellyMultiplier
        val cap = settings.bankroll * settings.maxStakePct
        return Math.round(minOf(raw, cap)).toDouble()
    }

    /**
     * Every current-week bet suggestion: line-shopping picks first (needs [Season.board]), then model
     * picks (works off [Season.board] when present, else the ESPN/DraftKings [Game.line]). Only games
     * that haven't kicked off yet are considered.
     */
    fun evaluate(season: Season, user: UserState, nowEpochMs: Long): BettingBoard {
        val settings = user.settings
        val currentWeek = (user.weekOverride ?: season.inferCurrentWeek(nowEpochMs)).coerceIn(1, REGULAR_SEASON_WEEKS)
        val games = season.gamesInWeek(currentWeek).filter { it.kickoffEpochMs > nowEpochMs && it.state == GameState.SCHEDULED }
        val picks = mutableListOf<BetPick>()
        val boardsUsed = mutableListOf<GameBoard>()
        for (game in games) {
            val board = season.board[game.id]
            if (board != null) boardsUsed += board
            picks += lineShopPicks(game, board, settings)
            picks += modelPicks(game, board, season.ratings, settings)
        }
        val sorted = picks.sortedWith(compareByDescending<BetPick> { it.signal == Signal.LINE_SHOP }.thenByDescending { it.ev })
        val boardAgeMs = season.boardFetchedAtEpochMs?.let { nowEpochMs - it }
        val booksSeen = boardsUsed.flatMap { b -> b.quotes.map { it.book } }.toSet().size
        return BettingBoard(currentWeek, nowEpochMs, sorted, boardAgeMs, booksSeen)
    }

    // ---- Graded board (Bet Score) -----------------------------------------------------------

    /**
     * Every SCHEDULED, not-yet-kicked-off game of the current week, with MONEYLINE and SPREAD always
     * attempted (falling back to the ESPN/DraftKings line with no odds board) and TOTAL attempted only
     * when [ModelSettings.includeTotals] and the board prices a total for the game. Unlike [evaluate],
     * which only surfaces sides that clear an edge threshold, every side of every market gets a 0-100
     * [SideAssessment.score] - see docs/BETTING.md for the full Bet Score formula. [lineHistory] is
     * optional (defaults to empty) since it lives outside [Season]/[UserState]; pass the app's recorded
     * history to get real [SideAssessment.lineMovePoints].
     */
    fun board(season: Season, user: UserState, nowEpochMs: Long, lineHistory: LineHistory = LineHistory()): BetBoard {
        val settings = user.settings
        val currentWeek = (user.weekOverride ?: season.inferCurrentWeek(nowEpochMs)).coerceIn(1, REGULAR_SEASON_WEEKS)
        val games = season.gamesInWeek(currentWeek).filter { it.kickoffEpochMs > nowEpochMs && it.state == GameState.SCHEDULED }
        val boardAgeMs = season.boardFetchedAtEpochMs?.let { nowEpochMs - it }
        val booksSeen = games.mapNotNull { season.board[it.id] }.flatMap { b -> b.quotes.map { it.book } }.toSet().size

        data class Item(val gameId: String, val assessment: MarketAssessment)
        val items = mutableListOf<Item>()
        for (game in games) {
            val gboard = season.board[game.id]
            assessMoneyline(game, gboard, season.ratings, settings, lineHistory, boardAgeMs)?.let { items += Item(game.id, it) }
            assessSpread(game, gboard, season.ratings, settings, lineHistory, boardAgeMs)?.let { items += Item(game.id, it) }
            if (settings.includeTotals) assessTotal(game, gboard, settings, boardAgeMs)?.let { items += Item(game.id, it) }
        }
        val ranked = Market.entries.flatMap { market ->
            items.filter { it.assessment.market == market }
                .sortedByDescending { it.assessment.best.score }
                .mapIndexed { i, item -> item.copy(assessment = item.assessment.copy(rank = i + 1)) }
        }
        val byGame = ranked.groupBy { it.gameId }
        val gameAssessments = games.map { g ->
            GameAssessment(
                gameId = g.id, kickoffEpochMs = g.kickoffEpochMs, home = g.home, away = g.away,
                markets = byGame[g.id].orEmpty().map { it.assessment }.sortedBy { it.market.ordinal },
            )
        }
        return BetBoard(currentWeek, nowEpochMs, boardAgeMs, booksSeen, gameAssessments)
    }

    /** A market's consensus group for the graded board: at least 2 books quoting both sides at the
     *  same point, chosen as the group with the most such books. Independent of [MarketGroup] (used by
     *  [lineShopMarket]'s "better number" search) - the graded board doesn't hunt for a different point,
     *  it just needs the consensus one plus every book's own no-vig probability for dispersion. */
    private class ConsensusGroup(
        val canonical: Double?,
        val quotes: List<Quote>,
        val sideA: String,
        val sideB: String,
        val pairedBookCount: Int,
        val fairA: Double,
        val fairB: Double,
        val perBookA: List<Double>,
        val perBookB: List<Double>,
    )

    private fun consensusGroup(quotes: List<Quote>, market: Market, home: Team): ConsensusGroup? {
        if (quotes.isEmpty()) return null
        val groups = quotes.groupBy { canonicalPoint(market, it.side, it.point, home) }
        return groups.mapNotNull { (canonical, groupQuotes) ->
            val sides = groupQuotes.map { it.side }.distinct()
            if (sides.size != 2) return@mapNotNull null
            val (sideA, sideB) = sides
            val paired = groupQuotes.groupBy { it.book }.mapNotNull { (_, qs) ->
                val a = qs.firstOrNull { it.side == sideA }
                val b = qs.firstOrNull { it.side == sideB }
                if (a != null && b != null) a to b else null
            }
            if (paired.size < 2) return@mapNotNull null
            val perA = paired.map { (a, b) -> Probability.noVig(a.price, b.price) }
            val perB = paired.map { (a, b) -> Probability.noVig(b.price, a.price) }
            ConsensusGroup(canonical, groupQuotes, sideA, sideB, paired.size, perA.average(), perB.average(), perA, perB)
        }.maxByOrNull { it.pairedBookCount }
    }

    /** Sample standard deviation; 0.0 for fewer than two observations (never null - "0 for one book"). */
    private fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    private fun assessMoneyline(
        game: Game, board: GameBoard?, ratings: Map<Team, TeamRating>, settings: ModelSettings, history: LineHistory, boardAgeMs: Long?,
    ): MarketAssessment? {
        val consensus = board?.let { consensusGroup(it.quotes.filter { q -> q.market == Market.MONEYLINE }, Market.MONEYLINE, game.home) }
        val sides = mutableListOf<SideAssessment>()
        for (team in listOf(game.home, game.away)) {
            val abbr = team.abbr
            val price: Int; val book: String; val fairProbability: Double?; val booksQuoting: Int; val dispersion: Double?
            if (consensus != null && (abbr == consensus.sideA || abbr == consensus.sideB)) {
                val best = consensus.quotes.filter { it.side == abbr }.maxByOrNull { it.price } ?: continue
                price = best.price; book = best.book
                fairProbability = if (abbr == consensus.sideA) consensus.fairA else consensus.fairB
                booksQuoting = consensus.pairedBookCount
                dispersion = stdDev(if (abbr == consensus.sideA) consensus.perBookA else consensus.perBookB)
            } else {
                val homeMl = game.line?.homeMoneyline; val awayMl = game.line?.awayMoneyline
                if (homeMl == null || awayMl == null) continue
                val teamMl = if (team == game.home) homeMl else awayMl
                val oppMl = if (team == game.home) awayMl else homeMl
                price = teamMl; book = "DraftKings (ESPN)"
                fairProbability = Probability.noVig(teamMl, oppMl)
                booksQuoting = 1; dispersion = null
            }
            val decimal = decimalOdds(price)
            val fairPrice = fairProbability?.let { OddsApiParser.americanFromProbability(it) }
            val lineShopEv = fairProbability?.let { it * decimal - 1.0 }
            val estimate = ProbabilityResolver.resolve(game, team, isCurrentWeek = false, adjustment = null, ratings = ratings, settings = settings)
            val modelEv = estimate.probability * decimal - 1.0
            val lineMove = moneylineMovement(history, game, team)
            val components = scoreComponents(lineShopEv, modelEv, booksQuoting, dispersion, lineMove, boardAgeMs, settings, isMoneyline = true)
            val score = components.total
            val rationale = moneylineRationale(book, price, fairPrice, booksQuoting, fairProbability, lineShopEv)
            sides += SideAssessment(
                side = abbr, sideTeam = team, point = null, bestBook = book, bestPrice = price, booksQuoting = booksQuoting,
                fairProbability = fairProbability, fairPrice = fairPrice, lineShopEv = lineShopEv, modelProbability = estimate.probability,
                modelEv = modelEv, lineDispersion = dispersion, lineMovePoints = lineMove, score = score, grade = scoreGrade(score),
                tier = scoreTier(score), components = components, rationale = rationale,
            )
        }
        if (sides.size < 2) return null
        return MarketAssessment(Market.MONEYLINE, sides, sides.maxBy { it.score }, rank = 0)
    }

    private fun assessSpread(
        game: Game, board: GameBoard?, ratings: Map<Team, TeamRating>, settings: ModelSettings, history: LineHistory, boardAgeMs: Long?,
    ): MarketAssessment? {
        val consensus = board?.let { consensusGroup(it.quotes.filter { q -> q.market == Market.SPREAD }, Market.SPREAD, game.home) }
        val sides = mutableListOf<SideAssessment>()
        for (team in listOf(game.home, game.away)) {
            val abbr = team.abbr
            val price: Int; val book: String; val point: Double; val fairProbability: Double?; val booksQuoting: Int; val dispersion: Double?
            if (consensus != null && (abbr == consensus.sideA || abbr == consensus.sideB)) {
                val best = consensus.quotes.filter { it.side == abbr }.maxByOrNull { it.price } ?: continue
                val p = best.point ?: sidePointFromCanonical(Market.SPREAD, abbr, consensus.canonical, game.home) ?: continue
                price = best.price; book = best.book; point = p
                fairProbability = if (abbr == consensus.sideA) consensus.fairA else consensus.fairB
                booksQuoting = consensus.pairedBookCount
                dispersion = stdDev(if (abbr == consensus.sideA) consensus.perBookA else consensus.perBookB)
            } else {
                val homeSpread = game.line?.homeSpread ?: continue
                point = if (team == game.home) homeSpread else -homeSpread
                price = STANDARD_JUICE; book = "DraftKings (ESPN)"
                fairProbability = 0.5 // a fairly-set spread is, by construction, close to a coinflip at standard juice.
                booksQuoting = 1; dispersion = null
            }
            val decimal = decimalOdds(price)
            val fairPrice = fairProbability?.let { OddsApiParser.americanFromProbability(it) }
            val lineShopEv = fairProbability?.let { it * decimal - 1.0 }
            val estimate = ProbabilityResolver.resolve(game, team, isCurrentWeek = false, adjustment = null, ratings = ratings, settings = settings)
            val modelMargin = Probability.spreadFromWinProbability(estimate.probability, settings.marginSigma)
            val pCover = Probability.winProbabilityFromSpread(modelMargin + point, settings.marginSigma)
            val modelEv = pCover * decimal - 1.0
            val lineMove = spreadMovement(history, game, team)
            val components = scoreComponents(lineShopEv, modelEv, booksQuoting, dispersion, lineMove, boardAgeMs, settings, isMoneyline = false)
            val score = components.total
            val breakEvenPct = Probability.impliedFromAmerican(price) * 100.0
            val rationale = spreadRationale(abbr, point, price, book, fairPrice, booksQuoting, pCover, breakEvenPct)
            sides += SideAssessment(
                side = abbr, sideTeam = team, point = point, bestBook = book, bestPrice = price, booksQuoting = booksQuoting,
                fairProbability = fairProbability, fairPrice = fairPrice, lineShopEv = lineShopEv, modelProbability = pCover,
                modelEv = modelEv, lineDispersion = dispersion, lineMovePoints = lineMove, score = score, grade = scoreGrade(score),
                tier = scoreTier(score), components = components, rationale = rationale,
            )
        }
        if (sides.size < 2) return null
        return MarketAssessment(Market.SPREAD, sides, sides.maxBy { it.score }, rank = 0)
    }

    /** TOTAL has no ESPN fallback (the scoreboard payload never carries a total) and no model
     *  counterpart, so it's skipped entirely without a board that prices both Over and Under. */
    private fun assessTotal(game: Game, board: GameBoard?, settings: ModelSettings, boardAgeMs: Long?): MarketAssessment? {
        val quotes = board?.quotes?.filter { it.market == Market.TOTAL }.orEmpty()
        val consensus = consensusGroup(quotes, Market.TOTAL, game.home) ?: return null
        val sides = mutableListOf<SideAssessment>()
        for (label in listOf(consensus.sideA, consensus.sideB)) {
            val isA = label == consensus.sideA
            val best = consensus.quotes.filter { it.side == label }.maxByOrNull { it.price } ?: continue
            val fairProbability = if (isA) consensus.fairA else consensus.fairB
            val dispersion = stdDev(if (isA) consensus.perBookA else consensus.perBookB)
            val decimal = decimalOdds(best.price)
            val fairPrice = OddsApiParser.americanFromProbability(fairProbability)
            val lineShopEv = fairProbability * decimal - 1.0
            // No LineHistory support for totals (ESPN never carries one) and no model counterpart.
            val components = scoreComponents(lineShopEv, null, consensus.pairedBookCount, dispersion, null, boardAgeMs, settings, isMoneyline = false)
            val score = components.total
            val rationale = totalRationale(label, best.point, best.price, best.book, fairPrice, consensus.pairedBookCount)
            sides += SideAssessment(
                side = label, sideTeam = null, point = best.point, bestBook = best.book, bestPrice = best.price,
                booksQuoting = consensus.pairedBookCount, fairProbability = fairProbability, fairPrice = fairPrice,
                lineShopEv = lineShopEv, modelProbability = null, modelEv = null, lineDispersion = dispersion, lineMovePoints = null,
                score = score, grade = scoreGrade(score), tier = scoreTier(score), components = components, rationale = rationale,
            )
        }
        if (sides.size < 2) return null
        return MarketAssessment(Market.TOTAL, sides, sides.maxBy { it.score }, rank = 0)
    }

    /** No-vig probability move (in percentage points) for [team] since the earliest [LineHistory]
     *  snapshot that recorded both moneylines for [game], vs. the current ESPN/DraftKings line. */
    private fun moneylineMovement(history: LineHistory, game: Game, team: Team): Double? {
        val earliest = history.snapshots.sortedBy { it.takenAtEpochMs }
            .firstNotNullOfOrNull { s -> s.lines.firstOrNull { it.gameId == game.id && it.homeMoneyline != null && it.awayMoneyline != null } }
            ?: return null
        val currentHomeMl = game.line?.homeMoneyline ?: return null
        val currentAwayMl = game.line?.awayMoneyline ?: return null
        val earliestHomeMl = earliest.homeMoneyline!!; val earliestAwayMl = earliest.awayMoneyline!!
        val earliestP = if (team == game.home) Probability.noVig(earliestHomeMl, earliestAwayMl) else Probability.noVig(earliestAwayMl, earliestHomeMl)
        val currentP = if (team == game.home) Probability.noVig(currentHomeMl, currentAwayMl) else Probability.noVig(currentAwayMl, currentHomeMl)
        return (currentP - earliestP) * 100.0
    }

    /** Point move (positive = better for [team]) since the earliest recorded spread for [game], vs. the
     *  current ESPN/DraftKings spread. */
    private fun spreadMovement(history: LineHistory, game: Game, team: Team): Double? {
        val earliestHome = history.movement(game.id).firstOrNull()?.second ?: return null
        val currentHome = game.line?.homeSpread ?: return null
        val earliestSide = if (team == game.home) earliestHome else -earliestHome
        val currentSide = if (team == game.home) currentHome else -currentHome
        return currentSide - earliestSide
    }

    /** The shared Bet Score formula - see docs/BETTING.md. [modelEv] is weighted by
     *  [ModelSettings.modelWeight] before it ever reaches [BetScoreComponents.base], so the model's
     *  contribution stays a minor, clearly-labeled fraction of the score even when it's large. */
    private fun scoreComponents(
        lineShopEv: Double?, modelEv: Double?, booksQuoting: Int, dispersion: Double?, lineMovePoints: Double?,
        boardAgeMs: Long?, settings: ModelSettings, isMoneyline: Boolean,
    ): BetScoreComponents {
        val lineShopEvPct = (lineShopEv ?: 0.0) * 100.0
        val modelEvPct = settings.modelWeight * (modelEv ?: 0.0) * 100.0
        val base = 50.0 + 12.5 * (lineShopEvPct + modelEvPct)
        val bookConfidencePenalty = when {
            booksQuoting >= 6 -> 0.0
            booksQuoting >= 3 -> 2.0
            booksQuoting == 2 -> 5.0
            else -> 10.0
        }
        val dispersionPenalty = (100.0 * (dispersion ?: 0.0) * 0.5).coerceAtMost(6.0)
        val movementAdjustment = when {
            lineMovePoints == null -> 0.0
            isMoneyline -> (lineMovePoints * 0.5).coerceIn(-4.0, 4.0)
            else -> (lineMovePoints * 1.0).coerceIn(-4.0, 4.0)
        }
        val staleBoardPenalty = when {
            boardAgeMs == null -> 0.0
            boardAgeMs > 24L * 3_600_000L -> 10.0
            boardAgeMs > 6L * 3_600_000L -> 5.0
            else -> 0.0
        }
        return BetScoreComponents(base, bookConfidencePenalty, dispersionPenalty, movementAdjustment, staleBoardPenalty, lineShopEvPct, modelEvPct)
    }

    /** Bet Score letter grade - a slightly stricter scale than [Safety.grade] since most sides of most
     *  markets are -EV by construction (the vig) and should read as such. */
    fun scoreGrade(score: Double): String = when {
        score >= 92 -> "A+"
        score >= 85 -> "A"
        score >= 80 -> "A-"
        score >= 76 -> "B+"
        score >= 72 -> "B"
        score >= 68 -> "B-"
        score >= 60 -> "C"
        score >= 50 -> "D"
        else -> "F"
    }

    fun scoreTier(score: Double): Tier = when {
        score >= 76 -> Tier.STRONG
        score >= 68 -> Tier.ACCEPTABLE
        score >= 60 -> Tier.RISKY
        else -> Tier.AVOID
    }

    private fun formatNumber(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
    private fun formatSignedPoint(v: Double): String = (if (v > 0) "+" else "") + formatNumber(v)

    private fun moneylineRationale(book: String, price: Int, fairPrice: Int?, booksQuoting: Int, fairProbability: Double?, lineShopEv: Double?): String =
        String.format(
            Locale.US, "%s %s vs fair %s from %d book%s (no-vig %.1f%%); EV %+.1f%%.",
            book, formatAmerican(price), fairPrice?.let { formatAmerican(it) } ?: "—", booksQuoting, if (booksQuoting == 1) "" else "s",
            (fairProbability ?: 0.0) * 100.0, (lineShopEv ?: 0.0) * 100.0,
        )

    /** Never mentions a moneyline win probability - only "covering", since a spread's model number is
     *  a cover probability, not a straight-up win probability. */
    private fun spreadRationale(
        teamAbbr: String, point: Double, price: Int, book: String, fairPrice: Int?, booksQuoting: Int, coverProbability: Double, breakEvenPct: Double,
    ): String = String.format(
        Locale.US, "%s %s at %s (%s); consensus %s at fair %s from %d book%s; model has %s covering %.0f%% (break-even %.1f%%).",
        teamAbbr, formatSignedPoint(point), formatAmerican(price), book, formatSignedPoint(point),
        fairPrice?.let { formatAmerican(it) } ?: "—", booksQuoting, if (booksQuoting == 1) "" else "s",
        teamAbbr, coverProbability * 100.0, breakEvenPct,
    )

    private fun totalRationale(side: String, point: Double?, price: Int, book: String, fairPrice: Int?, booksQuoting: Int): String {
        val label = if (side.equals("OVER", ignoreCase = true)) "Over" else "Under"
        val pointText = point?.let { formatNumber(it) } ?: "—"
        return String.format(
            Locale.US, "%s %s at %s (%s); consensus %s at fair %s from %d book%s.",
            label, pointText, formatAmerican(price), book, pointText, fairPrice?.let { formatAmerican(it) } ?: "—", booksQuoting, if (booksQuoting == 1) "" else "s",
        )
    }

    /** Settles a [bet] against [game]'s final score; PENDING while the game isn't final yet. */
    fun grade(bet: Bet, game: Game?): BetResult {
        if (game == null || game.state != GameState.FINAL || game.homeScore == null || game.awayScore == null) return BetResult.PENDING
        val home = game.homeScore
        val away = game.awayScore
        return when (bet.market) {
            Market.MONEYLINE -> {
                val team = Team.fromAbbr(bet.side) ?: return BetResult.PENDING
                when {
                    game.isTie -> BetResult.PUSH
                    game.winner == team -> BetResult.WIN
                    else -> BetResult.LOSS
                }
            }
            Market.SPREAD -> {
                val team = Team.fromAbbr(bet.side) ?: return BetResult.PENDING
                val sideScore = if (team == game.home) home else away
                val otherScore = if (team == game.home) away else home
                val point = bet.point ?: 0.0
                val margin = (sideScore + point) - otherScore
                when {
                    margin > 0.0 -> BetResult.WIN
                    margin == 0.0 -> BetResult.PUSH
                    else -> BetResult.LOSS
                }
            }
            Market.TOTAL -> {
                val point = bet.point ?: return BetResult.PENDING
                val total = (home + away).toDouble()
                val isOver = bet.side.equals("OVER", ignoreCase = true)
                when {
                    total == point -> BetResult.PUSH
                    (isOver && total > point) || (!isOver && total < point) -> BetResult.WIN
                    else -> BetResult.LOSS
                }
            }
        }
    }

    /** Realized profit for a graded [bet]: stake × (decimal − 1) on a win, −stake on a loss, 0 otherwise. */
    fun profit(bet: Bet, result: BetResult): Double = when (result) {
        BetResult.WIN -> bet.stake * (decimalOdds(bet.price) - 1.0)
        BetResult.LOSS -> -bet.stake
        BetResult.PUSH, BetResult.PENDING -> 0.0
    }

    /** Grades every bet in [user], with running totals overall and split by [Signal] and [Market]. */
    fun ledger(season: Season, user: UserState): Ledger {
        val rows = user.bets.map { bet ->
            val game = season.games.firstOrNull { it.id == bet.gameId }
            val result = grade(bet, game)
            val prof = profit(bet, result)
            val clv = if (result == BetResult.PENDING) null else closingLineValue(bet, game)
            LedgerRow(bet, game, result, prof, clv)
        }
        val bySignal = rows.filter { it.bet.signal != null }.groupBy { it.bet.signal!! }.mapValues { (_, rs) -> totalsOf(rs) }
        val byMarket = rows.groupBy { it.bet.market }.mapValues { (_, rs) -> totalsOf(rs) }
        return Ledger(rows, totalsOf(rows), bySignal, byMarket)
    }

    private fun totalsOf(rows: List<LedgerRow>): LedgerTotals {
        val staked = rows.sumOf { it.bet.stake }
        val profitSum = rows.sumOf { it.profit }
        val roi = if (staked > 0.0) profitSum / staked else null
        return LedgerTotals(
            staked = staked, profit = profitSum, roi = roi,
            wins = rows.count { it.result == BetResult.WIN },
            losses = rows.count { it.result == BetResult.LOSS },
            pushes = rows.count { it.result == BetResult.PUSH },
        )
    }

    private fun closingLineValue(bet: Bet, game: Game?): Double? {
        if (game == null) return null
        val line = game.line ?: return null
        return when (bet.market) {
            Market.MONEYLINE -> {
                val team = Team.fromAbbr(bet.side) ?: return null
                val sideMl = if (team == game.home) line.homeMoneyline else line.awayMoneyline
                val otherMl = if (team == game.home) line.awayMoneyline else line.homeMoneyline
                if (sideMl == null || otherMl == null) return null
                Probability.noVig(sideMl, otherMl) - Probability.impliedFromAmerican(bet.price)
            }
            Market.SPREAD -> {
                val team = Team.fromAbbr(bet.side) ?: return null
                val homeSpread = line.homeSpread ?: return null
                val closingPoint = if (team == game.home) homeSpread else -homeSpread
                val betPoint = bet.point ?: return null
                closingPoint - betPoint
            }
            Market.TOTAL -> null
        }
    }

    // ---- Line shopping ----------------------------------------------------------------------

    private fun lineShopPicks(game: Game, board: GameBoard?, settings: ModelSettings): List<BetPick> {
        if (board == null) return emptyList()
        val picks = mutableListOf<BetPick>()
        picks += lineShopMarket(game, board, Market.MONEYLINE, settings, sigma = settings.marginSigma)
        picks += lineShopMarket(game, board, Market.SPREAD, settings, sigma = settings.marginSigma)
        if (settings.includeTotals) picks += lineShopMarket(game, board, Market.TOTAL, settings, sigma = settings.totalSigma)
        return picks
    }

    /** A market's canonical point: for SPREAD, the equivalent home-team spread, so a home quote and the
     *  matching away quote for the same line land in the same group; for TOTAL, the point itself; null
     *  (one group) for MONEYLINE. */
    private fun canonicalPoint(market: Market, side: String, point: Double?, home: Team): Double? = when (market) {
        Market.MONEYLINE -> null
        Market.TOTAL -> point
        Market.SPREAD -> point?.let { if (Team.fromAbbr(side) == home) it else -it }
    }

    /** Inverse of [canonicalPoint]: the point for [side] implied by a group's canonical point. */
    private fun sidePointFromCanonical(market: Market, side: String, canonical: Double?, home: Team): Double? = when (market) {
        Market.MONEYLINE -> null
        Market.TOTAL -> canonical
        Market.SPREAD -> canonical?.let { if (Team.fromAbbr(side) == home) it else -it }
    }

    /** How much better [newPoint] is than [consensusPoint] for [side], in "extra points of cover" terms
     *  that [Probability.shiftByPoints] can apply directly (positive = better for the bettor). For SPREAD
     *  the side-specific point already carries the right sign; for TOTAL, a lower total helps OVER and a
     *  higher total helps UNDER. */
    private fun coverPointDelta(market: Market, side: String, newPoint: Double, consensusPoint: Double): Double =
        if (market == Market.TOTAL && side.equals("OVER", ignoreCase = true)) consensusPoint - newPoint else newPoint - consensusPoint

    private class MarketGroup(
        val canonical: Double?,
        val quotes: List<Quote>,
        val sideA: String,
        val sideB: String,
        val pairedBookCount: Int,
        val fairA: Double,
        val fairB: Double,
    )

    /** Finds the consensus group (≥2 books quoting both sides) and every other group's quotes, and emits
     *  the plain line-shop picks plus "better number" picks for a book hanging a different, better line. */
    private fun lineShopMarket(game: Game, board: GameBoard, market: Market, settings: ModelSettings, sigma: Double): List<BetPick> {
        val quotes = board.quotes.filter { it.market == market }
        if (quotes.isEmpty()) return emptyList()
        val groups = quotes.groupBy { canonicalPoint(market, it.side, it.point, game.home) }
        val infos = groups.mapNotNull { (canonical, groupQuotes) ->
            val sides = groupQuotes.map { it.side }.distinct()
            if (sides.size != 2) return@mapNotNull null
            val (sideA, sideB) = sides
            val paired = groupQuotes.groupBy { it.book }.mapNotNull { (_, qs) ->
                val a = qs.firstOrNull { it.side == sideA }
                val b = qs.firstOrNull { it.side == sideB }
                if (a != null && b != null) a to b else null
            }
            if (paired.size < 2) return@mapNotNull null
            MarketGroup(
                canonical = canonical, quotes = groupQuotes, sideA = sideA, sideB = sideB,
                pairedBookCount = paired.size,
                fairA = paired.map { (a, b) -> Probability.noVig(a.price, b.price) }.average(),
                fairB = paired.map { (a, b) -> Probability.noVig(b.price, a.price) }.average(),
            )
        }
        val consensus = infos.maxByOrNull { it.pairedBookCount } ?: return emptyList()
        val picks = mutableListOf<BetPick>()
        val sides = listOf(consensus.sideA to consensus.fairA, consensus.sideB to consensus.fairB)

        // Plain line-shop: best price within the consensus group itself.
        for ((side, fair) in sides) {
            val best = consensus.quotes.filter { it.side == side }.maxByOrNull { it.price } ?: continue
            addLineShopPick(picks, game, market, side, best.point, best.book, best.price, fair, consensus.pairedBookCount, settings, note = "")
        }

        // Better number: a book hanging a different, more favorable line for a side.
        for ((side, fair) in sides) {
            val overallBest = quotes.filter { it.side == side }.maxByOrNull { it.price } ?: continue
            val overallCanonical = canonicalPoint(market, overallBest.side, overallBest.point, game.home)
            if (overallCanonical == consensus.canonical) continue
            val consensusSidePoint = sidePointFromCanonical(market, side, consensus.canonical, game.home) ?: continue
            val newPoint = overallBest.point ?: continue
            val delta = coverPointDelta(market, side, newPoint, consensusSidePoint)
            val adjustedFair = Probability.shiftByPoints(fair, delta, sigma)
            addLineShopPick(picks, game, market, side, newPoint, overallBest.book, overallBest.price, adjustedFair, consensus.pairedBookCount, settings, note = "better number")
        }
        return picks
    }

    private fun addLineShopPick(
        out: MutableList<BetPick>, game: Game, market: Market, side: String, point: Double?,
        book: String, price: Int, fairProbability: Double, consensusBooks: Int, settings: ModelSettings, note: String,
    ) {
        val decimal = decimalOdds(price)
        val ev = fairProbability * decimal - 1.0
        if (ev < settings.minLineShopEdge) return
        val fairPriceAmerican = OddsApiParser.americanFromProbability(fairProbability)
        val kelly = kellyFraction(fairProbability, decimal)
        val stake = stakeFor(kelly, settings)
        val rationale = lineShopRationale(book, price, fairPriceAmerican, consensusBooks, fairProbability, ev, note)
        out += BetPick(
            gameId = game.id, week = game.week, kickoffEpochMs = game.kickoffEpochMs, market = market, side = side,
            sideTeam = Team.fromAbbr(side), point = point, bestBook = book, bestPrice = price,
            fairProbability = fairProbability, fairPrice = fairPriceAmerican, consensusBooks = consensusBooks,
            ev = ev, kellyFraction = kelly, stake = stake, signal = Signal.LINE_SHOP, note = note, rationale = rationale,
        )
    }

    private fun lineShopRationale(book: String, price: Int, fairPrice: Int, consensusBooks: Int, fairProbability: Double, ev: Double, note: String): String {
        val suffix = if (note.isNotBlank()) " ($note)" else ""
        return String.format(
            Locale.US, "%s %s vs fair %s from %d books (no-vig %.1f%%); EV %+.1f%%.%s",
            book, formatAmerican(price), formatAmerican(fairPrice), consensusBooks, fairProbability * 100.0, ev * 100.0, suffix,
        )
    }

    // ---- Model vs. market ----------------------------------------------------------------------

    private data class PricedLine(val point: Double?, val price: Int, val book: String)

    /** Best MONEYLINE price per side from [board], falling back to the ESPN/DraftKings line when the
     *  board has no bookmaker quotes for this game (or is missing entirely). */
    private fun moneylinePrices(game: Game, board: GameBoard?): Map<Team, PricedLine> {
        if (board != null) {
            val fromBoard = listOf(game.home, game.away).mapNotNull { team ->
                board.quotes.filter { it.market == Market.MONEYLINE && it.side == team.abbr }
                    .maxByOrNull { it.price }?.let { team to PricedLine(null, it.price, it.book) }
            }.toMap()
            if (fromBoard.size == 2) return fromBoard
        }
        val line = game.line ?: return emptyMap()
        val homeMl = line.homeMoneyline; val awayMl = line.awayMoneyline
        if (homeMl == null || awayMl == null) return emptyMap()
        return mapOf(game.home to PricedLine(null, homeMl, "DraftKings (ESPN)"), game.away to PricedLine(null, awayMl, "DraftKings (ESPN)"))
    }

    /** Best SPREAD price for [team] from [board], falling back to the ESPN/DraftKings spread priced at
     *  the standard −110 (ESPN's scoreboard payload carries the number but not the juice). */
    private fun spreadPrice(game: Game, board: GameBoard?, team: Team): PricedLine? {
        if (board != null) {
            val best = board.quotes.filter { it.market == Market.SPREAD && it.side == team.abbr && it.point != null }
                .maxByOrNull { it.price }
            if (best != null) return PricedLine(best.point, best.price, best.book)
        }
        val homeSpread = game.line?.homeSpread ?: return null
        val point = if (team == game.home) homeSpread else -homeSpread
        return PricedLine(point, STANDARD_JUICE, "DraftKings (ESPN)")
    }

    private fun modelPicks(game: Game, board: GameBoard?, ratings: Map<Team, TeamRating>, settings: ModelSettings): List<BetPick> {
        val picks = mutableListOf<BetPick>()
        val mlPrices = moneylinePrices(game, board)
        for (team in listOf(game.home, game.away)) {
            val priced = mlPrices[team] ?: continue
            val estimate = ProbabilityResolver.resolve(game, team, isCurrentWeek = false, adjustment = null, ratings = ratings, settings = settings)
            addModelPick(picks, game, Market.MONEYLINE, team, null, priced.price, priced.book, estimate.probability, estimate.marketProbability, settings)
        }
        for (team in listOf(game.home, game.away)) {
            val priced = spreadPrice(game, board, team) ?: continue
            val point = priced.point ?: continue
            val estimate = ProbabilityResolver.resolve(game, team, isCurrentWeek = false, adjustment = null, ratings = ratings, settings = settings)
            val modelMargin = Probability.spreadFromWinProbability(estimate.probability, settings.marginSigma)
            val pCover = Probability.winProbabilityFromSpread(modelMargin + point, settings.marginSigma)
            addModelPick(picks, game, Market.SPREAD, team, point, priced.price, priced.book, pCover, estimate.marketProbability, settings)
        }
        return picks
    }

    private fun addModelPick(
        out: MutableList<BetPick>, game: Game, market: Market, team: Team, point: Double?,
        price: Int, book: String, modelProbability: Double, marketProbability: Double?, settings: ModelSettings,
    ) {
        val decimal = decimalOdds(price)
        val ev = modelProbability * decimal - 1.0
        if (ev < settings.minModelEdge) return
        val fairPriceAmerican = OddsApiParser.americanFromProbability(modelProbability)
        val kelly = kellyFraction(modelProbability, decimal)
        val stake = stakeFor(kelly, settings)
        val rationale = modelRationale(market, team, modelProbability, marketProbability)
        out += BetPick(
            gameId = game.id, week = game.week, kickoffEpochMs = game.kickoffEpochMs, market = market, side = team.abbr,
            sideTeam = team, point = point, bestBook = book, bestPrice = price, fairProbability = modelProbability,
            fairPrice = fairPriceAmerican, consensusBooks = 1, ev = ev, kellyFraction = kelly, stake = stake,
            signal = Signal.MODEL, note = "", rationale = rationale,
        )
    }

    private fun modelRationale(market: Market, team: Team, modelProbability: Double, marketProbability: Double?): String {
        val marketPct = (marketProbability ?: modelProbability) * 100.0
        val verb = if (market == Market.SPREAD) "to cover" else "to win"
        return String.format(Locale.US, "Model has %s %s %.0f%% vs market %.0f%%; speculative.", team.abbr, verb, modelProbability * 100.0, marketPct)
    }

    private fun formatAmerican(price: Int): String = if (price > 0) "+$price" else price.toString()

    private const val STANDARD_JUICE = -110
}

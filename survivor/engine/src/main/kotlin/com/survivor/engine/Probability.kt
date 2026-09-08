package com.survivor.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Pure probability conversions. Everything here is transparent and documented in docs/MODEL.md. */
object Probability {

    /** American odds → raw implied probability (includes the book's vig). */
    fun impliedFromAmerican(odds: Int): Double =
        if (odds < 0) -odds.toDouble() / (-odds.toDouble() + 100.0) else 100.0 / (odds.toDouble() + 100.0)

    /**
     * No-vig win probability for side A given both sides' American moneylines.
     * Raw implied probabilities are normalised so the pair sums to 100%.
     */
    fun noVig(oddsA: Int, oddsB: Int): Double {
        val a = impliedFromAmerican(oddsA)
        val b = impliedFromAmerican(oddsB)
        return a / (a + b)
    }

    /**
     * Spread → win probability. [pointsFavored] is positive when the team is favored (a "-7" favorite
     * passes 7.0). Final margin is modelled as Normal(pointsFavored, sigma), a fit to historical NFL
     * closing lines, so P(win) = Φ(pointsFavored / sigma). See docs/MODEL.md for the lookup table.
     */
    fun winProbabilityFromSpread(pointsFavored: Double, sigma: Double = ModelSettings().marginSigma): Double =
        normalCdf(pointsFavored / sigma).coerceIn(0.01, 0.99)

    /** Inverse of [winProbabilityFromSpread]: the spread that a probability corresponds to. */
    fun spreadFromWinProbability(p: Double, sigma: Double = ModelSettings().marginSigma): Double =
        inverseNormalCdf(p.coerceIn(0.001, 0.999)) * sigma

    /** Shifts a probability by [points] of spread (positive = better for the team). */
    fun shiftByPoints(p: Double, points: Double, sigma: Double): Double =
        if (points == 0.0) p else winProbabilityFromSpread(spreadFromWinProbability(p, sigma) + points, sigma)

    /** Shrinks a future-week probability toward 50%: 0.5 + (p - 0.5) * (1 - d)^weeksAhead. */
    fun discount(p: Double, weeksAhead: Int, discountPerWeek: Double): Double {
        if (weeksAhead <= 0 || discountPerWeek <= 0.0) return p
        var f = 1.0
        repeat(weeksAhead) { f *= (1.0 - discountPerWeek) }
        return 0.5 + (p - 0.5) * f
    }

    /** Standard normal CDF via the Abramowitz–Stegun 7.1.26 erf approximation (|error| < 1.5e-7). */
    fun normalCdf(z: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(z) / sqrt(2.0))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t *
            exp(-z * z / 2.0)
        return if (z >= 0) 0.5 * (1.0 + y) else 0.5 * (1.0 - y)
    }

    /** Acklam's rational approximation of the inverse normal CDF (relative error ~1e-9). */
    fun inverseNormalCdf(p: Double): Double {
        require(p > 0.0 && p < 1.0) { "p must be in (0,1)" }
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
        val pLow = 0.02425
        val pHigh = 1 - pLow
        return when {
            p < pLow -> {
                val q = sqrt(-2 * ln(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
            p <= pHigh -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
            }
            else -> {
                val q = sqrt(-2 * ln(1 - p))
                -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
        }
    }
}

/** One team's win probability for one game, with provenance so the UI can label every number. */
data class ProbabilityEstimate(
    val probability: Double,
    val source: ProbabilitySource,
    /** Spread from this team's perspective (negative = favored), as the market or projection implies. */
    val teamSpread: Double?,
    /** This team's American moneyline when a line exists. */
    val teamMoneyline: Int?,
    val opponentMoneyline: Int?,
    val marketProbability: Double?,
    val fpiProbability: Double?,
    val adjustmentPoints: Double,
    val lineFetchedAtEpochMs: Long?,
) {
    val hasMoneyline: Boolean get() = teamMoneyline != null && opponentMoneyline != null
}

object ProbabilityResolver {

    /**
     * Resolves a team's win probability for [game] using the source priority in [ProbabilitySource]:
     * manual override → consensus moneyline → book moneyline → (future weeks) spread/FPI blend →
     * spread → FPI projection → FPI rating gap. Injury / QB / weather point adjustments are applied
     * as a spread shift on top of whichever automated source won.
     */
    fun resolve(
        game: Game,
        team: Team,
        isCurrentWeek: Boolean,
        adjustment: Adjustment?,
        ratings: Map<Team, TeamRating>,
        settings: ModelSettings,
    ): ProbabilityEstimate {
        require(game.involves(team))
        val home = team == game.home
        val sigma = settings.marginSigma
        val line = game.line
        val consensus = game.consensus

        fun teamMl(l: MarketLine?): Int? = l?.let { if (home) it.homeMoneyline else it.awayMoneyline }
        fun oppMl(l: MarketLine?): Int? = l?.let { if (home) it.awayMoneyline else it.homeMoneyline }
        fun teamSpread(l: MarketLine?): Double? = l?.homeSpread?.let { if (home) it else -it }

        val consensusP = consensus?.let { l -> val a = teamMl(l); val b = oppMl(l); if (a != null && b != null) Probability.noVig(a, b) else null }
        val bookMlP = line?.let { l -> val a = teamMl(l); val b = oppMl(l); if (a != null && b != null) Probability.noVig(a, b) else null }
        val spreadP = teamSpread(line)?.let { Probability.winProbabilityFromSpread(-it, sigma) }
        val fpiP = game.fpi?.let { if (home) it.homeWinProbability else 1.0 - it.homeWinProbability }?.coerceIn(0.01, 0.99)
        val ratingP = run {
            val a = ratings[team]?.fpi
            val b = ratings[game.opponentOf(team)]?.fpi
            if (a == null || b == null) null else {
                val hfa = if (game.neutralSite) 0.0 else if (home) settings.homeFieldPoints else -settings.homeFieldPoints
                Probability.winProbabilityFromSpread(a - b + hfa, sigma)
            }
        }
        val marketP = consensusP ?: bookMlP ?: spreadP

        val displaySpread = teamSpread(line) ?: marketP?.let { -Probability.spreadFromWinProbability(it, sigma) }
            ?: fpiP?.let { -Probability.spreadFromWinProbability(it, sigma) }
            ?: ratingP?.let { -Probability.spreadFromWinProbability(it, sigma) }

        val adjPoints = adjustment?.let { it.injuryPoints * settings.injuryWeight + it.qbPoints + it.weatherPoints * settings.weatherPenalty } ?: 0.0

        val (base, source) = when {
            adjustment?.overrideWinProbability != null -> adjustment.overrideWinProbability.coerceIn(0.01, 0.99) to ProbabilitySource.MANUAL_OVERRIDE
            isCurrentWeek && consensusP != null -> consensusP to ProbabilitySource.ODDS_API_MONEYLINE
            isCurrentWeek && bookMlP != null -> bookMlP to ProbabilitySource.MARKET_MONEYLINE
            isCurrentWeek && spreadP != null -> spreadP to ProbabilitySource.MARKET_SPREAD
            !isCurrentWeek && marketP != null && fpiP != null ->
                (settings.futureMarketWeight * marketP + (1 - settings.futureMarketWeight) * fpiP) to ProbabilitySource.MARKET_SPREAD_FPI_BLEND
            !isCurrentWeek && bookMlP != null -> bookMlP to ProbabilitySource.MARKET_MONEYLINE
            !isCurrentWeek && spreadP != null -> spreadP to ProbabilitySource.MARKET_SPREAD
            fpiP != null -> fpiP to ProbabilitySource.FPI_PROJECTION
            ratingP != null -> ratingP to ProbabilitySource.FPI_RATING_SPREAD
            else -> 0.5 to ProbabilitySource.NONE
        }
        val adjusted = if (source == ProbabilitySource.MANUAL_OVERRIDE) base else Probability.shiftByPoints(base, adjPoints, sigma)

        return ProbabilityEstimate(
            probability = adjusted.coerceIn(0.01, 0.99),
            source = source,
            teamSpread = displaySpread,
            teamMoneyline = teamMl(line),
            opponentMoneyline = oppMl(line),
            marketProbability = marketP,
            fpiProbability = fpiP,
            adjustmentPoints = adjPoints,
            lineFetchedAtEpochMs = consensus?.fetchedAtEpochMs ?: line?.fetchedAtEpochMs,
        )
    }
}

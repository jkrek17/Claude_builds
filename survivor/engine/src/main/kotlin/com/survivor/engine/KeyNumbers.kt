package com.survivor.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Key-number margin model for the spread market: the probability of every integer final home-team
 * margin, fitted on 4,175 regular-season games (2010-2025, nflverse closing lines). See docs/BETTING.md's
 * "Key numbers" section for the full write-up and validation numbers.
 *
 * `P(home margin = k | expected home margin s) ~ N(k; s, sigma=13.0) x w(|k|)`, k an integer in -60..60,
 * where `w` is the fitted key-number weight table ([WEIGHTS]). A plain normal curve badly underweights how
 * often NFL final margins land exactly on 3, 7, 10, 14, etc. (and badly overweights an exact tie, k=0) -
 * this model corrects that without changing the underlying mean.
 *
 * SIGN CONVENTION: every "home margin" / "expected home margin" parameter in this file is POSITIVE when
 * the home team is favored - the opposite of [MarketLine.homeSpread]'s convention (negative when the home
 * team is favored). This matches how the model was originally fit and specified. Callers passing a market
 * home spread (e.g. [MarketLine.homeSpread] or a [Game.line]) must negate it first. [coverProbability]'s
 * own [teamSpread] parameter is the ordinary betting convention instead (negative when that team is
 * favored) so a caller pricing one team's own bet doesn't have to think about the flip for that part.
 *
 * This model is used only for the SPREAD betting market ([BettingEngine]). It is deliberately NOT used for
 * survivor win probabilities, which keep [Probability.winProbabilityFromSpread]'s smooth normal curve
 * (sigma = 11.0) - see that function's doc.
 */
object KeyNumbers {

    /** Standard deviation of the underlying normal margin model, points. */
    private const val SIGMA = 13.0
    private const val MIN_K = -60
    private const val MAX_K = 60

    /** Fitted key-number weights by |margin|; any |k| not listed here (>= 22) defaults to 1.0. */
    private val WEIGHTS: Map<Int, Double> = mapOf(
        0 to 0.12, 1 to 0.83, 2 to 0.84, 3 to 2.79, 4 to 0.98, 5 to 0.79, 6 to 1.34, 7 to 1.83,
        8 to 0.91, 9 to 0.34, 10 to 1.23, 11 to 0.52, 12 to 0.46, 13 to 0.64, 14 to 1.49, 15 to 0.53,
        16 to 0.83, 17 to 1.22, 18 to 0.98, 19 to 0.48, 20 to 0.97, 21 to 1.32,
    )

    private fun weight(k: Int): Double = WEIGHTS[abs(k)] ?: 1.0

    private fun normalPdf(k: Int, mean: Double): Double {
        val z = (k - mean) / SIGMA
        return exp(-0.5 * z * z) / (SIGMA * sqrt(2.0 * PI))
    }

    /**
     * The normalised probability of every integer final home-team margin from -60 to 60, for a market/model
     * whose expected home margin (positive = home favored - see the class doc) is [homeMargin]. The
     * returned array always sums to 1.0; index `i` holds `P(margin = i - 60)` - use [marginProbability] to
     * look a margin up directly instead of computing that offset by hand.
     */
    fun marginDistribution(homeMargin: Double): DoubleArray {
        val raw = DoubleArray(MAX_K - MIN_K + 1) { i -> normalPdf(MIN_K + i, homeMargin) * weight(MIN_K + i) }
        val total = raw.sum()
        for (i in raw.indices) raw[i] = raw[i] / total
        return raw
    }

    /** Looks up [distribution]'s (from [marginDistribution]) probability for an integer [margin]; 0.0 for
     *  a margin outside -60..60 (the model's support). */
    fun marginProbability(distribution: DoubleArray, margin: Int): Double =
        if (margin < MIN_K || margin > MAX_K) 0.0 else distribution[margin - MIN_K]

    /** Win/push/loss of one spread bet, as raw fractions of every possible final margin - always summing
     *  to 1.0 (not "of decided bets"; see [winOfDecided] for that). */
    data class CoverOutcome(val win: Double, val push: Double, val loss: Double) {
        /** Win probability among the bets that don't push - the number ordinarily quoted as "cover %",
         *  since a push refunds the stake rather than counting as a loss. 0.0 if every outcome pushes. */
        val winOfDecided: Double get() = if (push >= 1.0) 0.0 else win / (1.0 - push)
    }

    /**
     * P(this team covers [teamSpread]), given the market/model's [homeSpreadConsensus] (its expected HOME
     * margin, positive when the home team is favored - see the class doc for this sign convention, which
     * is NOT the same as [teamSpread]'s). [teamSpread] is this team's own line in the ordinary betting
     * convention (negative when this team is favored). The team covers when
     * `(its final margin + teamSpread) > 0`, and pushes at exactly 0.
     */
    fun coverProbability(teamSpread: Double, teamIsHome: Boolean, homeSpreadConsensus: Double): CoverOutcome {
        val distribution = marginDistribution(homeSpreadConsensus)
        var win = 0.0
        var push = 0.0
        var loss = 0.0
        for (k in MIN_K..MAX_K) {
            val homeMargin = k.toDouble()
            val teamMargin = if (teamIsHome) homeMargin else -homeMargin
            val value = teamMargin + teamSpread
            val p = marginProbability(distribution, k)
            when {
                value > 0.0 -> win += p
                value == 0.0 -> push += p
                else -> loss += p
            }
        }
        return CoverOutcome(win, push, loss)
    }

    /**
     * P(the home team wins outright), given [homeSpread] (its expected HOME margin, positive = home
     * favored - see the class doc), expressed as a fraction of decided (non-tie) games -
     * `P(margin > 0) / (1 - P(tie))` - matching how NFL win probabilities are normally quoted (a tie is
     * vanishingly rare and conventionally excluded rather than counted as half a loss).
     */
    fun winProbability(homeSpread: Double): Double {
        val distribution = marginDistribution(homeSpread)
        val tie = marginProbability(distribution, 0)
        var win = 0.0
        for (k in 1..MAX_K) win += marginProbability(distribution, k)
        return if (tie >= 1.0) 0.0 else win / (1.0 - tie)
    }

    /**
     * The cover-probability value, in percentage points, of moving this team's own line from [fromLine] to
     * [toLine] while the market/model's expected home margin ([homeSpreadConsensus], same sign convention
     * as [marginDistribution]) stays fixed - i.e. "buying" or "selling" half a point (or more) on an
     * otherwise-unchanged game. Positive when [toLine] is better for the bettor (e.g. -3 -> -2.5 for a
     * favorite). This is where a key number's real value shows up: crossing exactly a key number captures
     * a large share of that number's whole push probability, far more than a generic half point elsewhere
     * on the curve. See docs/BETTING.md.
     */
    fun halfPointValue(fromLine: Double, toLine: Double, teamIsHome: Boolean, homeSpreadConsensus: Double): Double {
        val from = coverProbability(fromLine, teamIsHome, homeSpreadConsensus).winOfDecided
        val to = coverProbability(toLine, teamIsHome, homeSpreadConsensus).winOfDecided
        return (to - from) * 100.0
    }
}

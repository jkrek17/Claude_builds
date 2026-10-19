package com.survivor.engine

enum class Tier(val label: String) { STRONG("Strong"), ACCEPTABLE("Acceptable"), RISKY("Risky"), AVOID("Avoid") }

/** Every term of the Survivor Safety Score, kept separately so the UI can show the full breakdown. */
data class SafetyComponents(
    val base: Double,
    val roadPenalty: Double,
    val divisionalPenalty: Double,
    val restPenalty: Double,
    val travelPenalty: Double,
    val qbPenalty: Double,
    val marketAdjustment: Double,
    val futureCost: Double,
    val scarcityPenalty: Double,
    val pathPenalty: Double,
    val thresholdPenalty: Double,
    val leverageAdjustment: Double,
) {
    val matchupRisk: Double get() = roadPenalty + divisionalPenalty + restPenalty + travelPenalty + qbPenalty
    val total: Double
        get() = (base - matchupRisk + marketAdjustment - futureCost - scarcityPenalty - pathPenalty - thresholdPenalty + leverageAdjustment)
            .coerceIn(0.0, 100.0)
}

/** Pool-equity proxy when an ownership estimate exists. See docs/MODEL.md "Leverage". */
data class Leverage(val pickShare: Double, val expectedEquity: Double, val leverageScore: Double)

object Safety {
    /** Largest leverage score (in points) allowed to feed the Safety Score before the strategy weight. */
    const val LEVERAGE_CAP = 30.0


    /**
     * Pool equity under double elimination: your expected SHARE of the pool after this week, relative to an
     * unowned pick at the same win probability, so [Leverage.leverageScore] is the pure ownership effect in %.
     *
     * Entry weights after the week: alive with no strike = 1, alive with a strike in hand = [strikeValueRatio]
     * (the relative worth of a one-strike entry, e.g. P(0 further losses) / P(≤1 further loss) along the
     * remaining route), eliminated = 0. Everyone on your team shares your fate; the rest of the field wins at
     * [fieldWinProbability]; [fieldZeroStrikeFraction] of the alive field still has no strike. The expectation
     * is taken over your team's win/loss, so a chalk loss that strikes 42% of the field along with you is
     * priced correctly: it barely moves your share, whereas surviving a week in which the chalk fell does.
     */
    fun poolEquity(
        p: Double,
        pickShare: Double?,
        fieldWinProbability: Double,
        strikesUsed: Int = 0,
        fieldZeroStrikeFraction: Double = 1.0,
        strikeValueRatio: Double = 0.3,
    ): Leverage? {
        if (pickShare == null) return null
        val share = pickShare.coerceIn(0.0, 1.0)
        val f = fieldWinProbability.coerceIn(0.01, 0.99)
        val z0 = fieldZeroStrikeFraction.coerceIn(0.0, 1.0)
        val r = strikeValueRatio.coerceIn(0.0, 1.0)
        val strike = strikesUsed >= 1
        fun groupWeight(winProb: Double) = z0 * (winProb + (1 - winProb) * r) + (1 - z0) * (winProb * r)
        fun mine(win: Boolean) = if (win) (if (strike) r else 1.0) else (if (strike) 0.0 else r)
        fun equity(o: Double): Double {
            val totalIfWin = o * groupWeight(1.0) + (1 - o) * groupWeight(f)
            val totalIfLoss = o * groupWeight(0.0) + (1 - o) * groupWeight(f)
            return p * mine(true) / totalIfWin + (1 - p) * mine(false) / totalIfLoss
        }
        val owned = equity(share)
        val unowned = equity(0.0)
        return Leverage(share, owned, if (unowned > 0) (owned / unowned - 1.0) * 100.0 else 0.0)
    }

    /** Single-elimination approximation kept for callers without strike context; prefer [poolEquity]. */
    fun leverage(p: Double, pickShare: Double?, fieldAverageWinProbability: Double): Leverage? =
        poolEquity(p, pickShare, fieldAverageWinProbability)

    fun components(
        estimate: ProbabilityEstimate,
        situation: Situation,
        adjustment: Adjustment?,
        opportunityCost: Double,
        seasonPathLoss: Double,
        premiumSpots: Int,
        leverage: Leverage?,
        strikesUsed: Int,
        settings: ModelSettings,
    ): SafetyComponents {
        val p = estimate.probability
        val strikeMultiplier = if (strikesUsed >= 1) settings.strikeFutureWeightMultiplier else 1.0
        val restDisadvantage = (-situation.restAdvantageDays).coerceAtLeast(0)
        val marketAdjustment = when (estimate.source) {
            ProbabilitySource.ODDS_API_MONEYLINE, ProbabilitySource.MARKET_MONEYLINE -> settings.marketConfidenceBonus
            ProbabilitySource.MARKET_SPREAD, ProbabilitySource.MARKET_SPREAD_FPI_BLEND, ProbabilitySource.MANUAL_OVERRIDE -> 0.0
            ProbabilitySource.FPI_PROJECTION, ProbabilitySource.FPI_RATING_SPREAD -> -settings.projectionOnlyPenalty
            ProbabilitySource.NONE -> -settings.projectionOnlyPenalty * 2
        }
        val shortfall = (settings.minimumAcceptableWinProbability - p).coerceAtLeast(0.0) * 100.0
        return SafetyComponents(
            base = p * 100.0,
            roadPenalty = if (!situation.isHome && !situation.neutral) settings.roadPenalty else 0.0,
            divisionalPenalty = if (situation.divisional) settings.divisionalPenalty else 0.0,
            restPenalty = restDisadvantage * settings.shortRestPenaltyPerDay,
            travelPenalty = situation.timeZonesCrossed * settings.travelPenaltyPerTimeZone,
            qbPenalty = if (adjustment != null && adjustment.qbPoints != 0.0) settings.qbUncertaintyPenalty else 0.0,
            marketAdjustment = marketAdjustment,
            futureCost = opportunityCost * settings.futureValueWeight * strikeMultiplier,
            scarcityPenalty = premiumSpots.coerceAtMost(3) * settings.futureScarcityWeight * strikeMultiplier,
            pathPenalty = seasonPathLoss * settings.pathLossWeight * strikeMultiplier,
            thresholdPenalty = shortfall * if (strikesUsed >= 1) 1.5 else 0.5,
            // Leverage is a proxy; clamp it so ownership can move a score by at most ±LEVERAGE_CAP × weight points.
            leverageAdjustment = leverage?.let { it.leverageScore.coerceIn(-LEVERAGE_CAP, LEVERAGE_CAP) * settings.ownershipLeverageWeight } ?: 0.0,
        )
    }

    fun grade(score: Double): String = when {
        score >= 88 -> "A+"
        score >= 82 -> "A"
        score >= 78 -> "A-"
        score >= 74 -> "B+"
        score >= 70 -> "B"
        score >= 66 -> "B-"
        score >= 60 -> "C"
        score >= 52 -> "D"
        else -> "F"
    }

    fun tier(score: Double): Tier = when {
        score >= 76 -> Tier.STRONG
        score >= 68 -> Tier.ACCEPTABLE
        score >= 60 -> Tier.RISKY
        else -> Tier.AVOID
    }

    /** Colour tier for a raw win probability (schedule grid). */
    fun probabilityTier(p: Double): Tier = when {
        p > 0.82 -> Tier.STRONG
        p >= 0.75 -> Tier.ACCEPTABLE
        p >= 0.68 -> Tier.RISKY
        else -> Tier.AVOID
    }

    fun futureCostLabel(opportunityCost: Double): String = when {
        opportunityCost >= 12 -> "High"
        opportunityCost >= 5 -> "Medium"
        else -> "Low"
    }
}

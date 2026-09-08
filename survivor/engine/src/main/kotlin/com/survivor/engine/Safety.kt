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
    val thresholdPenalty: Double,
    val leverageAdjustment: Double,
) {
    val matchupRisk: Double get() = roadPenalty + divisionalPenalty + restPenalty + travelPenalty + qbPenalty
    val total: Double
        get() = (base - matchupRisk + marketAdjustment - futureCost - scarcityPenalty - thresholdPenalty + leverageAdjustment)
            .coerceIn(0.0, 100.0)
}

/** Pool-equity proxy when an ownership estimate exists. See docs/MODEL.md "Leverage". */
data class Leverage(val pickShare: Double, val expectedEquity: Double, val leverageScore: Double)

object Safety {

    fun leverage(p: Double, pickShare: Double?, fieldAverageWinProbability: Double): Leverage? {
        if (pickShare == null) return null
        val share = pickShare.coerceIn(0.0, 1.0)
        // Fraction of the pool still alive after this week if we advance: everyone on our team plus the
        // share of everyone else that wins their own pick. Equity = our advance probability / that.
        val survivingShare = share + (1 - share) * fieldAverageWinProbability
        val equity = p / survivingShare
        return Leverage(share, equity, (equity - 1.0) * 100.0)
    }

    fun components(
        estimate: ProbabilityEstimate,
        situation: Situation,
        adjustment: Adjustment?,
        opportunityCost: Double,
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
            thresholdPenalty = shortfall * if (strikesUsed >= 1) 1.5 else 0.5,
            leverageAdjustment = leverage?.let { it.leverageScore * settings.ownershipLeverageWeight } ?: 0.0,
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

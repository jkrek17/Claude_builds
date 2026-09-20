package com.survivor.app.ui

import com.survivor.app.ui.components.Fmt
import com.survivor.engine.Confidence
import com.survivor.engine.Market
import java.util.Locale

/** How strong a bet's EV is, for the Bets screen's chip coloring. Thresholds match [ModelSettings]'s
 *  defaults (1%/3%) but the tiering itself is independent of the user's own thresholds - it just says
 *  how good a shown edge is, not whether it clears the bar to be shown at all. */
enum class EvTier { STRONG, MODERATE, LOW }

/** Pure, Compose-free formatting helpers for the Bets screen so they're unit-testable without Android. */
object BetFormat {
    /** e.g. "KC -3.5" (SPREAD), "DEN ML" (MONEYLINE), "Over 44.5" (TOTAL). */
    fun marketLabel(market: Market, side: String, point: Double?): String = when (market) {
        Market.MONEYLINE -> "$side ML"
        Market.SPREAD -> "$side ${Fmt.spread(point)}"
        Market.TOTAL -> "${if (side.equals("OVER", ignoreCase = true)) "Over" else "Under"} ${point?.let { Fmt.num(it) } ?: "—"}"
    }

    /** Green (>=3% EV) / yellow (1-3%) / low (<1%) tier for the EV chip. */
    fun evTier(ev: Double): EvTier = when {
        ev >= 0.03 -> EvTier.STRONG
        ev >= 0.01 -> EvTier.MODERATE
        else -> EvTier.LOW
    }

    /** e.g. "moved +1.0 pt" (SPREAD/TOTAL points) or "price moved +2.3 pp in favour" / "price moved -1.0 pp
     *  against" (MONEYLINE, a no-vig win-probability move); "no history" when [points] is null - see
     *  [com.survivor.engine.SideAssessment.lineMovePoints]. */
    fun movedLabel(market: Market, points: Double?): String {
        if (points == null) return "no history"
        return if (market == Market.MONEYLINE) {
            String.format(Locale.US, "price moved %+.1f pp %s", points, if (points >= 0) "in favour" else "against")
        } else {
            String.format(Locale.US, "moved %+.1f pt", points)
        }
    }

    /** "+2.2 bps" / "-0.8 bps" for [com.survivor.engine.SideAssessment.expectedGrowthBps] - the
     *  Kelly-scaled expected growth rate the new Bet Score ranks on, not raw EV. */
    fun growthBps(bps: Double): String = if (kotlin.math.abs(bps) < 0.05) "0.0 bps" else String.format(Locale.US, "%+.1f bps", bps)

    /** Chip label for [Confidence] - how many standard errors of the fair price the edge clears. */
    fun confidenceLabel(c: Confidence): String = when (c) {
        Confidence.NONE -> "No confidence"
        Confidence.LOW -> "Low confidence"
        Confidence.MEDIUM -> "Medium confidence"
        Confidence.HIGH -> "High confidence"
    }

    /** "✓ Pinnacle" / "✗ Pinnacle" / "— Pinnacle" for [com.survivor.engine.SideAssessment.sharpAgrees];
     *  [label] should be the configured sharp book's display name (e.g. from `ModelSettings.sharpBooks`). */
    fun sharpMark(agrees: Boolean?, label: String = "Pinnacle"): String = when (agrees) {
        true -> "✓ $label"
        false -> "✗ $label"
        null -> "— $label"
    }
}

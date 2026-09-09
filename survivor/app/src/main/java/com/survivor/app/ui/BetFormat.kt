package com.survivor.app.ui

import com.survivor.app.ui.components.Fmt
import com.survivor.engine.Market

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

    /** e.g. "moved +1.0" (SPREAD/TOTAL points) or "moved +2.3%" (MONEYLINE, a no-vig probability move);
     *  "no history" when [points] is null - see [com.survivor.engine.SideAssessment.lineMovePoints]. */
    fun movedLabel(market: Market, points: Double?): String {
        if (points == null) return "no history"
        val sign = if (points >= 0) "+" else ""
        val suffix = if (market == Market.MONEYLINE) "%" else ""
        return "moved $sign${Fmt.num(points)}$suffix"
    }
}

package com.survivor.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/** Builds the plain-language decision explanation the dashboard shows under the recommendation. */
object Explain {

    fun pct(p: Double): String = "${(p * 100).roundToInt()}%"
    fun pct1(p: Double): String = String.format(java.util.Locale.US, "%.1f%%", p * 100)
    fun spread(s: Double?): String = when {
        s == null -> "no line"
        s == 0.0 -> "pick'em"
        s < 0 -> "${fmt(s)}"
        else -> "+${fmt(s)}"
    }
    private fun fmt(s: Double): String = if (s == s.roundToInt().toDouble()) s.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", s)
    fun moneyline(ml: Int?): String = ml?.let { if (it > 0) "+$it" else "$it" } ?: "—"

    fun venue(e: TeamWeekEvaluation): String = when {
        e.situation.neutral -> "vs ${e.opponent.abbr} (neutral)"
        e.situation.isHome -> "vs ${e.opponent.abbr}"
        else -> "@ ${e.opponent.abbr}"
    }

    fun build(
        rec: TeamWeekEvaluation,
        alternatives: List<TeamWeekEvaluation>,
        ranked: List<TeamWeekEvaluation>,
        unconstrainedRoute: Route,
        route: Route,
        strikesUsed: Int,
        settings: ModelSettings,
    ): Explanation {
        val t = rec.team.abbr
        val opp = rec.opponent.abbr
        val sb = StringBuilder()

        // 1. Why is this team safe?
        val favoredBy = rec.estimate.teamSpread?.let { -it }
        sb.append(t)
        sb.append(
            when {
                favoredBy == null -> " has a ${pct(rec.probability)} estimated win probability ${venue(rec)}"
                favoredBy > 0 -> " is a ${fmt(favoredBy)}-point ${if (rec.situation.isHome) "home" else "road"} favorite ${venue(rec)} (${pct(rec.probability)} to win)"
                favoredBy == 0.0 -> " is a pick'em ${venue(rec)} (${pct(rec.probability)} to win)"
                else -> " is a ${fmt(-favoredBy)}-point underdog ${venue(rec)} (${pct(rec.probability)} to win)"
            },
        )
        if (rec.estimate.hasMoneyline) sb.append(", moneyline ${moneyline(rec.estimate.teamMoneyline)} with the vig removed")
        sb.append(". Source: ${rec.estimate.source.label}.")
        val risks = mutableListOf<String>()
        if (rec.situation.divisional) risks += "divisional game"
        if (!rec.situation.isHome && !rec.situation.neutral) risks += "on the road"
        if (rec.situation.restAdvantageDays <= -3) risks += "${-rec.situation.restAdvantageDays} fewer rest days than $opp"
        if (rec.situation.timeZonesCrossed >= 2) risks += "crosses ${rec.situation.timeZonesCrossed} time zones"
        if (rec.adjustment?.qbPoints != null && rec.adjustment.qbPoints != 0.0) risks += "QB uncertainty flagged"
        if (rec.estimate.adjustmentPoints != 0.0) risks += "manual adjustment of ${fmt(rec.estimate.adjustmentPoints)} points applied"
        if (risks.isNotEmpty()) sb.append(" Risk notes: ${risks.joinToString(", ")}.")
        val whySafe = sb.toString()

        // 2. Why use it now?  3. What do we give up?
        val fv = rec.futureValue
        val best = fv.best
        val whyNow = buildString {
            val safest = ranked.maxByOrNull { it.probability }
            if (safest != null && safest.team != rec.team) {
                append("${safest.team.abbr} is safer this week (${pct(safest.probability)}) but preserving ${safest.team.abbr} is worth more later. ")
            }
            when {
                best == null -> append("$t has no remaining games after this week, so there is nothing to save it for.")
                best.probability <= rec.probability + 0.02 -> append("This is $t's best remaining spot: its next-best game is ${pct(best.probability)} in Week ${best.week} ${if (best.isHome) "vs" else "@"} ${best.opponent.abbr}.")
                else -> append("$t does have a better spot later (Week ${best.week}, ${pct(best.probability)}), but the optimizer can cover Week ${best.week} with another team, so the cost of using $t now is only ${fmt(rec.opportunityCost)}% of future path value.")
            }
            val optTeam = unconstrainedRoute.team(rec.week)
            if (optTeam != null && optTeam != rec.team) {
                val optP = ranked.firstOrNull { it.team == optTeam }?.probability
                append(" The season optimizer's unconstrained path would use ${optTeam.abbr}${optP?.let { " (${pct(it)})" } ?: ""} this week and save $t; taking $t now instead lowers modeled season survival by ${String.format(java.util.Locale.US, "%.1f", rec.seasonPathLoss)}% relative (${pct1(route.survival)} vs ${pct1(unconstrainedRoute.survival)} discounted), which the Safety Score treats as a fair trade for the extra safety this week.")
            } else append(" The full-season optimizer also uses $t this week.")
            if (strikesUsed >= 1) append(" You have a strike, so the model is weighting this week's safety much more heavily than future value.")
        }
        val giveUp = buildString {
            if (best == null) append("Nothing: $t has no future games with data.")
            else {
                append("Using $t now removes its Week ${best.week} ${if (best.isHome) "home" else "road"} game vs ${best.opponent.abbr} (${pct(best.probability)})")
                fv.secondBest?.let { append(" and its Week ${it.week} game vs ${it.opponent.abbr} (${pct(it.probability)})") }
                append(". $t has ${fv.countAbove(0.75)} future games above 75% and ${fv.premiumSpots} above 80%. ")
                append("Opportunity cost: ${fmt(rec.opportunityCost)}% (${Safety.futureCostLabel(rec.opportunityCost).lowercase()}).")
            }
        }
        val warning = if (best != null && best.probability >= rec.probability + 0.05)
            "$t has a stronger spot in Week ${best.week} (${pct(best.probability)} vs ${pct(rec.probability)} now). Consider whether another team could cover this week instead."
        else null

        // 4 + 5. Alternatives and why they rank lower.
        val alts = alternatives.map { a ->
            val reason = buildString {
                append("${a.team.abbr} ${venue(a)}, ${pct(a.probability)} (${spread(a.estimate.teamSpread)})")
                append(". Safety ${a.safetyScore.roundToInt()} vs ${rec.safetyScore.roundToInt()}: ")
                val reasons = mutableListOf<String>()
                val dp = a.probability - rec.probability
                if (dp <= -0.02) reasons += "${pct(abs(dp))} less likely to win this week"
                if (a.opportunityCost > rec.opportunityCost + 2) reasons += "higher future cost (${fmt(a.opportunityCost)}% vs ${fmt(rec.opportunityCost)}%)"
                if (a.components.matchupRisk > rec.components.matchupRisk + 1) reasons += "more matchup risk (${fmt(a.components.matchupRisk)} pts of penalties)"
                if (a.components.marketAdjustment < rec.components.marketAdjustment) reasons += "weaker market backing (${a.estimate.source.label.lowercase()})"
                if (a.components.scarcityPenalty > rec.components.scarcityPenalty) reasons += "${a.futureValue.premiumSpots} premium future spots to protect"
                if (a.seasonPathLoss > rec.seasonPathLoss + 1) reasons += "locking it now costs ${String.format(java.util.Locale.US, "%.1f", a.seasonPathLoss)}% of season survival vs ${String.format(java.util.Locale.US, "%.1f", rec.seasonPathLoss)}%"
                if (a.components.thresholdPenalty > 0) reasons += "below the ${pct(settings.minimumAcceptableWinProbability)} minimum"
                if (a.components.leverageAdjustment < rec.components.leverageAdjustment - 0.5) reasons += "more heavily owned"
                if (reasons.isEmpty()) reasons += "nearly identical; ${rec.team.abbr} edges it on the combined score"
                append(reasons.joinToString("; "))
                a.futureValue.best?.let { b -> if (b.probability > a.probability + 0.03) append(". Best future spot: Week ${b.week} vs ${b.opponent.abbr} (${pct(b.probability)})") }
                append(".")
            }
            a to reason
        }

        val headline = "Recommended: $t. ${pct(rec.probability)} estimated win probability, Safety ${rec.safetyScore.roundToInt()} (${rec.grade})."
        return Explanation(headline, whySafe, whyNow, giveUp, warning, alts)
    }
}

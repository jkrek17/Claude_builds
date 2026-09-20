package com.survivor.app.ui

import com.survivor.app.ui.components.Fmt
import com.survivor.engine.TeaserLeg
import com.survivor.engine.TeaserWindow
import java.util.Locale

/** Pure, Compose-free formatting helpers for the Teasers screen so they're unit-testable without Android. */
object TeaserFormat {
    /** e.g. "SEA -8 -> -2" for a favorite leg, "NE +2 -> +8" for an underdog leg. */
    fun legLine(leg: TeaserLeg): String =
        "${leg.team.abbr} ${signed(leg.originalPoint)} -> ${signed(leg.teasedPoint)}"

    /** "Favorite" / "Underdog" - the plain-language name for a [TeaserWindow], for the UI. */
    fun windowLabel(window: TeaserWindow): String = when (window) {
        TeaserWindow.FAV -> "Favorite"
        TeaserWindow.DOG -> "Underdog"
    }

    private fun signed(v: Double): String {
        val n = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
        return (if (v > 0) "+" else "") + n
    }

    /** e.g. "71.6% leg rate" for the leg-level list. */
    fun legRateLine(leg: TeaserLeg): String = "${Fmt.pct1(leg.legRate)} leg rate" + if (leg.highTotal) " · high total" else ""
}

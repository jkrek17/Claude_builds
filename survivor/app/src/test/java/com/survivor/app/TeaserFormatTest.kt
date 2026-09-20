package com.survivor.app

import com.survivor.app.ui.TeaserFormat
import com.survivor.engine.Team
import com.survivor.engine.TeaserLeg
import com.survivor.engine.TeaserWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class TeaserFormatTest {
    private fun leg(team: Team, original: Double, teased: Double, window: TeaserWindow, rate: Double = 0.716, highTotal: Boolean = false) =
        TeaserLeg(gameId = "g1", team = team, teamIsHome = true, originalPoint = original, teasedPoint = teased, window = window, legRate = rate, highTotal = highTotal, kickoffEpochMs = 1L, rationale = "")

    @Test fun `formats a favorite leg's line move`() {
        assertEquals("SEA -8 -> -2", TeaserFormat.legLine(leg(Team.SEA, -8.0, -2.0, TeaserWindow.FAV)))
    }

    @Test fun `formats an underdog leg's line move`() {
        assertEquals("NE +2 -> +8", TeaserFormat.legLine(leg(Team.NE, 2.0, 8.0, TeaserWindow.DOG)))
    }

    @Test fun `labels windows in plain language`() {
        assertEquals("Favorite", TeaserFormat.windowLabel(TeaserWindow.FAV))
        assertEquals("Underdog", TeaserFormat.windowLabel(TeaserWindow.DOG))
    }

    @Test fun `flags a high total leg in the leg-rate line`() {
        assertEquals("71.6% leg rate", TeaserFormat.legRateLine(leg(Team.SEA, -8.0, -2.0, TeaserWindow.FAV)))
        assertEquals("71.6% leg rate · high total", TeaserFormat.legRateLine(leg(Team.SEA, -8.0, -2.0, TeaserWindow.FAV, highTotal = true)))
    }
}

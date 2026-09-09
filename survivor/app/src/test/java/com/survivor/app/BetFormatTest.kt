package com.survivor.app

import com.survivor.app.ui.BetFormat
import com.survivor.app.ui.EvTier
import com.survivor.engine.Market
import kotlin.test.Test
import kotlin.test.assertEquals

class BetFormatTest {
    @Test fun `formats market labels the way the Bets screen shows them`() {
        assertEquals("KC ML", BetFormat.marketLabel(Market.MONEYLINE, "KC", null))
        assertEquals("KC -3.5", BetFormat.marketLabel(Market.SPREAD, "KC", -3.5))
        assertEquals("DEN +3.5", BetFormat.marketLabel(Market.SPREAD, "DEN", 3.5))
        assertEquals("Over 44.5", BetFormat.marketLabel(Market.TOTAL, "OVER", 44.5))
        assertEquals("Under 44.5", BetFormat.marketLabel(Market.TOTAL, "under", 44.5))
    }

    @Test fun `tiers EV the way the EV chip is colored - green at 3%, yellow at 1-3%, low below`() {
        assertEquals(EvTier.STRONG, BetFormat.evTier(0.03))
        assertEquals(EvTier.STRONG, BetFormat.evTier(0.05))
        assertEquals(EvTier.MODERATE, BetFormat.evTier(0.01))
        assertEquals(EvTier.MODERATE, BetFormat.evTier(0.0299))
        assertEquals(EvTier.LOW, BetFormat.evTier(0.0))
        assertEquals(EvTier.LOW, BetFormat.evTier(-0.02))
    }

    @Test fun `formats line movement for the graded board's small movement line`() {
        assertEquals("moved +1", BetFormat.movedLabel(Market.SPREAD, 1.0))
        assertEquals("moved -0.5", BetFormat.movedLabel(Market.TOTAL, -0.5))
        assertEquals("moved +2.3%", BetFormat.movedLabel(Market.MONEYLINE, 2.3))
        assertEquals("moved -1%", BetFormat.movedLabel(Market.MONEYLINE, -1.0))
        assertEquals("no history", BetFormat.movedLabel(Market.SPREAD, null))
    }
}

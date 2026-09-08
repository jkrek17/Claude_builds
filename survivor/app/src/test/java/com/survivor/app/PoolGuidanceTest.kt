package com.survivor.app

import com.survivor.app.ui.PoolGuidance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoolGuidanceTest {
    @Test fun `preset labels and selection`() {
        assertEquals("10", PoolGuidance.presetLabel(10))
        assertEquals("250+", PoolGuidance.presetLabel(250))
        assertEquals(50, PoolGuidance.selectedPreset(50))
        assertEquals(250, PoolGuidance.selectedPreset(400))
        assertNull(PoolGuidance.selectedPreset(37))
    }

    @Test fun `guidance text names the expected pool-end week`() {
        val text = PoolGuidance.text(entries = 50, expectedPoolEndWeek = 9.4)
        assertEquals(
            "With 50 entries the model expects the last other entry to fall around Week 9, so it optimizes for balancing safety now against saving strong teams for the second half.",
            text,
        )
    }

    @Test fun `guidance text handles a field that never fully clears and no data yet`() {
        val fullSeason = PoolGuidance.text(entries = 500, expectedPoolEndWeek = 19.0)
        assertEquals(
            "With 500 entries the model expects the last other entry to fall after Week 18, so it optimizes for surviving deep into the season, since a pool this size rarely finishes early.",
            fullSeason,
        )
        val noData = PoolGuidance.text(entries = 1, expectedPoolEndWeek = null)
        assertEquals(
            "With 1 entry the model expects the last other entry to fall partway through the season, so it optimizes for protecting your best teams for the next few weeks, since a pool this size usually resolves early.",
            noData,
        )
    }
}

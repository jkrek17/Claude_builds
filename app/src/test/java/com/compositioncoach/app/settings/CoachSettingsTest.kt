package com.compositioncoach.app.settings

import com.compositioncoach.composition.model.GuidanceLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class CoachSettingsTest {

    @Test
    fun `default settings match the documented out-of-the-box behavior`() {
        val defaults = CoachSettings()
        assertEquals(true, defaults.guidanceEnabled)
        assertEquals(true, defaults.showScore)
        assertEquals(false, defaults.showThirdsGrid)
        assertEquals(GuidanceLevel.BALANCED, defaults.guidanceLevel)
        assertEquals(true, defaults.poseDetectionEnabled)
        assertEquals(false, defaults.batterySaver)
        assertEquals(false, defaults.debugMode)
    }

    @Test
    fun `analysisIntervalMs is the default interval unless battery saver is on`() {
        assertEquals(CoachSettings.DEFAULT_INTERVAL_MS, CoachSettings().analysisIntervalMs)
        assertEquals(CoachSettings.BATTERY_SAVER_INTERVAL_MS, CoachSettings(batterySaver = true).analysisIntervalMs)
    }

    @Test
    fun `GuidanceLevelCodec round-trips every level`() {
        GuidanceLevel.entries.forEach { level ->
            assertEquals(level, GuidanceLevelCodec.decode(GuidanceLevelCodec.encode(level)))
        }
    }

    @Test
    fun `GuidanceLevelCodec falls back to BALANCED for missing or unrecognized values`() {
        assertEquals(GuidanceLevel.BALANCED, GuidanceLevelCodec.decode(null))
        assertEquals(GuidanceLevel.BALANCED, GuidanceLevelCodec.decode("NOT_A_REAL_LEVEL"))
        assertEquals(GuidanceLevel.BALANCED, GuidanceLevelCodec.decode(""))
    }
}

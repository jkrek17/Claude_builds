package com.compositioncoach.app.settings

import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent
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
        assertEquals(SceneIntent.AUTO, defaults.sceneIntent)
        assertEquals(true, defaults.detectObjectsEnabled)
        assertEquals(true, defaults.subjectMaskEnabled)
        assertEquals(false, defaults.onboardingSeen)
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

    @Test
    fun `SceneIntentCodec round-trips every intent`() {
        SceneIntent.entries.forEach { intent ->
            assertEquals(intent, SceneIntentCodec.decode(SceneIntentCodec.encode(intent)))
        }
    }

    @Test
    fun `SceneIntentCodec falls back to AUTO for missing, unrecognized or legacy values`() {
        assertEquals(SceneIntent.AUTO, SceneIntentCodec.decode(null))
        assertEquals(SceneIntent.AUTO, SceneIntentCodec.decode("NOT_A_REAL_INTENT"))
        assertEquals(SceneIntent.AUTO, SceneIntentCodec.decode(""))
    }

    // --- Detection settings round-trip (detect_objects / subject_mask keys) -----------------------------
    // SettingsRepository itself needs a real DataStore-backed Context, but its whole job is to persist a
    // CoachSettings value and read it back verbatim (see SettingsRepository.update/toCoachSettings) — the
    // property this test actually verifies, on the plain JVM, via `copy()` standing in for a write+read.

    @Test
    fun `detectObjectsEnabled round-trips through a copy like every other boolean setting`() {
        val defaultOn = CoachSettings()
        assertEquals(true, defaultOn.detectObjectsEnabled)
        val toggledOff = defaultOn.copy(detectObjectsEnabled = false)
        assertEquals(false, toggledOff.detectObjectsEnabled)
        assertEquals(true, toggledOff.copy(detectObjectsEnabled = true).detectObjectsEnabled)
    }

    @Test
    fun `subjectMaskEnabled round-trips independently of batterySaver`() {
        val toggledOff = CoachSettings(subjectMaskEnabled = false)
        assertEquals(false, toggledOff.subjectMaskEnabled)
        assertEquals(false, toggledOff.batterySaver)
        assertEquals(true, toggledOff.copy(subjectMaskEnabled = true).subjectMaskEnabled)
    }

    @Test
    fun `effectiveSubjectMaskEnabled is forced off by battery saver regardless of the user's own preference`() {
        assertEquals(true, CoachSettings(subjectMaskEnabled = true, batterySaver = false).effectiveSubjectMaskEnabled)
        assertEquals(false, CoachSettings(subjectMaskEnabled = true, batterySaver = true).effectiveSubjectMaskEnabled)
        assertEquals(false, CoachSettings(subjectMaskEnabled = false, batterySaver = false).effectiveSubjectMaskEnabled)
        assertEquals(false, CoachSettings(subjectMaskEnabled = false, batterySaver = true).effectiveSubjectMaskEnabled)
    }

    @Test
    fun `subjectMaskSubtitle explains the battery saver override only when it applies`() {
        assertEquals(
            "Better background and separation advice for people; uses more battery",
            CoachSettings(batterySaver = false).subjectMaskSubtitle(),
        )
        assertEquals(
            "Better background and separation advice for people; off while battery saver is on",
            CoachSettings(batterySaver = true).subjectMaskSubtitle(),
        )
    }

    @Test
    fun `onboardingSeen round-trips through a copy`() {
        val seen = CoachSettings().copy(onboardingSeen = true)
        assertEquals(true, seen.onboardingSeen)
        assertEquals(false, seen.copy(onboardingSeen = false).onboardingSeen)
    }
}

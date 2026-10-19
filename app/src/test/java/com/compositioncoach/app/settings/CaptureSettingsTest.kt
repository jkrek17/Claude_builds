package com.compositioncoach.app.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture-quality settings keys (`capture_enhancement`, `burst_sharpest`, `auto_level`) and their round trip. */
class CaptureSettingsTest {

    @Test
    fun `defaults are AUTO enhancement, burst on, auto-level on`() {
        val defaults = emptyPreferences().toCoachSettings()
        assertEquals(CaptureEnhancement.AUTO, defaults.captureEnhancement)
        assertTrue(defaults.burstSharpest)
        assertTrue(defaults.autoLevel)
        assertEquals(CoachSettings(), defaults)
    }

    @Test
    fun `every setting survives a write and read through DataStore preferences`() {
        val custom = CoachSettings(
            guidanceEnabled = false,
            showScore = false,
            showThirdsGrid = true,
            guidanceLevel = GuidanceLevel.COACH,
            poseDetectionEnabled = false,
            batterySaver = true,
            debugMode = true,
            sceneIntent = SceneIntent.LANDSCAPE,
            detectObjectsEnabled = false,
            subjectMaskEnabled = false,
            onboardingSeen = true,
            captureEnhancement = CaptureEnhancement.NIGHT,
            burstSharpest = false,
            autoLevel = false,
        )
        val prefs = mutablePreferencesOf().apply { writeCoachSettings(custom) }
        assertEquals(custom, prefs.toCoachSettings())
        assertEquals("NIGHT", prefs[SettingsRepository.Keys.CAPTURE_ENHANCEMENT])
        assertEquals(false, prefs[SettingsRepository.Keys.BURST_SHARPEST])
        assertEquals(false, prefs[SettingsRepository.Keys.AUTO_LEVEL])
    }

    @Test
    fun `key names are the documented on-disk contract`() {
        assertEquals("capture_enhancement", SettingsRepository.Keys.CAPTURE_ENHANCEMENT.name)
        assertEquals("burst_sharpest", SettingsRepository.Keys.BURST_SHARPEST.name)
        assertEquals("auto_level", SettingsRepository.Keys.AUTO_LEVEL.name)
    }

    @Test
    fun `CaptureEnhancementCodec round-trips and falls back to AUTO`() {
        CaptureEnhancement.entries.forEach { assertEquals(it, CaptureEnhancementCodec.decode(CaptureEnhancementCodec.encode(it))) }
        assertEquals(CaptureEnhancement.AUTO, CaptureEnhancementCodec.decode(null))
        assertEquals(CaptureEnhancement.AUTO, CaptureEnhancementCodec.decode("BOKEH"))
    }

    @Test
    fun `burst only runs with no extension and no power saving`() {
        val on = CoachSettings(burstSharpest = true)
        assertTrue(on.burstSharpestEffective(CaptureEnhancement.OFF, conservingPower = false))
        assertFalse(on.burstSharpestEffective(CaptureEnhancement.AUTO, conservingPower = false))
        assertFalse(on.burstSharpestEffective(CaptureEnhancement.NIGHT, conservingPower = false))
        assertFalse(on.burstSharpestEffective(CaptureEnhancement.OFF, conservingPower = true))
        assertFalse(CoachSettings(burstSharpest = false).burstSharpestEffective(CaptureEnhancement.OFF, conservingPower = false))
    }
}

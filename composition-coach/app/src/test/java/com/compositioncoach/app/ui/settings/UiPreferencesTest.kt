package com.compositioncoach.app.ui.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.compositioncoach.app.outcome.ExperienceMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Easy is the default; Coach from either source wins; unknown values fall back; keys round-trip. */
class UiPreferencesTest {

    @Test
    fun `defaults are Easy with everything off`() {
        assertEquals(UiPreferences(ExperienceMode.EASY, proControls = false, guideByVibration = false, guideByVoice = false), mutablePreferencesOf().toUiPreferences())
    }

    @Test
    fun `stored keys decode`() {
        val prefs = mutablePreferencesOf(
            UiPreferencesStore.Keys.EXPERIENCE_MODE to "COACH",
            UiPreferencesStore.Keys.PRO_CONTROLS to true,
            UiPreferencesStore.Keys.GUIDE_BY_VOICE to true,
        )
        assertEquals(UiPreferences(ExperienceMode.COACH, proControls = true, guideByVibration = false, guideByVoice = true), prefs.toUiPreferences())
    }

    @Test
    fun `an unknown experience falls back to Easy`() {
        assertEquals(ExperienceMode.EASY, UiPreferences.decodeExperience("EXPERT"))
        assertEquals(ExperienceMode.EASY, UiPreferences.decodeExperience(null))
    }

    @Test
    fun `Coach from either the camera state or the preference`() {
        assertEquals(ExperienceMode.COACH, UiPreferences.effectiveExperience(ExperienceMode.COACH, ExperienceMode.EASY))
        assertEquals(ExperienceMode.COACH, UiPreferences.effectiveExperience(ExperienceMode.EASY, ExperienceMode.COACH))
        assertEquals(ExperienceMode.EASY, UiPreferences.effectiveExperience(ExperienceMode.EASY, ExperienceMode.EASY))
    }

    @Test
    fun `key names match the ones WAVE2 gives CoachSettings`() {
        assertEquals("experience_mode", UiPreferencesStore.Keys.EXPERIENCE_MODE.name)
        assertEquals("pro_controls", UiPreferencesStore.Keys.PRO_CONTROLS.name)
    }
}

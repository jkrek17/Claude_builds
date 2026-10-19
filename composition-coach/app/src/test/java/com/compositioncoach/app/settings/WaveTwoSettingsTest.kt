package com.compositioncoach.app.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.outcome.ReviewCardMode
import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.engine.Feedback
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.SceneIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wave 2 keys: Easy/Coach defaults, outcome (with the scene intent derived from it), timer, taste model, sound. */
class WaveTwoSettingsTest {

    @Test
    fun `defaults are the Easy experience`() {
        val d = emptyPreferences().toCoachSettings()
        assertEquals(CoachSettings(), d)
        assertEquals(ExperienceMode.EASY, d.experienceMode)
        assertFalse(d.proControls)
        assertEquals(Outcome.ANYTHING, d.outcome)
        assertEquals(SceneIntent.AUTO, d.sceneIntent)
        assertEquals(ReviewCardMode.ALWAYS, d.reviewCardMode)
        assertFalse(d.hapticGuidance)
        assertFalse(d.voiceGuidance)
        assertEquals(0, d.timerSeconds)
        assertEquals("", d.adaptiveWeights)
        assertEquals(AdaptiveWeights.NEUTRAL, d.adaptive)
        assertEquals(ShutterSoundMode.AUTO, d.shutterSound)
        assertTrue(d.autoShoot)
        assertTrue(d.autoLevel)
        assertTrue(d.burstSharpest)
        // Easy hides the number even though the stored preference is on.
        assertTrue(d.showScore)
        assertFalse(d.effectiveShowScore())
    }

    @Test
    fun `the stored score preference survives a trip through Easy`() {
        val coachOn = CoachSettings(experienceMode = ExperienceMode.COACH, showScore = true)
        assertTrue(coachOn.effectiveShowScore())
        val easy = coachOn.copy(experienceMode = ExperienceMode.EASY)
        assertFalse(easy.effectiveShowScore())
        assertTrue(easy.copy(experienceMode = ExperienceMode.COACH).effectiveShowScore())
        assertFalse(CoachSettings(experienceMode = ExperienceMode.COACH, showScore = false).effectiveShowScore())
        assertFalse(coachOn.effectiveShowScore(ExperienceMode.EASY))
    }

    @Test
    fun `every Wave 2 setting survives a write and read`() {
        val weights = AdaptiveWeights.NEUTRAL.apply(Feedback.IGNORED_AND_KEPT(MetricCategory.HORIZON))
        val custom = CoachSettings(
            experienceMode = ExperienceMode.COACH,
            proControls = true,
            outcome = Outcome.GROUP,
            reviewCardMode = ReviewCardMode.ONLY_WHEN_USEFUL,
            hapticGuidance = true,
            voiceGuidance = true,
            timerSeconds = 10,
            adaptiveWeights = weights.serialize(),
            shutterSound = ShutterSoundMode.ALWAYS,
            autoShoot = false,
        )
        val prefs = mutablePreferencesOf().apply { writeCoachSettings(custom) }
        val read = prefs.toCoachSettings()
        assertEquals(custom, read)
        assertEquals(SceneIntent.GROUP_PORTRAIT, read.sceneIntent)
        assertEquals(weights, read.adaptive)
        assertEquals("COACH", prefs[SettingsRepository.Keys.EXPERIENCE_MODE])
        assertEquals("GROUP", prefs[SettingsRepository.Keys.OUTCOME])
        assertEquals("GROUP_PORTRAIT", prefs[SettingsRepository.Keys.SCENE_INTENT])
        assertEquals(10, prefs[SettingsRepository.Keys.TIMER_SECONDS])
        assertEquals("ALWAYS", prefs[SettingsRepository.Keys.SHUTTER_SOUND])
    }

    @Test
    fun `key names are the documented on-disk contract`() {
        assertEquals("experience_mode", SettingsRepository.Keys.EXPERIENCE_MODE.name)
        assertEquals("pro_controls", SettingsRepository.Keys.PRO_CONTROLS.name)
        assertEquals("outcome", SettingsRepository.Keys.OUTCOME.name)
        assertEquals("review_card_mode", SettingsRepository.Keys.REVIEW_CARD_MODE.name)
        assertEquals("haptic_guidance", SettingsRepository.Keys.HAPTIC_GUIDANCE.name)
        assertEquals("voice_guidance", SettingsRepository.Keys.VOICE_GUIDANCE.name)
        assertEquals("timer_seconds", SettingsRepository.Keys.TIMER_SECONDS.name)
        assertEquals("adaptive_weights", SettingsRepository.Keys.ADAPTIVE_WEIGHTS.name)
        assertEquals("shutter_sound", SettingsRepository.Keys.SHUTTER_SOUND.name)
    }

    @Test
    fun `the scene intent is derived from the outcome and no longer user-set`() {
        // A caller passing a contradictory intent is overruled on the way through DataStore.
        val contradictory = CoachSettings(outcome = Outcome.PORTRAIT, sceneIntent = SceneIntent.LANDSCAPE)
        val read = mutablePreferencesOf().apply { writeCoachSettings(contradictory) }.toCoachSettings()
        assertEquals(Outcome.PORTRAIT, read.outcome)
        assertEquals(SceneIntent.PORTRAIT, read.sceneIntent)
        Outcome.entries.forEach { assertEquals(it.spec.sceneIntent, CoachSettings().withOutcome(it).sceneIntent) }
    }

    @Test
    fun `a Wave 1 scene intent migrates to the matching outcome`() {
        val legacy = mutablePreferencesOf(SettingsRepository.Keys.SCENE_INTENT to "PORTRAIT")
        assertEquals(Outcome.PORTRAIT, legacy.toCoachSettings().outcome)
        val group = mutablePreferencesOf(SettingsRepository.Keys.SCENE_INTENT to "GROUP_PORTRAIT")
        assertEquals(Outcome.GROUP, group.toCoachSettings().outcome)
        // An intent with no outcome of its own, or garbage, lands on Anything.
        assertEquals(Outcome.ANYTHING, mutablePreferencesOf(SettingsRepository.Keys.SCENE_INTENT to "ARCHITECTURE").toCoachSettings().outcome)
        assertEquals(Outcome.ANYTHING, mutablePreferencesOf(SettingsRepository.Keys.SCENE_INTENT to "???").toCoachSettings().outcome)
        // Once an outcome is stored it wins over the legacy key.
        val both = mutablePreferencesOf(SettingsRepository.Keys.SCENE_INTENT to "PORTRAIT", SettingsRepository.Keys.OUTCOME to "NIGHT")
        assertEquals(Outcome.NIGHT, both.toCoachSettings().outcome)
    }

    @Test
    fun `codecs fall back to the defaults for unknown values`() {
        val junk = mutablePreferencesOf(
            SettingsRepository.Keys.EXPERIENCE_MODE to "EXPERT",
            SettingsRepository.Keys.OUTCOME to "UNDERWATER",
            SettingsRepository.Keys.REVIEW_CARD_MODE to "",
            SettingsRepository.Keys.SHUTTER_SOUND to "NEVER",
            SettingsRepository.Keys.TIMER_SECONDS to -4,
            SettingsRepository.Keys.ADAPTIVE_WEIGHTS to "HORIZON=banana,,=,NOPE=1.2",
        )
        assertEquals(CoachSettings(), junk.toCoachSettings())
    }

    @Test
    fun `the timer only offers off, 3 and 10 seconds`() {
        assertEquals(0, TimerSecondsCodec.decode(null))
        assertEquals(0, TimerSecondsCodec.decode(0))
        assertEquals(0, TimerSecondsCodec.decode(-1))
        assertEquals(3, TimerSecondsCodec.decode(2))
        assertEquals(3, TimerSecondsCodec.decode(3))
        assertEquals(3, TimerSecondsCodec.decode(5))
        assertEquals(10, TimerSecondsCodec.decode(7))
        assertEquals(10, TimerSecondsCodec.decode(60))
        val prefs = mutablePreferencesOf().apply { writeCoachSettings(CoachSettings(timerSeconds = 4)) }
        assertEquals(3, prefs[SettingsRepository.Keys.TIMER_SECONDS])
    }

    @Test
    fun `adaptive weights are stored in their normalised form`() {
        val prefs = mutablePreferencesOf().apply { writeCoachSettings(CoachSettings(adaptiveWeights = "HORIZON=0.9000,HEADROOM=9")) }
        assertEquals("HORIZON=0.9,HEADROOM=1.4", prefs[SettingsRepository.Keys.ADAPTIVE_WEIGHTS])
        assertEquals(1.4f, prefs.toCoachSettings().adaptive.multiplier(MetricCategory.HEADROOM), 1e-6f)
    }

    @Test
    fun `legacy scene intents map onto outcomes`() {
        assertEquals(Outcome.ANYTHING, Outcome.forSceneIntent(SceneIntent.AUTO))
        assertEquals(Outcome.PORTRAIT, Outcome.forSceneIntent(SceneIntent.PORTRAIT))
        assertEquals(Outcome.GROUP, Outcome.forSceneIntent(SceneIntent.GROUP_PORTRAIT))
        assertEquals(Outcome.LANDSCAPE, Outcome.forSceneIntent(SceneIntent.LANDSCAPE))
        assertEquals(Outcome.FOOD, Outcome.forSceneIntent(SceneIntent.OBJECT))
        assertEquals(Outcome.SELFIE, Outcome.forSceneIntent(SceneIntent.SELFIE))
        assertEquals(Outcome.ANYTHING, Outcome.forSceneIntent(SceneIntent.ARCHITECTURE))
    }
}

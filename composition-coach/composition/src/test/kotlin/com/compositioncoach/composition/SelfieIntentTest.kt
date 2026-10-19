package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.fixtures.SyntheticFrames.objectAt
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfieIntentTest {
    private val engine = CompositionEngine.default()

    @Test
    fun `selfie is coached exactly like portrait and reported as selfie`() {
        val frames = listOf(
            frameWith(faces = listOf(face(cx = 0.5f, cy = 0.45f, size = 0.3f)), isFrontCamera = true),
            frameWith(faces = listOf(face(cx = 0.8f, cy = 0.6f, size = 0.06f)), objects = listOf(objectAt(0.3f, 0.6f, 0.3f)), isFrontCamera = true),
            frameWith(isFrontCamera = true),
        )
        for (frame in frames) {
            val portrait = engine.evaluate(frame, GuidanceLevel.COACH, SceneIntent.PORTRAIT)
            val selfie = engine.evaluate(frame, GuidanceLevel.COACH, SceneIntent.SELFIE)
            assertEquals(SceneIntent.SELFIE, selfie.intent)
            assertEquals(SceneType.PORTRAIT, selfie.scene.type)
            assertEquals(portrait.rawScore, selfie.rawScore, 0f)
            assertEquals(portrait.awaitingSubject, selfie.awaitingSubject)
            assertEquals(portrait.recommendations.map { it.id }, selfie.recommendations.map { it.id })
            assertEquals(portrait.primarySubject?.kind, selfie.primarySubject?.kind)
        }
        assertTrue(engine.evaluate(frameWith(), intent = SceneIntent.SELFIE).awaitingSubject)
        assertEquals(SceneType.PORTRAIT, SceneIntent.SELFIE.forcedSceneType())
        assertEquals(SceneIntent.PORTRAIT, SceneIntent.SELFIE.rulesIntent())
        assertEquals(SceneIntent.LANDSCAPE, SceneIntent.LANDSCAPE.rulesIntent())
    }
}

package com.compositioncoach.composition

import com.compositioncoach.composition.engine.SceneClassifier
import com.compositioncoach.composition.fixtures.SyntheticFrames
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneClassifierTest {

    @Test
    fun `single face is a portrait`() {
        val frame = frameWith(faces = listOf(face(0.5f, 0.4f, 0.2f)))
        val scene = SceneClassifier.classify(frame)
        assertEquals(SceneType.PORTRAIT, scene.type)
        assertFalse(scene.isCloseUpPortrait)
    }

    @Test
    fun `large face is a close-up portrait`() {
        val frame = frameWith(faces = listOf(face(0.5f, 0.45f, 0.5f)))
        val scene = SceneClassifier.classify(frame)
        assertEquals(SceneType.PORTRAIT, scene.type)
        assertTrue(scene.isCloseUpPortrait)
    }

    @Test
    fun `two reasonable faces are a group portrait`() {
        val frame = frameWith(
            faces = listOf(face(0.3f, 0.4f, 0.12f, id = 0), face(0.7f, 0.4f, 0.12f, id = 1)),
        )
        val scene = SceneClassifier.classify(frame)
        assertEquals(SceneType.GROUP_PORTRAIT, scene.type)
    }

    @Test
    fun `strong left-right symmetry with no faces reads as architecture`() {
        val frame = frameWith(stats = SyntheticFrames.symmetricStats(horizontalSymmetry = 0.9f))
        val scene = SceneClassifier.classify(frame)
        assertEquals(SceneType.ARCHITECTURE, scene.type)
        assertTrue(scene.isSymmetricScene)
    }

    @Test
    fun `reported horizon angle with no faces reads as landscape`() {
        val frame = frameWith(stats = SyntheticFrames.statsWithHorizonAngle(2f))
        val scene = SceneClassifier.classify(frame)
        assertEquals(SceneType.LANDSCAPE, scene.type)
        assertTrue(scene.hasHorizon)
    }

    @Test
    fun `featureless frame with no faces is general`() {
        val frame = frameWith(stats = com.compositioncoach.composition.model.ImageStatistics.flat())
        val scene = SceneClassifier.classify(frame)
        assertEquals(SceneType.GENERAL, scene.type)
    }
}

package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionEngineTest {
    private val engine = CompositionEngine.default()

    @Test
    fun `engine never throws on a completely empty frame`() {
        val frame = FrameAnalysis(timestampNanos = 0L, frameWidth = 1080, frameHeight = 1920)
        val result = engine.evaluate(frame)
        assertTrue(result.score in 0..100)
        assertFalse(result.isShootReady)
    }

    @Test
    fun `two-person frame classifies as group portrait with no looking-room recommendation`() {
        val frame = frameWith(
            faces = listOf(
                face(0.3f, 0.4f, 0.12f, id = 0),
                face(0.7f, 0.4f, 0.12f, id = 1),
            ),
        )
        val result = engine.evaluate(frame)
        assertEquals(SceneType.GROUP_PORTRAIT, result.scene.type)
        assertTrue(result.recommendations.none { it.category == MetricCategory.LOOKING_ROOM })
    }

    @Test
    fun `engine result score is bounded and deterministic for the same frame`() {
        val frame = frameWith(faces = listOf(face(0.5f, 0.4f, 0.2f)))
        val a = engine.evaluate(frame)
        val b = engine.evaluate(frame)
        assertEquals(a.score, b.score)
    }
}

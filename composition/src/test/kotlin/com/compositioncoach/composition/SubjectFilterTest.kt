package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.engine.SubjectFilter
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectFilterTest {
    private val engine = CompositionEngine.default()

    @Test
    fun `a tiny background face is dropped`() {
        val frame = frameWith(faces = listOf(face(cx = 0.75f, cy = 0.15f, size = 0.05f)))
        assertTrue(SubjectFilter.dropIncidentalFaces(frame).faces.isEmpty())
    }

    @Test
    fun `a portrait-sized face is kept`() {
        val frame = frameWith(faces = listOf(face(cx = 0.5f, cy = 0.35f, size = 0.25f)))
        assertEquals(1, SubjectFilter.dropIncidentalFaces(frame).faces.size)
    }

    @Test
    fun `engine does not classify a scene with only a distant face as a portrait`() {
        val frame = frameWith(faces = listOf(face(cx = 0.75f, cy = 0.15f, size = 0.05f)))
        val result = engine.evaluate(frame)
        assertNotEquals(SceneType.PORTRAIT, result.scene.type)
        assertTrue(result.subjects.none { it.kind == SubjectKind.FACE })
        assertTrue(result.recommendations.none { it.id.startsWith("headroom") })
    }
}

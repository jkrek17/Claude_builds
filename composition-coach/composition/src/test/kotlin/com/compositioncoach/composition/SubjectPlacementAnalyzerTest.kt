package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.SubjectPlacementAnalyzer
import com.compositioncoach.composition.engine.SubjectResolver
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectPlacementAnalyzerTest {
    private val analyzer = SubjectPlacementAnalyzer()

    private fun contextFor(faceCx: Float, faceCy: Float, scene: SceneClassification): AnalysisContext {
        val frame = frameWith(faces = listOf(face(faceCx, faceCy, 0.15f)))
        val resolution = SubjectResolver.resolve(frame)
        return AnalysisContext(frame, scene, resolution.subjects, resolution.primary)
    }

    @Test
    fun `centred subject in a symmetric scene needs no advice`() {
        val scene = SceneClassification(SceneType.GENERAL, 1f, isSymmetricScene = true)
        val context = contextFor(0.5f, 0.4f, scene)
        val metric = analyzer.analyze(context)!!
        assertNull(metric.recommendation)
        assertTrue(metric.score > 0.9f)
    }

    @Test
    fun `subject already near centre and scene not portrait needs no advice`() {
        val scene = SceneClassification(SceneType.GENERAL, 1f, isSymmetricScene = false)
        val context = contextFor(0.51f, 0.4f, scene)
        val metric = analyzer.analyze(context)!!
        assertNull(metric.recommendation)
    }

    @Test
    fun `face on the upper-left third scores highly`() {
        val scene = SceneClassification(SceneType.GENERAL, 1f)
        val context = contextFor(0.34f, 0.35f, scene)
        val metric = analyzer.analyze(context)!!
        assertTrue("expected a high placement score, was ${metric.score}", metric.score >= 0.85f)
    }

    @Test
    fun `off-centre subject in a symmetric scene is pushed toward centre, not a third`() {
        val scene = SceneClassification(SceneType.GENERAL, 1f, isSymmetricScene = true)
        val context = contextFor(0.75f, 0.4f, scene)
        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals("placement.center", rec.id)
        assertEquals(Direction.RIGHT, rec.direction)
    }
}

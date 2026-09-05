package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.HeadroomAnalyzer
import com.compositioncoach.composition.engine.SubjectResolver
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadroomAnalyzerTest {
    private val analyzer = HeadroomAnalyzer()
    private val scene = SceneClassification(SceneType.PORTRAIT, 1f)

    private fun contextFor(faceCy: Float, size: Float): AnalysisContext {
        val frame = frameWith(faces = listOf(face(0.5f, faceCy, size)))
        val resolution = SubjectResolver.resolve(frame)
        return AnalysisContext(frame, scene, resolution.subjects, resolution.primary)
    }

    @Test
    fun `excessive headroom recommends lowering the camera`() {
        val context = contextFor(faceCy = 0.5f, size = 0.2f)
        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals(Direction.DOWN, rec.direction)
        assertEquals("Lower camera slightly", rec.instruction)
    }

    @Test
    fun `tight headroom recommends raising the camera`() {
        val context = contextFor(faceCy = 0.1f, size = 0.15f)
        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals(Direction.UP, rec.direction)
        assertEquals("Raise camera slightly", rec.instruction)
    }

    @Test
    fun `comfortable headroom needs no advice`() {
        // headroom (estimatedHeadTop) lands right in the ideal 3-9% band for this face size.
        val context = contextFor(faceCy = 0.22f, size = 0.2f)
        val metric = analyzer.analyze(context)!!
        assertTrue("expected no recommendation, got ${metric.recommendation}", metric.recommendation == null)
        assertTrue("expected a comfortable score, was ${metric.score}", metric.score > 0.9f)
    }
}

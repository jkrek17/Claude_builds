package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.EdgeTensionAnalyzer
import com.compositioncoach.composition.engine.SubjectResolver
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.SceneClassification
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeTensionAnalyzerTest {
    private val analyzer = EdgeTensionAnalyzer()
    private val scene = SceneClassification.UNKNOWN

    private fun contextFor(cx: Float, cy: Float, size: Float): AnalysisContext {
        val frame = frameWith(faces = listOf(face(cx, cy, size)))
        val resolution = SubjectResolver.resolve(frame)
        return AnalysisContext(frame, scene, resolution.subjects, resolution.primary)
    }

    @Test
    fun `face near the left edge recommends moving left`() {
        val metric = analyzer.analyze(contextFor(0.06f, 0.5f, 0.1f))!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals(Direction.LEFT, rec.direction)
        assertEquals("Move slightly left", rec.instruction)
    }

    @Test
    fun `face near the right edge recommends moving right`() {
        val metric = analyzer.analyze(contextFor(0.94f, 0.5f, 0.1f))!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals(Direction.RIGHT, rec.direction)
        assertEquals("Move slightly right", rec.instruction)
    }

    @Test
    fun `well-centred face needs no edge tension advice`() {
        val metric = analyzer.analyze(contextFor(0.5f, 0.5f, 0.1f))!!
        org.junit.Assert.assertNull(metric.recommendation)
    }
}

package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.SymmetryAnalyzer
import com.compositioncoach.composition.engine.SubjectResolver
import com.compositioncoach.composition.fixtures.SyntheticFrames
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.SceneClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SymmetryAnalyzerTest {
    private val analyzer = SymmetryAnalyzer()
    private val scene = SceneClassification.UNKNOWN

    @Test
    fun `strongly symmetric off-centre scene recommends centering the shot`() {
        val subjectFace = face(0.7f, 0.4f, 0.15f)
        val frame = frameWith(faces = listOf(subjectFace), stats = SyntheticFrames.symmetricStats(horizontalSymmetry = 0.9f))
        val resolution = SubjectResolver.resolve(frame)
        val context = AnalysisContext(frame, scene, resolution.subjects, resolution.primary)

        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals("Strong symmetry — center the shot", rec.title)
        assertEquals(Direction.RIGHT, rec.direction)
    }

    @Test
    fun `strongly symmetric centred scene needs no advice`() {
        val subjectFace = face(0.5f, 0.4f, 0.15f)
        val frame = frameWith(faces = listOf(subjectFace), stats = SyntheticFrames.symmetricStats(horizontalSymmetry = 0.9f))
        val resolution = SubjectResolver.resolve(frame)
        val context = AnalysisContext(frame, scene, resolution.subjects, resolution.primary)

        val metric = analyzer.analyze(context)!!
        assertNull(metric.recommendation)
    }

    @Test
    fun `low symmetry is not applicable`() {
        val frame = frameWith(stats = ImageStatistics.flat())
        val context = AnalysisContext(frame, scene, emptyList(), null)
        val metric = analyzer.analyze(context)!!
        assertFalse(metric.applicable)
    }
}

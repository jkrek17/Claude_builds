package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.BackgroundDistractionAnalyzer
import com.compositioncoach.composition.engine.SubjectResolver
import com.compositioncoach.composition.fixtures.SyntheticFrames
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.SceneClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundDistractionAnalyzerTest {
    private val analyzer = BackgroundDistractionAnalyzer()
    private val scene = SceneClassification.UNKNOWN

    @Test
    fun `high-edge vertical band above the head triggers a recommendation`() {
        val face = face(0.5f, 0.4f, 0.2f)
        val frame = frameWith(faces = listOf(face), stats = SyntheticFrames.withVerticalBandAbove(face))
        val resolution = SubjectResolver.resolve(frame)
        val context = AnalysisContext(frame, scene, resolution.subjects, resolution.primary)

        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals(MetricCategory.BACKGROUND_DISTRACTION, rec.category)
        assertTrue(rec.instruction.contains("Move slightly"))
    }

    @Test
    fun `clean uniform background needs no advice`() {
        val face = face(0.5f, 0.4f, 0.2f)
        val frame = frameWith(faces = listOf(face), stats = com.compositioncoach.composition.model.ImageStatistics.flat())
        val resolution = SubjectResolver.resolve(frame)
        val context = AnalysisContext(frame, scene, resolution.subjects, resolution.primary)

        val metric = analyzer.analyze(context)!!
        assertNull(metric.recommendation)
    }
}

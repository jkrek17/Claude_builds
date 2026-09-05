package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.HorizonAnalyzer
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HorizonAnalyzerTest {
    private val analyzer = HorizonAnalyzer()
    private val scene = SceneClassification.UNKNOWN

    @Test
    fun `tilted +5 degrees recommends rotating counter-clockwise`() {
        val frame = frameWith(rollDegrees = 5f)
        val context = AnalysisContext(frame, scene, emptyList(), null)
        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals(Direction.ROTATE_COUNTER_CLOCKWISE, rec.direction)
        assertEquals("Rotate slightly counter-clockwise", rec.instruction)
    }

    @Test
    fun `tilted -5 degrees recommends rotating clockwise`() {
        val frame = frameWith(rollDegrees = -5f)
        val context = AnalysisContext(frame, scene, emptyList(), null)
        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals(Direction.ROTATE_CLOCKWISE, rec.direction)
    }

    @Test
    fun `near-level horizon within dead zone needs no advice`() {
        val frame = frameWith(rollDegrees = 0.5f)
        val context = AnalysisContext(frame, scene, emptyList(), null)
        val metric = analyzer.analyze(context)!!
        assertNull(metric.recommendation)
        assertEquals(Severity.NONE, metric.severity)
    }

    @Test
    fun `unreliable orientation falls back to stats horizon estimate`() {
        val frame = frameWith(
            rollDegrees = 20f,
            orientationReliable = false,
            stats = com.compositioncoach.composition.fixtures.SyntheticFrames.statsWithHorizonAngle(4f),
        )
        val context = AnalysisContext(frame, scene, emptyList(), null)
        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals(Direction.ROTATE_COUNTER_CLOCKWISE, rec.direction)
    }
}

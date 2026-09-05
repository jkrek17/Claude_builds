package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.HorizonAnalyzer
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
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
    fun `unreliable orientation falls back to stats horizon estimate in a landscape`() {
        val frame = frameWith(
            rollDegrees = 20f,
            orientationReliable = false,
            stats = com.compositioncoach.composition.fixtures.SyntheticFrames.statsWithHorizonAngle(4f),
        )
        val landscape = SceneClassification(SceneType.LANDSCAPE, 0.8f, hasHorizon = true)
        val context = AnalysisContext(frame, landscape, emptyList(), null)
        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals(Direction.ROTATE_COUNTER_CLOCKWISE, rec.direction)
    }

    @Test
    fun `unreliable orientation with a visual line in a general scene gives no advice`() {
        // Phone pointing down at a table: the sensor is unreliable and the "horizon" is wood grain.
        val frame = frameWith(
            rollDegrees = 20f,
            orientationReliable = false,
            stats = com.compositioncoach.composition.fixtures.SyntheticFrames.statsWithHorizonAngle(4f),
        )
        val context = AnalysisContext(frame, scene, emptyList(), null)
        val metric = analyzer.analyze(context)!!
        assertEquals(false, metric.applicable)
        assertNull(metric.recommendation)
    }
}

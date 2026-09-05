package com.compositioncoach.composition

import com.compositioncoach.composition.engine.ScoreAggregator
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreAggregatorTest {
    private val scene = SceneClassification.UNKNOWN

    @Test
    fun `inapplicable metrics do not affect the aggregate score`() {
        val applicableOnly = listOf(
            CompositionMetric(MetricCategory.HORIZON, "a", score = 0.5f, confidence = 1f),
        )
        val withInapplicable = applicableOnly + CompositionMetric(
            MetricCategory.HEADROOM, "b", score = 0f, confidence = 1f, applicable = false,
        )
        assertEquals(ScoreAggregator.aggregate(applicableOnly, scene), ScoreAggregator.aggregate(withInapplicable, scene), 0.001f)
    }

    @Test
    fun `high severity issues pull the score down`() {
        val clean = listOf(CompositionMetric(MetricCategory.HORIZON, "a", score = 0.9f, confidence = 1f))
        val withHighSeverity = listOf(
            CompositionMetric(MetricCategory.HORIZON, "a", score = 0.9f, confidence = 1f, severity = Severity.HIGH),
        )
        assertTrue(ScoreAggregator.aggregate(withHighSeverity, scene) < ScoreAggregator.aggregate(clean, scene))
    }

    @Test
    fun `empty metric list returns the neutral score`() {
        assertEquals(ScoreAggregator.NEUTRAL_SCORE, ScoreAggregator.aggregate(emptyList(), scene), 0.001f)
    }
}

package com.compositioncoach.composition

import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.engine.Feedback
import com.compositioncoach.composition.engine.RecommendationEngine
import com.compositioncoach.composition.engine.ScoreAggregator
import com.compositioncoach.composition.fixtures.SyntheticFrames
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.fixtures.SyntheticFrames.maskFromRect
import com.compositioncoach.composition.fixtures.SyntheticFrames.objectAt
import com.compositioncoach.composition.fixtures.SyntheticFrames.standingBody
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.CropAspect
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ObjectCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveWeightsTest {

    @Test
    fun `multipliers stay within bounds under any amount of feedback`() {
        var weights = AdaptiveWeights.NEUTRAL
        repeat(500) {
            weights = weights.apply(Feedback.NOT_HELPFUL(MetricCategory.HEADROOM))
            weights = weights.apply(Feedback.IGNORED_AND_RETAKEN(MetricCategory.HORIZON))
            for (value in weights.allMultipliers().values) assertTrue(value in 0.6f..1.4f)
        }
        assertEquals(AdaptiveWeights.MIN_MULTIPLIER, weights.multiplier(MetricCategory.HEADROOM), 0.005f) // one decay after the last step
        assertEquals(AdaptiveWeights.MAX_MULTIPLIER, weights.multiplier(MetricCategory.HORIZON), 0f)
    }

    @Test
    fun `steps follow the update rule`() {
        val kept = AdaptiveWeights.NEUTRAL.apply(Feedback.IGNORED_AND_KEPT(MetricCategory.HEADROOM))
        assertEquals(0.97f, kept.multiplier(MetricCategory.HEADROOM), 1e-5f)
        val retaken = AdaptiveWeights.NEUTRAL.apply(Feedback.IGNORED_AND_RETAKEN(MetricCategory.HEADROOM))
        assertEquals(1.02f, retaken.multiplier(MetricCategory.HEADROOM), 1e-5f)
        // FOLLOWED only repairs a lowered weight; it never pushes above 1.0.
        assertEquals(1f, AdaptiveWeights.NEUTRAL.apply(Feedback.FOLLOWED(MetricCategory.HEADROOM)).multiplier(MetricCategory.HEADROOM), 0f)
        val repaired = kept.apply(Feedback.FOLLOWED(MetricCategory.HEADROOM))
        assertEquals(1f + (0.97f - 1f) * 0.995f + 0.01f, repaired.multiplier(MetricCategory.HEADROOM), 1e-5f)
        // A crop choice changes no multiplier.
        assertTrue(AdaptiveWeights.NEUTRAL.apply(Feedback.CROP_CHOSEN(CropAspect.SQUARE, tighter = true)).isNeutral)
    }

    @Test
    fun `every event decays all multipliers half a percent toward 1`() {
        val start = AdaptiveWeights.of(mapOf(MetricCategory.HORIZON to 0.8f, MetricCategory.LIGHT to 1.2f))
        val after = start.apply(Feedback.CROP_CHOSEN(CropAspect.ORIGINAL, tighter = false))
        assertEquals(1f - 0.2f * 0.995f, after.multiplier(MetricCategory.HORIZON), 1e-5f)
        assertEquals(1f + 0.2f * 0.995f, after.multiplier(MetricCategory.LIGHT), 1e-5f)
        var decayed = start
        repeat(2000) { decayed = decayed.apply(Feedback.FOLLOWED(MetricCategory.SYMMETRY)) }
        assertEquals(1f, decayed.multiplier(MetricCategory.LIGHT), 0.001f)
    }

    @Test
    fun `serialisation round-trips and tolerates junk`() {
        val weights = AdaptiveWeights.NEUTRAL
            .apply(Feedback.IGNORED_AND_KEPT(MetricCategory.HORIZON))
            .apply(Feedback.IGNORED_AND_RETAKEN(MetricCategory.HEADROOM))
            .apply(Feedback.CROP_CHOSEN(CropAspect.SQUARE, tighter = true))
            .apply(Feedback.CROP_CHOSEN(CropAspect.FOUR_FIVE, tighter = false))
        val text = weights.serialize()
        val parsed = AdaptiveWeights.parse(text)
        for (category in MetricCategory.entries) {
            assertEquals(weights.multiplier(category), parsed.multiplier(category), 0.001f)
        }
        assertEquals(1, parsed.cropCount(CropAspect.SQUARE))
        assertEquals(1, parsed.cropCount(CropAspect.FOUR_FIVE))
        assertEquals(1, parsed.tightCrops)
        assertEquals(2, parsed.totalCrops)
        assertEquals(text, parsed.serialize())

        val compact = AdaptiveWeights.parse("HORIZON=0.94,HEADROOM=1.1")
        assertEquals(0.94f, compact.multiplier(MetricCategory.HORIZON), 1e-5f)
        assertEquals(1.1f, compact.multiplier(MetricCategory.HEADROOM), 1e-5f)
        assertEquals("HORIZON=0.94,HEADROOM=1.1", compact.serialize())

        val junk = AdaptiveWeights.parse("HORIZON=abc,NOPE=0.7,LIGHT=9,=,,crop.SQUARE=-4")
        assertEquals(1f, junk.multiplier(MetricCategory.HORIZON), 0f)
        assertEquals(AdaptiveWeights.MAX_MULTIPLIER, junk.multiplier(MetricCategory.LIGHT), 0f)
        assertEquals(0, junk.cropCount(CropAspect.SQUARE))
        assertEquals("", AdaptiveWeights.NEUTRAL.serialize())
        assertEquals(AdaptiveWeights.NEUTRAL, AdaptiveWeights.parse(null))
    }

    @Test
    fun `a category at 0_6 ranks below one at 1_0 with equal severity`() {
        fun rec(id: String, category: MetricCategory, direction: Direction) = Recommendation(
            id = id, category = category, priority = Priority.MEDIUM, confidence = 0.8f, severity = Severity.MEDIUM,
            title = id, instruction = id, direction = direction,
        )
        val metrics = listOf(
            CompositionMetric(MetricCategory.HEADROOM, "h", 0.5f, 0.8f, Severity.MEDIUM, recommendation = rec("headroom", MetricCategory.HEADROOM, Direction.DOWN)),
            CompositionMetric(MetricCategory.HORIZON, "z", 0.5f, 0.8f, Severity.MEDIUM, recommendation = rec("horizon", MetricCategory.HORIZON, Direction.ROTATE_CLOCKWISE)),
        )
        val engine = RecommendationEngine()
        // Neutral: equal confidence, so ease of correction decides (rotation first).
        assertEquals("horizon", engine.rank(metrics, GuidanceLevel.COACH).first().id)
        val lowHorizon = AdaptiveWeights.of(mapOf(MetricCategory.HORIZON to 0.6f))
        assertEquals(listOf("headroom", "horizon"), engine.rank(metrics, GuidanceLevel.COACH, lowHorizon).map { it.id })
    }

    @Test
    fun `multipliers move the aggregate score toward the categories the user values`() {
        val metrics = listOf(
            CompositionMetric(MetricCategory.HORIZON, "a", score = 0.2f, confidence = 1f),
            CompositionMetric(MetricCategory.SUBJECT_PLACEMENT, "b", score = 1f, confidence = 1f),
        )
        val scene = SceneClassification.UNKNOWN
        val neutral = ScoreAggregator.aggregate(metrics, scene)
        val ignoresHorizon = ScoreAggregator.aggregate(metrics, scene, AdaptiveWeights.of(mapOf(MetricCategory.HORIZON to 0.6f)))
        assertTrue(ignoresHorizon > neutral)
        assertEquals(neutral, ScoreAggregator.aggregate(metrics, scene, AdaptiveWeights.NEUTRAL), 0f)
    }

    /**
     * Raw scores and headline ids of the golden-scenario frames, recorded on the pipeline *before* the taste
     * model existed. Neutral weights must reproduce them exactly.
     */
    @Test
    fun `neutral weights reproduce the pre-taste-model results exactly`() {
        val engine = CompositionEngine.default()
        val personRect = NormalizedRect(0.35f, 0.15f, 0.65f, 0.9f)
        val personRect2 = NormalizedRect(0.3f, 0.2f, 0.7f, 0.95f)
        val window = NormalizedRect(0.34f, 0.02f, 0.66f, 0.34f)
        val cases = listOf(
            frameWith(faces = listOf(face(0.85f, 0.1f, 0.05f)), objects = listOf(objectAt(0.5f, 0.55f, 0.35f, ObjectCategory.FOOD, 0.9f))) to
                (72.58989f to listOf("placement.thirds")),
            frameWith(faces = listOf(face(0.5f, 0.5f, 0.5f))) to (89.3631f to emptyList()),
            frameWith(faces = listOf(face(0.5f, 0.22f, 0.15f)), bodies = listOf(standingBody(0.5f, 0.15f, 1.0f))) to
                (60.879158f to listOf("cropping.feet")),
            frameWith(faces = listOf(face(0.25f, 0.4f, 0.12f, id = 0), face(0.5f, 0.4f, 0.12f, id = 1), face(0.97f, 0.4f, 0.12f, id = 2))) to
                (40.89636f to listOf("edge.tension.group_cutoff", "headroom.excessive")),
            frameWith(stats = SyntheticFrames.statsWithHorizonStep(0.5f, 8f), rollDegrees = 8f) to
                (54.131313f to listOf("horizon.tilt", "scene.landscape.horizon")),
            frameWith(stats = SyntheticFrames.offCenterSalientRegion(0.75f, 0.6f)) to (68.5657f to listOf("symmetry.offcenter")),
            frameWith(stats = SyntheticFrames.offCenterSalientRegion(0.5f, 0.6f)) to (91.75207f to emptyList()),
            frameWith(
                faces = listOf(face(0.5f, 0.3f, 0.2f)),
                stats = SyntheticFrames.statsWithNarrowPoleAbove(personRect, gridWidth = 32, gridHeight = 32),
                subjectMask = maskFromRect(personRect, 32, 32),
            ) to (70.85622f to listOf("background.pole", "separation.blends")),
            frameWith(
                faces = listOf(face(0.5f, 0.35f, 0.2f)),
                stats = SyntheticFrames.statsWithLuminancePatches(0.7f, listOf(window to 0.95f, personRect2 to 0.2f)),
                subjectMask = maskFromRect(personRect2),
            ) to (81.61921f to listOf("light.backlit")),
            frameWith(faces = listOf(face(0.88f, 0.4f, 0.15f))) to (54.69818f to listOf("headroom.excessive", "negativespace.subjecttoosmall")),
        )
        for ((frame, expected) in cases) {
            val default = engine.evaluate(frame)
            val neutral = engine.evaluate(frame, adaptive = AdaptiveWeights.NEUTRAL)
            assertEquals(expected.first, default.rawScore, 0f)
            assertEquals(expected.first, neutral.rawScore, 0f)
            assertEquals(expected.second, neutral.recommendations.map { it.id })
            assertEquals(default.optimization?.best?.predictedScore, neutral.optimization?.best?.predictedScore)
        }
    }

    @Test
    fun `tendencies are plain sentences drawn from the multipliers and crop history`() {
        assertTrue(AdaptiveWeights.NEUTRAL.summaryTendencies().isEmpty())
        var weights = AdaptiveWeights.NEUTRAL
        repeat(12) { weights = weights.apply(Feedback.IGNORED_AND_KEPT(MetricCategory.SUBJECT_PLACEMENT)) }
        repeat(6) { weights = weights.apply(Feedback.NOT_HELPFUL(MetricCategory.HEADROOM)) }
        repeat(4) { weights = weights.apply(Feedback.CROP_CHOSEN(CropAspect.ORIGINAL, tighter = true)) }
        val tendencies = weights.summaryTendencies()
        assertEquals(
            listOf("You like tight crops", "You tend to keep centred subjects", "You rarely follow headroom advice"),
            tendencies,
        )
        assertTrue(tendencies.size <= 3)
        for (category in MetricCategory.entries) {
            assertTrue(AdaptiveWeights.lowSentence(category).length <= 60)
            assertTrue(AdaptiveWeights.highSentence(category).length <= 60)
        }
    }
}

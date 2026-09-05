package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.SubjectPlacementAnalyzer
import com.compositioncoach.composition.engine.CompositionOptimizer
import com.compositioncoach.composition.engine.ScoreAggregator
import com.compositioncoach.composition.engine.SubjectResolver
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionOptimizerTest {

    @Test
    fun `subject far to the right predicts a rightward pan as the best move`() {
        // Anchor y is pinned to the lower thirds line so the vertical component stays near-optimal
        // and the (large) horizontal offset dominates which candidate wins.
        val subjectFace = face(0.9f, 0.345f, 0.12f)
        val frame = frameWith(faces = listOf(subjectFace))
        val scene = SceneClassification(SceneType.GENERAL, 1f)
        val analyzers = listOf(SubjectPlacementAnalyzer())

        val resolution = SubjectResolver.resolve(frame)
        val context = AnalysisContext(frame, scene, resolution.subjects, resolution.primary)
        val currentScore = ScoreAggregator.aggregate(listOfNotNull(analyzers[0].analyze(context)), scene)

        val optimizer = CompositionOptimizer(analyzers)
        val result = optimizer.optimize(frame, scene, currentScore, GuidanceLevel.BALANCED)

        assertEquals("right", result.best?.label)
    }
}

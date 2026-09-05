package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.LookingRoomAnalyzer
import com.compositioncoach.composition.engine.SubjectResolver
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LookingRoomAnalyzerTest {
    private val analyzer = LookingRoomAnalyzer()

    @Test
    fun `subject looking toward the near edge needs more room`() {
        val frame = frameWith(faces = listOf(face(0.85f, 0.4f, 0.15f, gaze = GazeDirection.RIGHT)))
        val resolution = SubjectResolver.resolve(frame)
        val context = AnalysisContext(frame, SceneClassification.UNKNOWN, resolution.subjects, resolution.primary)
        val metric = analyzer.analyze(context)!!
        val rec = requireNotNull(metric.recommendation)
        assertEquals("Leave more room in front of subject", rec.title)
        assertEquals(Direction.RIGHT, rec.direction)
    }

    @Test
    fun `subject looking toward the centre needs no advice`() {
        val frame = frameWith(faces = listOf(face(0.5f, 0.4f, 0.15f, gaze = GazeDirection.CENTER)))
        val resolution = SubjectResolver.resolve(frame)
        val context = AnalysisContext(frame, SceneClassification.UNKNOWN, resolution.subjects, resolution.primary)
        val metric = analyzer.analyze(context)!!
        assertFalse(metric.applicable)
    }

    @Test
    fun `group portrait gets no looking-room advice`() {
        val frame = frameWith(
            faces = listOf(
                face(0.3f, 0.4f, 0.12f, gaze = GazeDirection.RIGHT, id = 0),
                face(0.7f, 0.4f, 0.12f, gaze = GazeDirection.LEFT, id = 1),
            ),
        )
        val resolution = SubjectResolver.resolve(frame)
        val scene = SceneClassification(SceneType.GROUP_PORTRAIT, 1f)
        val context = AnalysisContext(frame, scene, resolution.subjects, resolution.primary)
        val metric = analyzer.analyze(context)!!
        assertFalse(metric.applicable)
    }
}

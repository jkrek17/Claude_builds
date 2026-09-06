package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.engine.CompositionSmoother
import com.compositioncoach.composition.fixtures.SyntheticFrames.centralSalientRegion
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [SceneIntent] overriding scene detection, subject rules, and the "awaiting subject" coaching. */
class IntentTest {
    private val engine = CompositionEngine.default()

    // --- PORTRAIT ---------------------------------------------------------------------------------

    @Test
    fun `portrait intent keeps a tiny face as the subject instead of dropping it as background`() {
        val frame = frameWith(faces = listOf(face(cx = 0.5f, cy = 0.4f, size = 0.045f)))
        val result = engine.evaluate(frame, intent = SceneIntent.PORTRAIT)

        assertEquals(SceneType.PORTRAIT, result.scene.type)
        assertFalse(result.awaitingSubject)
        assertEquals(SubjectKind.FACE, result.primarySubject?.kind)
        assertTrue(
            "expected a 'move closer' recommendation for a small declared-portrait face",
            result.recommendations.any { it.instruction == "Move closer to your subject" },
        )
    }

    @Test
    fun `portrait intent with no face at all awaits a subject`() {
        val frame = frameWith(stats = centralSalientRegion(hotEdge = 0.05f, backgroundEdge = 0.05f))
        val result = engine.evaluate(frame, intent = SceneIntent.PORTRAIT)

        assertTrue(result.awaitingSubject)
        assertEquals(1, result.recommendations.size)
        assertEquals("intent.portrait.find_subject", result.recommendations[0].id)
        assertEquals("Point the camera at your subject", result.recommendations[0].instruction)
        assertTrue(result.metrics.none { it.category == MetricCategory.HEADROOM })
    }

    // --- GROUP_PORTRAIT -----------------------------------------------------------------------------

    @Test
    fun `group intent with only one face asks to step back to fit everyone in`() {
        val frame = frameWith(faces = listOf(face(cx = 0.5f, cy = 0.4f, size = 0.15f)))
        val result = engine.evaluate(frame, intent = SceneIntent.GROUP_PORTRAIT)

        assertTrue(result.awaitingSubject)
        assertEquals("intent.group.find_subjects", result.recommendations[0].id)
        assertEquals("Step back to fit everyone in", result.recommendations[0].instruction)
    }

    // --- LANDSCAPE ------------------------------------------------------------------------------

    @Test
    fun `landscape intent with a large face still ignores the person for headroom and looking room`() {
        val frame = frameWith(
            faces = listOf(face(cx = 0.5f, cy = 0.4f, size = 0.3f)),
            rollDegrees = 5f,
        )
        val result = engine.evaluate(frame, intent = SceneIntent.LANDSCAPE)

        assertEquals(SceneType.LANDSCAPE, result.scene.type)
        assertFalse(result.awaitingSubject)
        assertNull(result.primarySubject)
        assertFalse(result.metrics.first { it.category == MetricCategory.HEADROOM }.applicable)
        assertFalse(result.metrics.first { it.category == MetricCategory.LOOKING_ROOM }.applicable)
        assertTrue(result.metrics.first { it.category == MetricCategory.HORIZON }.applicable)
    }

    // --- OBJECT ---------------------------------------------------------------------------------

    @Test
    fun `object intent picks the salient region over a background face`() {
        val frame = frameWith(
            faces = listOf(face(cx = 0.85f, cy = 0.1f, size = 0.05f)), // a bystander, well below the default subject threshold
            stats = centralSalientRegion(hotEdge = 0.09f, backgroundEdge = 0.05f), // contrast ~1.75: above the OBJECT threshold (1.6), below the default (2.2)
        )
        val result = engine.evaluate(frame, intent = SceneIntent.OBJECT)

        assertEquals(SceneType.OBJECT, result.scene.type)
        assertFalse(result.awaitingSubject)
        assertEquals(SubjectKind.SALIENT_REGION, result.primarySubject?.kind)
    }

    @Test
    fun `object intent with nothing salient awaits a subject`() {
        val frame = frameWith(stats = centralSalientRegion(hotEdge = 0.05f, backgroundEdge = 0.05f))
        val result = engine.evaluate(frame, intent = SceneIntent.OBJECT)

        assertTrue(result.awaitingSubject)
        assertEquals("intent.object.find_subject", result.recommendations[0].id)
        assertEquals("Move closer to your subject", result.recommendations[0].instruction)
    }

    // --- AUTO: unchanged behaviour ------------------------------------------------------------------

    @Test
    fun `auto intent behaves exactly like calling evaluate without an intent`() {
        val frame = frameWith(
            faces = listOf(face(0.3f, 0.4f, 0.12f, id = 0), face(0.7f, 0.4f, 0.12f, id = 1)),
        )
        val default = engine.evaluate(frame)
        val explicitAuto = engine.evaluate(frame, intent = SceneIntent.AUTO)

        assertEquals(default.scene.type, explicitAuto.scene.type)
        assertEquals(default.primarySubject, explicitAuto.primarySubject)
        assertEquals(default.score, explicitAuto.score)
        assertEquals(SceneType.GROUP_PORTRAIT, explicitAuto.scene.type)
    }

    // --- CompositionSmoother --------------------------------------------------------------------

    private fun findSubjectRecommendation() = Recommendation(
        id = "intent.portrait.find_subject",
        category = MetricCategory.SCENE_SPECIFIC,
        priority = Priority.MEDIUM,
        confidence = 1f,
        severity = Severity.MEDIUM,
        title = "Looking for a face",
        instruction = "Point the camera at your subject",
        direction = Direction.NONE,
    )

    private fun normalResult(score: Float) = CompositionResult.empty().copy(rawScore = score, score = Math.round(score))

    private fun awaitingResult() = CompositionResult.empty().copy(
        rawScore = 0f, score = 0,
        recommendations = listOf(findSubjectRecommendation()),
        awaitingSubject = true,
    )

    @Test
    fun `smoother holds the last score and flags awaiting while a declared subject is missing, then resumes`() {
        val smoother = CompositionSmoother()

        // A few normal frames settle the displayed score at 75.
        var last = smoother.update(normalResult(75f))
        repeat(20) { last = smoother.update(normalResult(75f)) }
        assertEquals(75, last.displayScore)
        assertFalse(last.awaitingSubject)

        // The subject disappears: intent mode kicks in.
        repeat(5) { last = smoother.update(awaitingResult()) }
        assertTrue(last.awaitingSubject)
        assertFalse(last.isShootReady)
        assertEquals(75, last.displayScore) // held, not dragged toward the meaningless 0
        assertEquals("intent.portrait.find_subject", last.primaryRecommendation?.id)

        // The subject reappears: normal behaviour resumes.
        last = smoother.update(normalResult(75f))
        assertFalse(last.awaitingSubject)
        assertEquals(75, last.displayScore)
    }
}

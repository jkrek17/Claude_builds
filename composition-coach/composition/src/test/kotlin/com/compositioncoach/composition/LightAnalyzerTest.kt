package com.compositioncoach.composition

import com.compositioncoach.composition.analyzer.AnalysisContext
import com.compositioncoach.composition.analyzer.LightAnalyzer
import com.compositioncoach.composition.engine.SubjectResolver
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.fixtures.SyntheticFrames.maskFromRect
import com.compositioncoach.composition.fixtures.SyntheticFrames.objectAt
import com.compositioncoach.composition.fixtures.SyntheticFrames.statsWithLuminancePatches
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LightAnalyzerTest {
    private val analyzer = LightAnalyzer()

    /** Standing person: face centred at (0.5, 0.35), 0.2 tall; body silhouette below and around it. */
    private val face = face(0.5f, 0.35f, 0.2f)
    private val faceRect = face.bounds
    private val personRect = NormalizedRect(0.3f, 0.2f, 0.7f, 0.95f)

    private fun run(frame: FrameAnalysis): CompositionMetric {
        val resolution = SubjectResolver.resolve(frame)
        return analyzer.analyze(AnalysisContext(frame, SceneClassification.UNKNOWN, resolution.subjects, resolution.primary))
    }

    private fun portrait(stats: ImageStatistics?, mask: SubjectMask? = maskFromRect(personRect)) =
        frameWith(faces = listOf(face), stats = stats, subjectMask = mask)

    /** Wall of [wall], a person-sized patch of [person]; optional window patch behind the head. */
    private fun room(wall: Float, person: Float, window: Float? = null) = statsWithLuminancePatches(
        wall,
        listOfNotNull(
            window?.let { NormalizedRect(0.34f, 0.02f, 0.66f, 0.34f) to it },
            personRect to person,
        ),
    )

    // --- Backlit ----------------------------------------------------------------------------------

    @Test
    fun `bright window directly behind the head asks to move the window beside you`() {
        val metric = run(portrait(room(wall = 0.7f, person = 0.2f, window = 0.95f)))
        val rec = requireNotNull(metric.recommendation)
        assertEquals("light.backlit", rec.id)
        assertEquals(MetricCategory.LIGHT, rec.category)
        assertEquals(Severity.MEDIUM, rec.severity)
        assertEquals("Move so the bright window is beside you, not behind", rec.instruction)
        assertEquals("Your subject is darker than the background", rec.reason)
        assertEquals(Direction.NONE, rec.direction)
        assertEquals(0.8f, metric.confidence, 1e-6f)
        assertNull(metric.strength)
        assertNotNull(metric.issue)
        assertTrue(metric.score < 0.5f)
    }

    @Test
    fun `uniformly bright background behind a dark subject asks to turn so the light is behind you`() {
        val metric = run(portrait(room(wall = 0.85f, person = 0.2f)))
        val rec = requireNotNull(metric.recommendation)
        assertEquals("light.backlit", rec.id)
        assertEquals("Turn so the light is behind you", rec.instruction)
        assertEquals(Severity.MEDIUM, rec.severity)
    }

    @Test
    fun `backlit is still found from the face box alone but at lower confidence`() {
        val stats = statsWithLuminancePatches(0.85f, listOf(faceRect to 0.2f))
        val metric = run(portrait(stats, mask = null))
        assertEquals("light.backlit", metric.recommendation?.id)
        assertEquals(0.55f, metric.confidence, 1e-6f)
        assertEquals("Turn so the light is behind you", metric.recommendation?.instruction)
    }

    @Test
    fun `a moderately darker subject against a mid background is not backlit`() {
        val metric = run(portrait(room(wall = 0.58f, person = 0.45f)))
        assertNull(metric.recommendation)
    }

    // --- Dark subject -------------------------------------------------------------------------------

    @Test
    fun `a dark subject in a dim scene says move toward the light`() {
        val rec = requireNotNull(run(portrait(room(wall = 0.4f, person = 0.1f))).recommendation)
        assertEquals("light.dark", rec.id)
        assertEquals("Move toward the light", rec.instruction)
        assertEquals(Severity.MEDIUM, rec.severity)
    }

    @Test
    fun `a slightly dark subject is only low severity`() {
        val rec = requireNotNull(run(portrait(room(wall = 0.35f, person = 0.18f))).recommendation)
        assertEquals("light.dark", rec.id)
        assertEquals(Severity.LOW, rec.severity)
    }

    // --- Harsh side light -----------------------------------------------------------------------------

    @Test
    fun `split lighting across the face asks to turn toward the light`() {
        val midX = faceRect.center.x
        val stats = statsWithLuminancePatches(
            0.5f,
            listOf(
                personRect to 0.5f,
                NormalizedRect(faceRect.left, faceRect.top, midX, faceRect.bottom) to 0.25f,
                NormalizedRect(midX, faceRect.top, faceRect.right, faceRect.bottom) to 0.72f,
            ),
        )
        val rec = requireNotNull(run(portrait(stats)).recommendation)
        assertEquals("light.split", rec.id)
        assertEquals("Turn your subject slightly toward the light", rec.instruction)
        assertEquals(Severity.LOW, rec.severity)
        assertEquals(Direction.NONE, rec.direction)
    }

    @Test
    fun `a face too small to judge halves does not trigger split light`() {
        val tiny = face(0.5f, 0.3f, 0.08f)
        val midX = tiny.bounds.center.x
        val stats = statsWithLuminancePatches(
            0.5f,
            listOf(
                NormalizedRect(tiny.bounds.left, tiny.bounds.top, midX, tiny.bounds.bottom) to 0.1f,
                NormalizedRect(midX, tiny.bounds.top, tiny.bounds.right, tiny.bounds.bottom) to 0.9f,
            ),
        )
        assertNull(run(frameWith(faces = listOf(tiny), stats = stats)).recommendation?.takeIf { it.id == "light.split" })
    }

    // --- Blown highlights -----------------------------------------------------------------------------

    @Test
    fun `blown highlights on the face say step into the shade`() {
        // The top rows of the face at 0.99 (about a third of its cells), the rest a healthy 0.6.
        val stats = statsWithLuminancePatches(
            0.5f,
            listOf(
                personRect to 0.5f,
                faceRect to 0.6f,
                NormalizedRect(faceRect.left, faceRect.top, faceRect.right, faceRect.top + 0.07f) to 0.99f,
            ),
        )
        val rec = requireNotNull(run(portrait(stats)).recommendation)
        assertEquals("light.blown", rec.id)
        assertEquals("Step into the shade", rec.instruction)
        assertEquals(Severity.LOW, rec.severity)
    }

    @Test
    fun `a few hot cells are not blown highlights`() {
        val stats = statsWithLuminancePatches(
            0.5f,
            listOf(
                personRect to 0.5f,
                faceRect to 0.55f,
                NormalizedRect(faceRect.left, faceRect.top, faceRect.left + 0.04f, faceRect.top + 0.035f) to 0.99f,
            ),
        )
        assertNull(run(portrait(stats)).recommendation)
    }

    // --- Good light -------------------------------------------------------------------------------------

    @Test
    fun `even, moderate light is a strength and never an issue`() {
        val metric = run(portrait(room(wall = 0.5f, person = 0.55f)))
        assertNull(metric.recommendation)
        assertNull(metric.issue)
        assertEquals(Severity.NONE, metric.severity)
        assertEquals("Nice light on your subject", metric.strength)
        assertTrue("score ${metric.score}", metric.score >= 0.85f)
    }

    @Test
    fun `very bright but not blown skin is not praised`() {
        val metric = run(portrait(room(wall = 0.5f, person = 0.9f)))
        assertNull(metric.strength)
        assertNull(metric.recommendation)
    }

    // --- Applicability ------------------------------------------------------------------------------------

    @Test
    fun `without luminance statistics the metric is not applicable`() {
        val metric = run(frameWith(faces = listOf(face), stats = null))
        assertFalse(metric.applicable)
        assertNull(metric.recommendation)
    }

    @Test
    fun `without any subject the metric is not applicable`() {
        assertFalse(run(frameWith(stats = room(0.5f, 0.5f))).applicable)
    }

    @Test
    fun `an object subject needs a mask`() {
        val obj = objectAt(0.5f, 0.55f, 0.3f)
        val stats = statsWithLuminancePatches(0.85f, listOf(obj.bounds to 0.15f))
        assertFalse(run(frameWith(objects = listOf(obj), stats = stats)).applicable)

        val withMask = run(frameWith(objects = listOf(obj), stats = stats, subjectMask = maskFromRect(obj.bounds)))
        assertTrue(withMask.applicable)
        assertEquals("light.backlit", withMask.recommendation?.id)
        assertEquals(0.8f, withMask.confidence, 1e-6f)
    }

    // --- Invariants -----------------------------------------------------------------------------------------

    @Test
    fun `scores are in range, severity is never HIGH, and strength and issue never coexist`() {
        val random = Random(7)
        repeat(400) {
            val midX = faceRect.center.x
            val stats = statsWithLuminancePatches(
                random.nextFloat(),
                listOf(
                    personRect to random.nextFloat(),
                    NormalizedRect(faceRect.left, faceRect.top, midX, faceRect.bottom) to random.nextFloat(),
                    NormalizedRect(midX, faceRect.top, faceRect.right, faceRect.bottom) to random.nextFloat(),
                ),
            )
            val metric = run(portrait(stats, if (random.nextBoolean()) maskFromRect(personRect) else null))
            assertTrue(metric.score in 0f..1f)
            assertTrue(metric.confidence in 0f..1f)
            assertTrue(metric.severity != Severity.HIGH)
            assertTrue(metric.recommendation?.severity != Severity.HIGH)
            assertFalse(metric.strength != null && metric.issue != null)
            assertFalse(metric.strength != null && metric.recommendation != null)
            if (metric.strength != null) assertTrue("score ${metric.score}", metric.score >= 0.85f)
        }
    }
}

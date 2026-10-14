package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.fixtures.SyntheticFrames
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.fixtures.SyntheticFrames.maskFromRect
import com.compositioncoach.composition.fixtures.SyntheticFrames.objectAt
import com.compositioncoach.composition.fixtures.SyntheticFrames.standingBody
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ObjectCategory
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end scenarios run through the whole [CompositionEngine], each modelling one situation field
 * testing (or the module brief) called out by name. Every scenario asserts the same four things: the
 * detected [SceneType], the primary subject's [SubjectKind] (or its absence), the headline recommendation
 * id (or its absence), and that the overall score lands in a plausible band — loose bands on purpose,
 * since exact scoring is covered by the analyzer- and aggregator-level unit tests elsewhere; these tests
 * exist to catch a wrong *category* of outcome (wrong scene, wrong subject, wrong headline), not to pin
 * exact numbers that would make this suite brittle against future weight tuning.
 */
class GoldenScenariosTest {
    private val engine = CompositionEngine.default()

    private fun headlineId(result: CompositionResult): String? = result.recommendations.firstOrNull()?.id

    // --- 1. Pub pint on a table, with a distant, incidental face ------------------------------------

    @Test
    fun `a pint on a table beats a distant background face`() {
        val frame = frameWith(
            faces = listOf(face(cx = 0.85f, cy = 0.1f, size = 0.05f)), // a stranger at the next table
            objects = listOf(objectAt(cx = 0.5f, cy = 0.55f, size = 0.35f, category = ObjectCategory.FOOD, confidence = 0.9f)),
        )
        val result = engine.evaluate(frame)

        assertEquals(SceneType.OBJECT, result.scene.type)
        assertEquals(SubjectKind.OBJECT, result.primarySubject?.kind)
        assertTrue(
            "no headroom advice should ever be given about an object",
            result.recommendations.none { it.category == MetricCategory.HEADROOM } &&
                result.metrics.none { it.category == MetricCategory.HEADROOM && it.applicable },
        )
        assertTrue("score out of expected band: ${result.score}", result.score in 30..90)
    }

    // --- 2. Centred close-up portrait ----------------------------------------------------------------

    @Test
    fun `a centred close-up portrait needs no placement advice`() {
        val frame = frameWith(faces = listOf(face(cx = 0.5f, cy = 0.5f, size = 0.5f)))
        val result = engine.evaluate(frame)

        assertEquals(SceneType.PORTRAIT, result.scene.type)
        assertTrue(result.scene.isCloseUpPortrait)
        assertEquals(SubjectKind.FACE, result.primarySubject?.kind)
        assertTrue(result.recommendations.none { it.category == MetricCategory.SUBJECT_PLACEMENT })
        assertTrue("score out of expected band: ${result.score}", result.score in 40..100)
    }

    // --- 3. Full-body person cut at the ankles -------------------------------------------------------

    @Test
    fun `a full-body shot cut at the ankles flags cropping as the headline`() {
        val frame = frameWith(
            faces = listOf(face(cx = 0.5f, cy = 0.22f, size = 0.15f)),
            bodies = listOf(standingBody(cx = 0.5f, headTop = 0.15f, feetY = 1.0f)),
        )
        val result = engine.evaluate(frame)

        assertEquals(SceneType.PORTRAIT, result.scene.type)
        assertEquals(SubjectKind.PERSON, result.primarySubject?.kind)
        assertEquals("cropping.feet", headlineId(result))
        assertTrue("score out of expected band: ${result.score}", result.score in 20..75)
    }

    // --- 4. Group of three, one face right at the edge (HIGH edge tension) --------------------------

    @Test
    fun `a group of three with one face at the edge is a HIGH severity cutoff`() {
        val frame = frameWith(
            faces = listOf(
                face(cx = 0.25f, cy = 0.4f, size = 0.12f, id = 0),
                face(cx = 0.5f, cy = 0.4f, size = 0.12f, id = 1),
                face(cx = 0.97f, cy = 0.4f, size = 0.12f, id = 2), // right edge: bounds run past x=1
            ),
        )
        val result = engine.evaluate(frame)

        assertEquals(SceneType.GROUP_PORTRAIT, result.scene.type)
        // The optimizer may promote a different (also-valid) recommendation to the headline slot when its
        // simulated fix scores even better (see CompositionEngine.applyOptimizerInsight) — what this
        // scenario must guarantee is that the cutoff itself is flagged, at HIGH severity, not that it wins
        // the headline slot specifically.
        val cutoff = result.recommendations.firstOrNull { it.id == "edge.tension.group_cutoff" }
            ?: result.metrics.first { it.category == MetricCategory.EDGE_TENSION }.recommendation
        assertEquals("edge.tension.group_cutoff", cutoff?.id)
        assertEquals(Severity.HIGH, cutoff?.severity)
        assertTrue("score out of expected band: ${result.score}", result.score in 0..60)
    }

    // --- 5. Landscape with a tilted horizon sitting dead centre --------------------------------------

    @Test
    fun `a landscape with a tilted, dead-centre horizon asks to both rotate and reposition`() {
        val stats = SyntheticFrames.statsWithHorizonStep(rowFraction = 0.5f, angleDegrees = 8f)
        val frame = frameWith(stats = stats, rollDegrees = 8f)
        val result = engine.evaluate(frame)

        assertEquals(SceneType.LANDSCAPE, result.scene.type)
        assertNull(result.primarySubject)
        assertTrue(
            "expected a rotate recommendation for the 8 degree tilt",
            result.recommendations.any { it.category == MetricCategory.HORIZON },
        )
        assertTrue(
            "expected a horizon-repositioning recommendation, or at least an issue noting one",
            result.recommendations.any { it.category == MetricCategory.SCENE_SPECIFIC } ||
                result.improvements.any { it.contains("horizon", ignoreCase = true) },
        )
        assertTrue("score out of expected band: ${result.score}", result.score in 10..70)
    }

    // --- 6. Symmetric hallway -------------------------------------------------------------------------

    @Test
    fun `a symmetric hallway with an off-centre subject asks to centre it`() {
        val frame = frameWith(stats = SyntheticFrames.offCenterSalientRegion(cx = 0.75f, hotEdge = 0.6f))
        val result = engine.evaluate(frame)

        assertEquals(SceneType.ARCHITECTURE, result.scene.type)
        assertTrue(result.scene.isSymmetricScene)
        assertEquals(SubjectKind.SALIENT_REGION, result.primarySubject?.kind)
        assertEquals("symmetry.offcenter", headlineId(result))
        assertTrue("score out of expected band: ${result.score}", result.score in 20..85)
    }

    @Test
    fun `a symmetric hallway with a centred subject needs no centring advice`() {
        val frame = frameWith(stats = SyntheticFrames.offCenterSalientRegion(cx = 0.5f, hotEdge = 0.6f))
        val result = engine.evaluate(frame)

        assertEquals(SceneType.ARCHITECTURE, result.scene.type)
        assertTrue(result.scene.isSymmetricScene)
        assertTrue(result.recommendations.none { it.category == MetricCategory.SYMMETRY })
        assertTrue("score out of expected band: ${result.score}", result.score in 60..100)
    }

    // --- 7. Portrait with a pole above the head, caught by the subject mask -------------------------

    @Test
    fun `a mask-detected pole above the head is flagged even though the face box alone wouldn't show it`() {
        val personRect = NormalizedRect(0.35f, 0.15f, 0.65f, 0.9f)
        val frame = frameWith(
            faces = listOf(face(cx = 0.5f, cy = 0.3f, size = 0.2f)),
            stats = SyntheticFrames.statsWithNarrowPoleAbove(personRect, gridWidth = 32, gridHeight = 32),
            subjectMask = maskFromRect(personRect, gridWidth = 32, gridHeight = 32),
        )
        val result = engine.evaluate(frame)

        assertEquals(SceneType.PORTRAIT, result.scene.type)
        assertEquals(SubjectKind.FACE, result.primarySubject?.kind)
        val backgroundMetric = result.metrics.first { it.category == MetricCategory.BACKGROUND_DISTRACTION }
        assertEquals("background.pole", backgroundMetric.recommendation?.id)
        assertTrue("score out of expected band: ${result.score}", result.score in 10..90)
    }
}

package com.compositioncoach.composition

import com.compositioncoach.composition.engine.AfterActionReport
import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.engine.Verdict
import com.compositioncoach.composition.fixtures.SyntheticFrames
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.fixtures.SyntheticFrames.maskFromRect
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.FramingCandidate
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.OptimizationResult
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.ScoreWeights
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterActionReportTest {
    private val engine = CompositionEngine.default()

    private val personSubject = DetectedSubject(
        id = 0, kind = SubjectKind.FACE, bounds = NormalizedRect(0.4f, 0.3f, 0.6f, 0.5f),
        face = face(cx = 0.5f, cy = 0.4f, size = 0.2f), isPrimary = true,
    )

    private fun rec(id: String, category: MetricCategory, direction: Direction, improvement: Float? = null, region: NormalizedRect? = null) =
        Recommendation(
            id = id, category = category, priority = Priority.MEDIUM, confidence = 0.8f, severity = Severity.MEDIUM,
            title = id, instruction = id, direction = direction, expectedImprovement = improvement, region = region,
        )

    private fun result(
        score: Int,
        metrics: List<CompositionMetric> = emptyList(),
        recommendations: List<Recommendation> = emptyList(),
        best: FramingCandidate? = null,
        strengths: List<String> = emptyList(),
    ) = CompositionResult(
        timestampNanos = 0L, score = score, rawScore = score.toFloat(),
        scene = SceneClassification(SceneType.PORTRAIT, 1f),
        metrics = metrics, recommendations = recommendations,
        subjects = listOf(personSubject), primarySubject = personSubject,
        isShootReady = false, strengths = strengths,
        optimization = best?.let { OptimizationResult(score.toFloat(), listOf(FramingCandidate("current", ReframeVector.ZERO, score.toFloat()), it), it) },
        weights = ScoreWeights.PORTRAIT,
    )

    @Test
    fun `verdict thresholds and the HIGH-severity cap`() {
        assertEquals(Verdict.KEEPER, AfterActionReport.verdictFor(80, false))
        assertEquals(Verdict.GOOD, AfterActionReport.verdictFor(79, false))
        assertEquals(Verdict.GOOD, AfterActionReport.verdictFor(60, false))
        assertEquals(Verdict.TRY_AGAIN, AfterActionReport.verdictFor(59, false))
        assertEquals(Verdict.GOOD, AfterActionReport.verdictFor(95, true))
        assertEquals(Verdict.TRY_AGAIN, AfterActionReport.verdictFor(40, true))

        val high = CompositionMetric(MetricCategory.EDGE_TENSION, "e", 0.2f, 1f, Severity.HIGH)
        assertEquals(Verdict.GOOD, AfterActionReport.build(result(90, metrics = listOf(high))).verdict)
        val inapplicableHigh = high.copy(applicable = false)
        assertEquals(Verdict.KEEPER, AfterActionReport.build(result(90, metrics = listOf(inapplicableHigh))).verdict)
    }

    @Test
    fun `the optimizer's best move credits a matching recommendation`() {
        val best = FramingCandidate("down", ReframeVector(dy = -0.07f), 74f)
        val report = AfterActionReport.build(
            result(64, recommendations = listOf(rec("headroom.excessive", MetricCategory.HEADROOM, Direction.DOWN)), best = best),
        )
        val next = report.tryNextTime!!
        assertEquals("Lowering the camera a little would have fixed the headroom (+10)", next.sentence)
        assertEquals(10, next.predictedGain)
        assertEquals(Direction.DOWN, next.direction)
        assertEquals(MetricCategory.HEADROOM, next.category)
        assertTrue(next.fromOptimizer)
    }

    @Test
    fun `a sideways move with a background problem says it would have cleared it`() {
        val pole = rec("background.pole", MetricCategory.BACKGROUND_DISTRACTION, Direction.NONE, region = NormalizedRect(0.45f, 0f, 0.55f, 0.3f))
        val metrics = listOf(CompositionMetric(MetricCategory.BACKGROUND_DISTRACTION, "b", 0.3f, 0.8f, Severity.MEDIUM, recommendation = pole))
        val best = FramingCandidate("left", ReframeVector(dx = -0.07f), 79f)
        val report = AfterActionReport.build(result(70, metrics = metrics, recommendations = listOf(pole), best = best))
        assertEquals("A step to the left would have cleared the pole behind them (+9)", report.tryNextTime!!.sentence)
        assertEquals(MetricCategory.BACKGROUND_DISTRACTION, report.tryNextTime!!.category)
    }

    @Test
    fun `a sideways move with nothing else to credit is about placement, without jargon`() {
        val best = FramingCandidate("left", ReframeVector(dx = -0.07f), 76f)
        val report = AfterActionReport.build(result(70, best = best))
        assertEquals("A step to the left would have placed them off centre, where it reads better (+6)", report.tryNextTime!!.sentence)
    }

    @Test
    fun `a small optimizer gain falls back to the top recommendation`() {
        val best = FramingCandidate("left", ReframeVector(dx = -0.07f), 72f)
        val headroom = rec("headroom.excessive", MetricCategory.HEADROOM, Direction.DOWN, improvement = 4.2f)
        val report = AfterActionReport.build(result(70, recommendations = listOf(headroom), best = best))
        val next = report.tryNextTime!!
        assertEquals("Lowering the camera a little would have fixed the headroom (+4)", next.sentence)
        assertFalse(next.fromOptimizer)
        assertEquals("headroom.excessive", next.recommendationId)
    }

    @Test
    fun `nothing to say means no try-next-time`() {
        assertNull(AfterActionReport.build(result(92)).tryNextTime)
    }

    @Test
    fun `every direction and category reads as one plain sentence under 90 characters`() {
        val ids = listOf(
            null, "background.pole", "edge.tension.group_cutoff", "cropping.feet", "cropping.headtop",
            "scene.landscape.horizon", "scene.architecture.converging", "light.backlit", "light.dark",
            "light.blown", "light.split", "separation.blends",
        )
        val subjects = listOf(personSubject, personSubject.copy(kind = SubjectKind.OBJECT, face = null), null)
        for (subject in subjects) for (direction in Direction.entries) for (category in MetricCategory.entries) for (id in ids) {
            val r = result(50).copy(primarySubject = subject)
            val sentence = AfterActionReport.sentence(direction, AfterActionReport.outcomeFor(category, id, r), 99, id)
            assertTrue("too long (${sentence.length}): $sentence", sentence.length <= AfterActionReport.MAX_SENTENCE_LENGTH)
            assertTrue(sentence, sentence.endsWith("(+99)"))
            assertTrue(sentence, sentence.contains(" would have "))
            for (jargon in listOf("third", "rule", "negative space", "composition", "leading line")) {
                assertFalse("jargon in: $sentence", sentence.contains(jargon, ignoreCase = true))
            }
        }
    }

    @Test
    fun `lead phrases per direction`() {
        assertEquals("A step to the left", AfterActionReport.leadFor(Direction.LEFT))
        assertEquals("A step to the right", AfterActionReport.leadFor(Direction.RIGHT))
        assertEquals("Raising the camera a little", AfterActionReport.leadFor(Direction.UP))
        assertEquals("Lowering the camera a little", AfterActionReport.leadFor(Direction.DOWN))
        assertEquals("Moving a little closer", AfterActionReport.leadFor(Direction.CLOSER))
        assertEquals("A step back", AfterActionReport.leadFor(Direction.BACK))
        assertEquals("Turning the phone slightly clockwise", AfterActionReport.leadFor(Direction.ROTATE_CLOCKWISE))
        assertEquals("Turning the phone slightly counter-clockwise", AfterActionReport.leadFor(Direction.ROTATE_COUNTER_CLOCKWISE))
        assertEquals("Turning so the light was behind you", AfterActionReport.leadFor(Direction.NONE, "light.backlit"))
    }

    @Test
    fun `worked lists at most three plain strengths, best first`() {
        val report = AfterActionReport.build(
            result(85, strengths = listOf("Subject sits on a strong thirds point", "Level horizon", "Comfortable headroom", "Well-balanced frame")),
        )
        assertEquals(listOf("Subject sits off centre, where it reads well", "Level horizon", "Comfortable headroom"), report.worked)
    }

    @Test
    fun `a backlit portrait gets a light note from the engine`() {
        val personRect = NormalizedRect(0.3f, 0.2f, 0.7f, 0.95f)
        val window = NormalizedRect(0.34f, 0.02f, 0.66f, 0.34f)
        val frame = frameWith(
            faces = listOf(face(cx = 0.5f, cy = 0.35f, size = 0.2f)),
            stats = SyntheticFrames.statsWithLuminancePatches(0.7f, listOf(window to 0.95f, personRect to 0.2f)),
            subjectMask = maskFromRect(personRect),
        )
        val report = AfterActionReport.build(engine.evaluate(frame, GuidanceLevel.COACH))
        assertNotNull(report.lightNote)
        assertTrue(report.lightNote!!, report.lightNote!!.startsWith("Your subject is darker than the background."))
        assertTrue(report.worked.size <= 3)
    }

    @Test
    fun `an engine result for a right-edge face produces a full card`() {
        val result = engine.evaluate(frameWith(faces = listOf(face(cx = 0.88f, cy = 0.4f, size = 0.15f))), GuidanceLevel.COACH)
        val report = AfterActionReport.build(result)
        assertEquals(Verdict.TRY_AGAIN, report.verdict)
        val next = report.tryNextTime!!
        assertTrue(next.sentence.length <= AfterActionReport.MAX_SENTENCE_LENGTH)
        assertTrue(next.predictedGain >= 1)
        assertTrue(next.sentence.endsWith("(+${next.predictedGain})"))
    }

    @Test
    fun `awaiting a subject gives no try-next-time`() {
        val result = engine.evaluate(frameWith(), GuidanceLevel.COACH, SceneIntent.PORTRAIT)
        assertTrue(result.awaitingSubject)
        val report = AfterActionReport.build(result)
        assertNull(report.tryNextTime)
        assertEquals(Verdict.TRY_AGAIN, report.verdict)
    }
}

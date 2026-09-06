package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.engine.CompositionSmoother
import com.compositioncoach.composition.engine.SmoothingConfig
import com.compositioncoach.composition.model.BodyLandmark
import com.compositioncoach.composition.model.BodyLandmarkType
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.DeviceOrientation
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ObjectCategory
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SubjectMask
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property-style fuzzing of [CompositionEngine.evaluate]: hundreds of randomly generated (but plausible)
 * [FrameAnalysis] inputs, seeded for reproducibility, checked against the invariants the engine must
 * never violate regardless of what the vision layer sends it. See "Quality gate" in composition/README.md
 * for the human-readable list this test enforces.
 */
class EngineRobustnessTest {
    private val engine = CompositionEngine.default()

    private fun randomFace(random: Random, id: Int): DetectedFace {
        // Sizes/positions intentionally allowed to push faces partially (or fully) out of frame.
        val size = random.nextFloat() * 0.9f + 0.02f
        val cx = random.nextFloat() * 1.6f - 0.3f
        val cy = random.nextFloat() * 1.6f - 0.3f
        val half = size / 2f
        val bounds = NormalizedRect(cx - half, cy - half, cx + half, cy + half)
        val gaze = GazeDirection.entries.toTypedArray().random(random)
        val headEulerY = if (random.nextBoolean()) random.nextFloat() * 180f - 90f else null
        val headEulerZ = if (random.nextBoolean()) random.nextFloat() * 180f - 90f else null
        return DetectedFace(
            id = id,
            bounds = bounds,
            leftEye = if (random.nextBoolean()) NormalizedPoint(cx - size * 0.15f, cy) else null,
            rightEye = if (random.nextBoolean()) NormalizedPoint(cx + size * 0.15f, cy) else null,
            noseBase = if (random.nextBoolean()) NormalizedPoint(cx, cy + size * 0.1f) else null,
            headEulerY = headEulerY,
            headEulerZ = headEulerZ,
            gaze = gaze,
            confidence = random.nextFloat(),
            smilingProbability = if (random.nextBoolean()) random.nextFloat() else null,
        )
    }

    private fun randomBody(random: Random, cx: Float, top: Float, bottom: Float, id: Int): DetectedBody {
        val landmarks = mutableMapOf<BodyLandmarkType, BodyLandmark>()
        for (type in BodyLandmarkType.entries) {
            if (random.nextFloat() < 0.15f) continue // some landmarks missing, as a real detector would
            val x = cx + (random.nextFloat() - 0.5f) * 0.3f
            val y = top + random.nextFloat() * (bottom - top) + (random.nextFloat() - 0.5f) * 0.2f
            landmarks[type] = BodyLandmark(type, NormalizedPoint(x, y), random.nextFloat())
        }
        val half = random.nextFloat() * 0.2f + 0.05f
        return DetectedBody(
            id = id,
            bounds = NormalizedRect(cx - half, top, cx + half, bottom),
            landmarks = landmarks,
            confidence = random.nextFloat(),
        )
    }

    private fun randomStats(random: Random): ImageStatistics {
        val gridWidth = random.nextInt(8, 33)
        val gridHeight = random.nextInt(8, 33)
        val luminance = FloatArray(gridWidth * gridHeight) { random.nextFloat() }
        val edge = FloatArray(gridWidth * gridHeight) { random.nextFloat() }
        return ImageStatistics(
            gridWidth = gridWidth,
            gridHeight = gridHeight,
            luminance = luminance,
            edgeDensity = edge,
            horizontalSymmetry = random.nextFloat(),
            verticalSymmetry = random.nextFloat(),
            meanLuminance = random.nextFloat(),
            contrast = random.nextFloat(),
            estimatedHorizonAngleDegrees = if (random.nextBoolean()) random.nextFloat() * 40f - 20f else null,
        )
    }

    /** A [SubjectMask] on a grid size deliberately independent of the paired [ImageStatistics] grid. */
    private fun randomMask(random: Random): SubjectMask {
        val gridWidth = random.nextInt(8, 33)
        val gridHeight = random.nextInt(8, 33)
        val probability = FloatArray(gridWidth * gridHeight) { random.nextFloat() }
        return SubjectMask(gridWidth, gridHeight, probability)
    }

    private fun randomFrame(random: Random): FrameAnalysis {
        val faceCount = random.nextInt(0, 5)
        val faces = (0 until faceCount).map { randomFace(random, it) }
        val bodies = faces.filter { random.nextFloat() < 0.6f }.mapIndexed { i, f ->
            randomBody(random, f.bounds.center.x, f.bounds.top, (f.bounds.bottom + random.nextFloat() * 0.6f), i)
        }
        val objects = (0 until random.nextInt(0, 3)).map { i ->
            val size = random.nextFloat() * 0.5f + 0.02f
            val cx = random.nextFloat()
            val cy = random.nextFloat()
            val half = size / 2f
            DetectedObject(
                id = i,
                bounds = NormalizedRect(cx - half, cy - half, cx + half, cy + half),
                category = ObjectCategory.entries.toTypedArray().random(random),
                confidence = random.nextFloat(),
            )
        }
        val stats = if (random.nextFloat() < 0.9f) randomStats(random) else null
        val mask = if (random.nextFloat() < 0.5f) randomMask(random) else null
        val orientation = if (random.nextFloat() < 0.9f) {
            DeviceOrientation(
                rollDegrees = random.nextFloat() * 360f - 180f,
                pitchDegrees = random.nextFloat() * 180f - 90f,
                isReliable = random.nextBoolean(),
            )
        } else {
            null
        }
        return FrameAnalysis(
            timestampNanos = random.nextLong(0, Long.MAX_VALUE / 2),
            frameWidth = random.nextInt(1, 4000),
            frameHeight = random.nextInt(1, 4000),
            faces = faces,
            bodies = bodies,
            stats = stats,
            objects = objects,
            subjectMask = mask,
            orientation = orientation,
            isFrontCamera = random.nextBoolean(),
        )
    }

    private fun oppositeOf(direction: Direction): Direction? = when (direction) {
        Direction.LEFT -> Direction.RIGHT
        Direction.RIGHT -> Direction.LEFT
        Direction.UP -> Direction.DOWN
        Direction.DOWN -> Direction.UP
        Direction.ROTATE_CLOCKWISE -> Direction.ROTATE_COUNTER_CLOCKWISE
        Direction.ROTATE_COUNTER_CLOCKWISE -> Direction.ROTATE_CLOCKWISE
        Direction.CLOSER -> Direction.BACK
        Direction.BACK -> Direction.CLOSER
        Direction.NONE -> null
    }

    private fun assertInvariants(frame: FrameAnalysis, intent: SceneIntent, result: CompositionResult) {
        assertTrue("score out of range: ${result.score}", result.score in 0..100)
        assertTrue("rawScore out of range: ${result.rawScore}", result.rawScore in 0f..100f)
        assertTrue("rawScore is NaN", !result.rawScore.isNaN())

        for (metric in result.metrics) {
            assertTrue(
                "${metric.analyzerName} score out of 0..1: ${metric.score}",
                !metric.score.isNaN() && metric.score in 0f..1f,
            )
            assertTrue(
                "${metric.analyzerName} confidence out of 0..1: ${metric.confidence}",
                !metric.confidence.isNaN() && metric.confidence in 0f..1f,
            )
            assertTrue(
                "${metric.analyzerName} has both a strength and an issue string set on the same metric",
                metric.strength == null || metric.issue == null,
            )
            val rec = metric.recommendation
            if (rec != null) {
                assertTrue(
                    "${metric.analyzerName} recommendation confidence out of 0..1: ${rec.confidence}",
                    !rec.confidence.isNaN() && rec.confidence in 0f..1f,
                )
                assertTrue(
                    "${metric.analyzerName} applicable=false but carries a recommendation",
                    metric.applicable,
                )
                val vector = rec.vector
                if (vector != null) {
                    val primary = vector.primaryDirection()
                    assertTrue(
                        "${rec.id}: direction=${rec.direction} disagrees with vector.primaryDirection()=$primary",
                        rec.direction == primary || rec.direction == Direction.NONE,
                    )
                }
            }
        }

        assertTrue("more than 3 recommendations: ${result.recommendations.size}", result.recommendations.size <= 3)

        val activeDirections = result.recommendations.map { it.direction }.filter { it != Direction.NONE }
        for (i in activeDirections.indices) {
            for (j in activeDirections.indices) {
                if (i == j) continue
                assertTrue(
                    "opposite-direction recommendations both active: ${activeDirections[i]} and ${activeDirections[j]}",
                    oppositeOf(activeDirections[i]) != activeDirections[j],
                )
            }
        }

        if (result.awaitingSubject) {
            assertTrue(
                "awaitingSubject should carry exactly one recommendation, had ${result.recommendations.size}",
                result.recommendations.size == 1,
            )
        }
    }

    @Test
    fun `engine never throws and always honors its invariants over hundreds of random frames`() {
        val random = Random(42)
        val intents = SceneIntent.entries.toTypedArray()
        repeat(400) { iteration ->
            val frame = randomFrame(random)
            val level = GuidanceLevel.entries.toTypedArray().random(random)
            val intent = intents.random(random)
            val result = try {
                engine.evaluate(frame, level, intent)
            } catch (t: Throwable) {
                throw AssertionError("engine threw on iteration $iteration (seed-derived frame): $t", t)
            }
            assertInvariants(frame, intent, result)
        }
    }

    // --- Smoother display-geometry fuzzing -----------------------------------------------------------

    private fun assertInFrameOrNull(label: String, point: com.compositioncoach.composition.model.NormalizedPoint?) {
        if (point == null) return
        assertTrue("$label.x out of 0..1: ${point.x}", !point.x.isNaN() && point.x in 0f..1f)
        assertTrue("$label.y out of 0..1: ${point.y}", !point.y.isNaN() && point.y in 0f..1f)
    }

    private fun assertInFrameOrNull(label: String, rect: NormalizedRect?) {
        if (rect == null) return
        assertTrue("$label.left out of 0..1: ${rect.left}", !rect.left.isNaN() && rect.left in 0f..1f)
        assertTrue("$label.top out of 0..1: ${rect.top}", !rect.top.isNaN() && rect.top in 0f..1f)
        assertTrue("$label.right out of 0..1: ${rect.right}", !rect.right.isNaN() && rect.right in 0f..1f)
        assertTrue("$label.bottom out of 0..1: ${rect.bottom}", !rect.bottom.isNaN() && rect.bottom in 0f..1f)
    }

    @Test
    fun `smoother's display geometry stays inside the frame and secondary lines never duplicate the headline`() {
        val random = Random(42)
        val intents = SceneIntent.entries.toTypedArray()
        val smoother = CompositionSmoother()
        var t = 0L
        repeat(300) { iteration ->
            val frame = randomFrame(random)
            val level = GuidanceLevel.entries.toTypedArray().random(random)
            val intent = intents.random(random)
            val result = engine.evaluate(frame, level, intent)
            t += 100L * 1_000_000L
            val smoothed = try {
                smoother.update(result.copy(timestampNanos = t))
            } catch (throwable: Throwable) {
                throw AssertionError("smoother threw on iteration $iteration: $throwable", throwable)
            }

            assertInFrameOrNull("displayAnchor", smoothed.displayAnchor)
            assertInFrameOrNull("displayTarget", smoothed.displayTarget)
            assertInFrameOrNull("displayRegion", smoothed.displayRegion)
            assertInFrameOrNull("displaySubjectBounds", smoothed.displaySubjectBounds)
            smoothed.displayHorizon?.let { line ->
                assertInFrameOrNull("displayHorizon.start", line.start)
                assertInFrameOrNull("displayHorizon.end", line.end)
            }

            val ids = smoothed.activeRecommendations.map { it.id }
            assertTrue(
                "secondary line duplicates another active line's id on iteration $iteration: $ids",
                ids.size == ids.distinct().size,
            )
        }
    }

    // --- Smoother fuzzing ------------------------------------------------------------------------

    private fun resultWith(random: Random, timestampNanos: Long, rawScore: Float, headline: Recommendation?): CompositionResult {
        val recs = listOfNotNull(headline)
        return CompositionResult(
            timestampNanos = timestampNanos,
            score = rawScore.toInt().coerceIn(0, 100),
            rawScore = rawScore,
            scene = com.compositioncoach.composition.model.SceneClassification.UNKNOWN,
            metrics = emptyList(),
            recommendations = recs,
            subjects = emptyList(),
            primarySubject = null,
            isShootReady = false,
            weights = com.compositioncoach.composition.model.ScoreWeights.forScene(com.compositioncoach.composition.model.SceneType.GENERAL),
        )
    }

    @Test
    fun `smoother stays within 0 to 100 and never NaN under random timestamp gaps`() {
        val random = Random(42)
        val smoother = CompositionSmoother()
        var t = 0L
        repeat(500) {
            val gapMs = listOf(0L, -50L, 5L, 3000L, 1L, 800L).random(random)
            t += gapMs * 1_000_000L
            val rawScore = random.nextFloat() * 100f
            val result = resultWith(random, t, rawScore, headline = null)
            val smoothed = smoother.update(result)
            assertTrue("displayScore out of range: ${smoothed.displayScore}", smoothed.displayScore in 0..100)
        }
    }

    @Test
    fun `smoother settles to the raw score after a constant result for several seconds`() {
        val smoother = CompositionSmoother()
        var t = 0L
        val target = 42f
        repeat(60) { // 60 * 100ms = 6s of constant input
            val result = resultWith(Random(42), t, target, headline = null)
            smoother.update(result)
            t += 100L * 1_000_000L
        }
        val finalResult = resultWith(Random(42), t, target, headline = null)
        val smoothed = smoother.update(finalResult)
        assertTrue(
            "expected displayScore (${smoothed.displayScore}) to have settled near $target",
            kotlin.math.abs(smoothed.displayScore - target) <= 1f,
        )
    }
}

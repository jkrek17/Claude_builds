package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.fixtures.SyntheticFrames
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent
import org.junit.Test
import kotlin.random.Random

/**
 * JVM micro-benchmark-style sanity check, not a correctness test: runs the full engine (including
 * [com.compositioncoach.composition.engine.CompositionOptimizer]'s six per-frame candidate simulations)
 * over a couple hundred realistic frames and prints the mean per-frame time. Asserts nothing about
 * wall-clock time (too flaky across CI/dev machines) — its job is to catch a pipeline that no longer
 * runs at all, and to give a human a number to eyeball when tuning analyzer cost.
 */
class EnginePerformanceTest {

    private fun realisticFrame(random: Random): FrameAnalysis {
        val cx = 0.3f + random.nextFloat() * 0.4f
        val faceHeight = 0.08f + random.nextFloat() * 0.2f
        val faceTop = 0.1f + random.nextFloat() * 0.3f
        val face = SyntheticFrames.face(cx = cx, cy = faceTop + faceHeight / 2f, size = faceHeight)
        val body = SyntheticFrames.standingBody(cx = cx, headTop = faceTop, feetY = 0.85f + random.nextFloat() * 0.1f)
        val mask = SyntheticFrames.maskFromRect(
            com.compositioncoach.composition.model.NormalizedRect(cx - 0.18f, faceTop, cx + 0.18f, 0.9f),
        )
        val stats = SyntheticFrames.withVerticalBandAbove(face, bandEdge = random.nextFloat())
        return SyntheticFrames.frameWith(
            faces = listOf(face),
            bodies = listOf(body),
            stats = stats,
            subjectMask = mask,
            rollDegrees = random.nextFloat() * 6f - 3f,
            timestampNanos = random.nextLong(),
        )
    }

    @Test
    fun `engine evaluates 200 realistic frames and reports mean time`() {
        val engine = CompositionEngine.default()
        val random = Random(7)
        val frames = (0 until 200).map { realisticFrame(random) }

        // Warm up the JIT so the reported mean reflects steady-state cost, not class loading/interpretation.
        frames.take(20).forEach { engine.evaluate(it, GuidanceLevel.COACH, SceneIntent.AUTO) }

        val start = System.nanoTime()
        for (frame in frames) {
            engine.evaluate(frame, GuidanceLevel.COACH, SceneIntent.AUTO)
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        val meanMs = totalMs / frames.size
        println("EnginePerformanceTest: ${frames.size} frames in ${"%.1f".format(totalMs)}ms (mean ${"%.3f".format(meanMs)}ms/frame)")
        // No timing assertion (flaky across machines/CI) -- this test's value is (a) proving the full
        // pipeline runs end-to-end without throwing over a batch, and (b) the printed number above.
    }
}

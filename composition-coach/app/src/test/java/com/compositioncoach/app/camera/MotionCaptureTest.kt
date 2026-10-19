package com.compositioncoach.app.camera

import com.compositioncoach.vision.AccumulatedImage
import com.compositioncoach.vision.AngularDelta
import com.compositioncoach.vision.FrameAccumulator
import com.compositioncoach.vision.FrameAligner
import com.compositioncoach.vision.FrameStackingSource
import com.compositioncoach.vision.StackBlend
import com.compositioncoach.vision.StackingStatus
import com.compositioncoach.vision.merge.Convergence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The Motion hold under a virtual clock: [HoldLoop] driven against a stream of synthetic frames feeding a real
 * [FrameAccumulator] at a chosen frame rate, plus the pure exposure planning and the gyro re-basing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MotionCaptureTest {

    private val w = 32
    private val h = 24

    /** A stream delivering `fps` frames per second of virtual time into a real accumulator. */
    private inner class FakeStream(
        mode: StackBlend,
        private val fps: Int,
        private val clock: () -> Long,
        private val frame: (Int) -> IntArray,
    ) : FrameStackingSource {
        val accumulator = FrameAccumulator(mode, w, h)
        private val startedAt = clock()

        override val stackingProgress: StateFlow<Float?>
            get() = MutableStateFlow(accumulator.also { catchUp() }.convergence)

        override val stackingStatus: StateFlow<StackingStatus?>
            get() = MutableStateFlow(accumulator.also { catchUp() }.let { StackingStatus(it.framesOffered, it.convergence, it.converged) })

        private fun catchUp() {
            val due = ((clock() - startedAt) * fps / 1000).toInt()
            while (accumulator.framesOffered < due) accumulator.add(frame(accumulator.framesOffered))
        }

        override fun startAccumulating(mode: StackBlend, maxFrames: Int, aligner: FrameAligner?, focalToLongEdge: Float, maxShiftFraction: Float) = true

        override suspend fun stopAccumulating(): AccumulatedImage? = accumulator.result()
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun flat(v: Int) = IntArray(w * h) { rgb(v, v, v) }

    /** Fresh uniform noise every frame: an average, a lighten and a median that never settle. */
    private fun noisy(): (Int) -> IntArray {
        var seed = 987654321
        fun next(): Int {
            seed = seed * 1103515245 + 12345
            return (seed ushr 8) and 0xFF
        }
        return { IntArray(w * h) { rgb(next(), next(), next()) } }
    }

    /** A scene that keeps brightening for 256 frames: the average at N is never close to the average at N/2. */
    private fun ramp(): (Int) -> IntArray = { i -> flat(i.coerceAtMost(255)) }

    private fun TestScope.loop(mode: StackBlend, minimumMs: Long) =
        HoldLoop(mode, minimumMs, maxOf(Convergence.capMs(mode), minimumMs), { currentTime })

    private suspend fun HoldLoop.runOn(stream: FrameStackingSource, steadiness: () -> Steadiness = { Steadiness.STEADY }, progress: MutableList<Float> = mutableListOf()) =
        run(stream, steadiness) { progress += it }

    @Test
    fun `a stream that converges early stops at the minimum hold, not later`() = runTest {
        val stream = FakeStream(StackBlend.AVERAGE, 30, { currentTime }) { flat(100) }
        val outcome = loop(StackBlend.AVERAGE, 2_000).runOn(stream)
        assertEquals(HoldLoop.End.CONVERGED, outcome.end)
        assertTrue("elapsed ${outcome.elapsedMs}", outcome.elapsedMs in 2_000..2_100)
        assertTrue(stream.accumulator.converged)
    }

    @Test
    fun `a stream that never converges stops at the cap`() = runTest {
        val stream = FakeStream(StackBlend.AVERAGE, 30, { currentTime }, ramp())
        val outcome = loop(StackBlend.AVERAGE, 2_000).runOn(stream)
        assertEquals(HoldLoop.End.CAP, outcome.end)
        assertTrue("elapsed ${outcome.elapsedMs}", outcome.elapsedMs in 6_000..6_100)
        assertFalse(stream.accumulator.converged)
    }

    @Test
    fun `the cap is longer for trails and the crowd`() = runTest {
        for ((mode, cap) in listOf(StackBlend.LIGHTEN to 10_000L, StackBlend.MEDIAN to 15_000L)) {
            val start = currentTime
            val stream = FakeStream(mode, 20, { currentTime }, noisy())
            val outcome = loop(mode, 3_000).runOn(stream)
            // Fresh noise raises pixels (lighten) and moves every median forever; lighten does end sooner when it clips.
            assertTrue("$mode ended at ${outcome.elapsedMs}", outcome.elapsedMs in 3_000..cap + 100)
            assertTrue(currentTime - start <= cap + 100)
            if (mode == StackBlend.MEDIAN) assertEquals(HoldLoop.End.CAP, outcome.end)
        }
    }

    @Test
    fun `a 30 fps stream with a 4 s minimum holds at least 4 s (the early cutoff cannot return)`() = runTest {
        // The old hold expected 12 frames a second: 48 frames, reached after 1.6 s at 30 fps, ended it.
        for (mode in StackBlend.entries) {
            val stream = FakeStream(mode, 30, { currentTime }, noisy())
            val before = currentTime
            val outcome = loop(mode, 4_000).runOn(stream)
            assertTrue("$mode held ${outcome.elapsedMs} ms", outcome.elapsedMs >= 4_000)
            assertTrue("$mode offered ${stream.accumulator.framesOffered} frames", stream.accumulator.framesOffered > 4 * 12)
            assertTrue(currentTime - before >= 4_000)
        }
    }

    @Test
    fun `even a stream that converges at once is held for the minimum`() = runTest {
        val stream = FakeStream(StackBlend.MEDIAN, 30, { currentTime }) { flat(60) }
        val outcome = loop(StackBlend.MEDIAN, 4_000).runOn(stream)
        assertTrue(outcome.elapsedMs >= 4_000)
        assertEquals(HoldLoop.End.CONVERGED, outcome.end)
    }

    @Test
    fun `progress is the larger of time and convergence and reaches one`() = runTest {
        val stream = FakeStream(StackBlend.AVERAGE, 30, { currentTime }) { flat(100) }
        val progress = mutableListOf<Float>()
        loop(StackBlend.AVERAGE, 4_000).runOn(stream, progress = progress)
        assertTrue(progress.all { it in 0f..1f })
        // Converged after half a second: the ring jumps ahead of the clock...
        val atOneSecond = progress[(1_000 / MotionCapture.TICK_MS).toInt() - 1]
        assertEquals(1f, atOneSecond, 0f)
        assertEquals(1f, progress.last(), 0f)
    }

    @Test
    fun `sustained shake ends the hold as too shaky, and a steady phone does not`() = runTest {
        val stream = FakeStream(StackBlend.AVERAGE, 30, { currentTime }, noisy())
        val outcome = loop(StackBlend.AVERAGE, 4_000).runOn(stream, steadiness = { Steadiness.MOVING })
        assertEquals(HoldLoop.End.TOO_SHAKY, outcome.end)
        // More than 25 % of the 4 s minimum, found at the next tick.
        assertTrue("elapsed ${outcome.elapsedMs}", outcome.elapsedMs in 1_000..1_100)
    }

    @Test
    fun `the first second's frame rate is measured for the resolution fallback`() = runTest {
        val fast = loop(StackBlend.AVERAGE, 2_000).runOn(FakeStream(StackBlend.AVERAGE, 30, { currentTime }, noisy()))
        assertEquals(30f, fast.firstSecondFps!!, 1.5f)
        val slow = loop(StackBlend.AVERAGE, 2_000).runOn(FakeStream(StackBlend.AVERAGE, 6, { currentTime }, noisy()))
        assertTrue("slow ${slow.firstSecondFps}", slow.firstSecondFps!! < 8f)
        val shaky = loop(StackBlend.AVERAGE, 8_000).runOn(FakeStream(StackBlend.AVERAGE, 30, { currentTime }, noisy()), steadiness = { Steadiness.MOVING })
        assertNull("ended before a full second", shaky.firstSecondFps.takeIf { shaky.elapsedMs < 1_000 })
    }

    @Test
    fun `the hold loop works on the clock it is given`() = runTest {
        var ticks = 0
        val source = object : FrameStackingSource {
            override val stackingProgress: StateFlow<Float?> = MutableStateFlow(0.5f)
            override fun startAccumulating(mode: StackBlend, maxFrames: Int, aligner: FrameAligner?, focalToLongEdge: Float, maxShiftFraction: Float) = true
            override suspend fun stopAccumulating(): AccumulatedImage? = null
        }
        var now = 0L
        val outcome = HoldLoop(StackBlend.AVERAGE, 1_000, 1_000, { now }, { now += it; ticks++ }).run(source, { Steadiness.STEADY }) {}
        assertEquals(1_000L, outcome.elapsedMs)
        assertEquals(20, ticks)
        assertEquals(HoldLoop.End.CAP, outcome.end)
    }

    // ---- exposure planning

    @Test
    fun `bias lowers the exposure index by one stop in the lens's steps and clamps`() {
        assertEquals(-3, MotionExposure.biasIndex(0, 1f / 3f, -6..6))
        assertEquals(-2, MotionExposure.biasIndex(0, 0.5f, -6..6))
        assertEquals(-1, MotionExposure.biasIndex(0, 1f, -6..6))
        assertEquals(-6, MotionExposure.biasIndex(-5, 1f / 3f, -6..6))
        assertEquals(1, MotionExposure.biasIndex(4, 1f / 3f, -6..6))
        assertEquals(2, MotionExposure.biasIndex(2, 0f, -6..6))
        assertEquals(0, MotionExposure.biasIndex(0, 1f / 3f, 0..0))
    }

    private val shutterRange = 100_000L..1_000_000_000L
    private val isos = 50..3200

    @Test
    fun `trails use the longest shutter the frame interval allows`() {
        // A dim scene: 1/30 s at ISO 800 in the normal photo.
        val plan = MotionExposure.trailsPlan(33_000_000L, 800, 1f, 33_333_333L, shutterRange, isos)!!
        assertEquals(28_333_333L, plan.exposureNs)
        val wanted = 33_000_000.0 * 800 / 2
        assertEquals(wanted, plan.exposureNs.toDouble() * plan.iso, wanted * 0.01)
        assertTrue(plan.iso in isos)
    }

    @Test
    fun `trails in a bright scene stop at what the lowest ISO needs`() {
        val plan = MotionExposure.trailsPlan(2_000_000L, 100, 1f, 33_333_333L, shutterRange, isos)!!
        // Half of 2 ms at ISO 100 is 1 ms at ISO 100 = 2 ms at ISO 50: nothing is gained by a longer shutter.
        assertEquals(2_000_000L, plan.exposureNs)
        assertEquals(50, plan.iso)
    }

    @Test
    fun `trails stay inside the sensor ranges and refuse nonsense`() {
        val dark = MotionExposure.trailsPlan(500_000_000L, 3200, 1f, 33_333_333L, shutterRange, isos)!!
        assertTrue(dark.exposureNs <= 33_333_333L - MotionExposure.FRAME_INTERVAL_MARGIN_NS)
        assertEquals(3200, dark.iso)
        assertNull(MotionExposure.trailsPlan(0L, 100, 1f, 33_333_333L, shutterRange, isos))
        assertNull(MotionExposure.trailsPlan(1_000_000L, 0, 1f, 33_333_333L, shutterRange, isos))
        assertNull(MotionExposure.trailsPlan(1_000_000L, 100, 1f, 0L, shutterRange, isos))
    }

    // ---- gyro alignment over a long hold

    @Test
    fun `alignment covers a 15 s hold although the gyro history is 8 s`() {
        val rate = 0.01f // rad/s about x
        val historyNs = 8_000_000_000L
        var nowNs = 0L
        val between = { from: Long, to: Long ->
            if (minOf(from, to) < nowNs - historyNs) null else AngularDelta(rate * (to - from) / 1e9f, 0f, 0f)
        }
        val aligner = ChainedAligner(0L, { nowNs }, between)
        var t = 0L
        while (t < 15_000_000_000L) {
            t += 100_000_000L
            nowNs = t
            val delta = aligner.angularDeltaSinceStart(t)
            assertNotNull("lost the gyro at ${t / 1_000_000} ms", delta)
            assertTrue("at $t: ${delta!!.x}", abs(delta.x - rate * t / 1e9f) < 1e-4f)
        }
        // Without re-basing, the start would have fallen out of the history.
        nowNs = 15_000_000_000L
        assertNull(between(0L, nowNs))
    }

    @Test
    fun `alignment falls back to processing time when frame timestamps are on another clock`() {
        var nowNs = 5_000_000_000L
        val aligner = ChainedAligner(1_000_000_000L, { nowNs }, { from, to -> AngularDelta((to - from) / 1e9f, 0f, 0f) })
        // The frame says 99 s (another clock): the delta runs from the start to "now" (4 s).
        assertEquals(4f, aligner.angularDeltaSinceStart(99_000_000_000L)!!.x, 1e-4f)
        nowNs = 6_000_000_000L
        assertEquals(5f, aligner.angularDeltaSinceStart(99_000_000_000L)!!.x, 1e-4f)
    }

    @Test
    fun `no gyro means no alignment`() {
        val aligner = ChainedAligner(0L, { 1_000L }, { _, _ -> null })
        assertNull(aligner.angularDeltaSinceStart(1_000L))
    }

    @Test
    fun `suffixes name the three stacks`() {
        assertEquals("_silk", MotionCapture.suffixFor(StackBlend.AVERAGE))
        assertEquals("_trails", MotionCapture.suffixFor(StackBlend.LIGHTEN))
        assertEquals("_clear", MotionCapture.suffixFor(StackBlend.MEDIAN))
    }
}

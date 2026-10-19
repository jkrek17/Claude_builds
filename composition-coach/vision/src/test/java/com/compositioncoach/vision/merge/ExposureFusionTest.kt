package com.compositioncoach.vision.merge

import com.compositioncoach.vision.PixelShift
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class ExposureFusionTest {

    private val w = 320
    private val h = 240

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun lum(c: Int) = (red(c) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3

    /**
     * Scene radiance (linear, 1.0 = the 0 EV clipping point at gamma 2.2). Top half: a bright sky (1.6, above
     * clipping at 0 EV) with a cloud checker of +-20 %. Bottom half: a mid-tone textured ground with a dark
     * corner (bottom-left 40 x 30) whose texture is only 0.002..0.004 in radiance.
     */
    private fun radiance(x: Int, y: Int): Double {
        val checker = if (((x / 16) + (y / 16)) % 2 == 0) 1.0 else 0.0
        return when {
            y < h / 2 -> 1.6 * (0.8 + 0.4 * checker)
            x < 120 && y >= h - 80 -> 0.006 + 0.006 * checker
            else -> 0.12 + 0.12 * checker
        }
    }

    /** One simulated exposure: [stops] relative to 0 EV, gamma 2.2, 8-bit clip. */
    private fun exposure(stops: Int, shiftX: Int = 0, shiftY: Int = 0, scene: (Int, Int) -> Double = ::radiance): IntArray {
        val gain = 2.0.pow(stops)
        return IntArray(w * h) { i ->
            val x = (i % w + shiftX).coerceIn(0, w - 1)
            val y = (i / w + shiftY).coerceIn(0, h - 1)
            grey(((gain * scene(x, y)).coerceIn(0.0, 1.0).pow(1 / 2.2) * 255.0).toInt())
        }
    }

    private fun region(px: IntArray, xs: IntRange, ys: IntRange): List<Int> = ys.flatMap { y -> xs.map { x -> lum(px[y * w + x]) } }

    private fun mean(v: List<Int>) = v.sum().toDouble() / v.size

    private fun std(v: List<Int>): Double {
        val m = mean(v)
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    private val sky = (16 until 304) to (16 until 104)
    private val corner = (16 until 100) to ((h - 64) until (h - 16))
    private val ground = (150 until 300) to (140 until 220)

    @Test
    fun `weights sum to one at every cell`() {
        val random = Random(7)
        val guides = List(3) { IntArray(20 * 15) { (0xFF shl 24) or random.nextInt(0x1000000) } }
        val weights = ExposureFusion.weights(guides, 20, 15)
        for (i in 0 until 20 * 15) assertEquals(1f, weights.sumOf { it[i].toDouble() }.toFloat(), 1e-5f)
    }

    @Test
    fun `a clipped frame gets almost no weight where another frame is well exposed`() {
        val frames = listOf(-2, 0, 2).map { ExposureFusion.boxGuide(exposure(it), w, h).pixels }
        val gw = (w + 3) / 4
        val gh = (h + 3) / 4
        val weights = ExposureFusion.weights(frames, gw, gh)
        // A sky cell (row 4 of 30, col 10 of 40): -2 EV is mid-tone, 0 EV and +2 EV are clipped white.
        val i = 4 * gw + 10
        assertTrue("weights ${weights.map { it[i] }}", weights[0][i] > 0.9f)
    }

    @Test
    fun `a flat field fuses to itself`() {
        for (colour in listOf(grey(128), (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50)) {
            val frame = IntArray(w * h) { colour }
            val fused = ExposureFusion.fuse(listOf(frame, frame.copyOf(), frame.copyOf()), w, h)
            for (px in fused) {
                assertTrue(abs(red(px) - red(colour)) <= 1)
                assertTrue(abs(((px shr 8) and 0xFF) - ((colour shr 8) and 0xFF)) <= 1)
                assertTrue(abs((px and 0xFF) - (colour and 0xFF)) <= 1)
            }
        }
    }

    @Test
    fun `clipped sky detail is recovered from the dark frame`() {
        val minus = exposure(-2)
        val zero = exposure(0)
        val plus = exposure(2)
        assertEquals("the 0 EV sky is clipped", 0.0, std(region(zero, sky.first, sky.second)), 0.01)
        val fused = ExposureFusion.fuse(listOf(zero, minus, plus), w, h)
        val skyStd = std(region(fused, sky.first, sky.second))
        val reference = std(region(minus, sky.first, sky.second))
        assertTrue("fused sky std $skyStd vs -2 EV $reference", skyStd > 0.7 * reference)
        val skyMean = mean(region(fused, sky.first, sky.second))
        assertTrue("fused sky mean $skyMean is well below clipping", skyMean < 215)
    }

    @Test
    fun `a dark corner is recovered from the bright frame`() {
        val minus = exposure(-2)
        val zero = exposure(0)
        val plus = exposure(2)
        val fused = ExposureFusion.fuse(listOf(minus, zero, plus), w, h)
        val before = mean(region(zero, corner.first, corner.second))
        val after = mean(region(fused, corner.first, corner.second))
        val detail = std(region(fused, corner.first, corner.second))
        assertTrue("corner mean $before -> $after", after > before + 15)
        assertTrue("corner keeps its texture (std $detail vs 0 EV ${std(region(zero, corner.first, corner.second))})", detail > 1.5 * std(region(zero, corner.first, corner.second)))
    }

    @Test
    fun `well exposed ground is left alone`() {
        val zero = exposure(0)
        val fused = ExposureFusion.fuse(listOf(exposure(-2), zero, exposure(2)), w, h)
        val error = region(fused, ground.first, ground.second).zip(region(zero, ground.first, ground.second)) { a, b -> abs(a - b) }.average()
        assertTrue("ground drifted by $error levels", error < 20)
    }

    @Test
    fun `strips blend to the same pixels as the whole image`() {
        val frames = listOf(exposure(-2), exposure(0), exposure(2))
        val shifts = listOf(PixelShift(3, -2), PixelShift.NONE, PixelShift(-4, 5))
        val whole = ExposureFusion.fuse(frames, w, h, shifts)

        val boxes = frames.map { ExposureFusion.boxGuide(it, w, h) }
        val gw = boxes[0].width
        val gh = boxes[0].height
        val aligned = boxes.mapIndexed { i, g -> ExposureFusion.shiftArgb(g.pixels, gw, gh, PixelShift(shifts[i].dx / 4, shifts[i].dy / 4)) }
        val weights = ExposureFusion.weights(aligned, gw, gh)
        val correction = ExposureFusion.correction(aligned, weights, ExposureFusion.pyramidBlend(aligned, weights, gw, gh), gw, gh)
        val stripped = IntArray(w * h)
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(37, h - y0)
            // Hold only the rows each shifted sample can need, as the real decoder does.
            val strips = frames.mapIndexed { i, px ->
                val top = (y0 + shifts[i].dy).coerceIn(0, h - 1)
                val bottom = (y0 + rows + shifts[i].dy).coerceIn(top + 1, h)
                ExposureFusion.Strip(px.copyOfRange(top * w, bottom * w), top, bottom - top, shifts[i])
            }
            val out = IntArray(w * rows)
            ExposureFusion.blendStrip(strips, w, h, y0, rows, weights, correction, gw, gh, out)
            System.arraycopy(out, 0, stripped, y0 * w, w * rows)
            y0 += rows
        }
        assertArrayEquals(whole, stripped)
    }

    @Test
    fun `alignment recovers a known shift between exposures`() {
        val random = Random(3)
        val coarse = Array(12) { DoubleArray(16) { random.nextDouble() } }
        fun scene(x: Int, y: Int): Double {
            val fx = (x / 6.0).coerceIn(0.0, 14.99)
            val fy = (y / 6.0).coerceIn(0.0, 10.99)
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val tx = fx - x0
            val ty = fy - y0
            return 0.02 + 0.5 * ((coarse[y0][x0] * (1 - tx) + coarse[y0][x0 + 1] * tx) * (1 - ty) +
                (coarse[y0 + 1][x0] * (1 - tx) + coarse[y0 + 1][x0 + 1] * tx) * ty)
        }
        val gw = 100
        val gh = 70
        fun guide(stops: Int, sx: Int, sy: Int) = FloatArray(gw * gh) { i ->
            val x = (i % gw - sx).coerceIn(0, gw - 1)
            val y = (i / gw - sy).coerceIn(0, gh - 1)
            ((2.0.pow(stops) * scene(x, y)).coerceIn(0.0, 1.0).pow(1 / 2.2)).toFloat()
        }
        val reference = guide(0, 0, 0)
        for ((sx, sy) in listOf(3 to -2, -5 to 4, 0 to 0, 8 to 8)) {
            for (stops in listOf(-2, 2)) {
                val found = ExposureFusion.alignmentShift(reference, guide(stops, sx, sy), gw, gh)
                assertEquals("shift ($sx, $sy) at $stops EV", PixelShift(sx, sy), found)
            }
        }
        // A gyro estimate that is a few pixels off is corrected.
        assertEquals(PixelShift(3, -2), ExposureFusion.alignmentShift(reference, guide(2, 3, -2), gw, gh, initial = PixelShift(1, -4)))
    }

    @Test
    fun `alignment keeps the initial shift on a featureless guide`() {
        val flat = FloatArray(60 * 60) { 0.5f }
        assertEquals(PixelShift(2, 1), ExposureFusion.alignmentShift(flat, flat, 60, 60, PixelShift(2, 1)))
    }
}

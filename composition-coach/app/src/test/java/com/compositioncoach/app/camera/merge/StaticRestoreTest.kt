package com.compositioncoach.app.camera.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class StaticRestoreTest {

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun value(c: Int) = c and 0xFF

    // ---- the static mask

    @Test
    fun `zero variance everywhere is fully static`() {
        val weights = StaticRestore.staticWeights(FloatArray(8 * 6), 8, 6)
        assertTrue(weights.all { it > 0.999f })
        assertEquals(1f, StaticRestore.staticFraction(weights), 1e-4f)
    }

    @Test
    fun `high variance is not static, with a feather between`() {
        val gw = 20
        val gh = 12
        // A moving patch (stddev 0.2) in the middle of an otherwise still scene.
        val std = FloatArray(gw * gh) { i -> if (i % gw in 8..11 && i / gw in 4..7) 0.2f else 0f }
        val weights = StaticRestore.staticWeights(std, gw, gh)
        assertEquals(0f, weights[5 * gw + 9], 1e-4f)
        assertEquals(1f, weights[0], 1e-4f)
        assertEquals(1f, weights[gw * gh - 1], 1e-4f)
        // The feather: cells next to the patch are neither fully on nor fully off.
        val edge = weights[5 * gw + 6]
        assertTrue("edge $edge", edge > 0.01f && edge < 0.99f)
        // Detail stops short of the moving region: the weights decrease towards it.
        assertTrue(weights[5 * gw + 4] > weights[5 * gw + 6])
    }

    @Test
    fun `sensor noise level variance still counts as static`() {
        val weights = StaticRestore.staticWeights(FloatArray(16 * 12) { 0.012f }, 16, 12)
        assertTrue(weights.all { it > 0.99f })
    }

    @Test
    fun `most of the picture moving leaves nothing worth restoring`() {
        val weights = StaticRestore.staticWeights(FloatArray(16 * 12) { 0.3f }, 16, 12)
        assertTrue(StaticRestore.staticFraction(weights) < StaticRestore.MIN_STATIC_FRACTION)
    }

    // ---- the blend: the still where variance is zero, the stack elsewhere

    private val outW = 64
    private val outH = 48
    private val stackW = 32
    private val stackH = 24
    private val gs = 4

    /** A fine 2-px checkerboard (above the stack's resolution) plus a soft gradient. */
    private val companion = IntArray(outW * outH) { i ->
        val x = i % outW
        val y = i / outW
        grey((100 + x + if ((x + y) % 2 == 0) 40 else -40).coerceIn(0, 255))
    }

    /** The stack as the analysis stream would see this scene: the companion averaged 2x2. */
    private val stack = IntArray(stackW * stackH) { i ->
        val x = (i % stackW) * 2
        val y = (i / stackW) * 2
        grey((value(companion[y * outW + x]) + value(companion[y * outW + x + 1]) + value(companion[(y + 1) * outW + x]) + value(companion[(y + 1) * outW + x + 1])) / 4)
    }

    private val gw = (stackW + gs - 1) / gs
    private val gh = (stackH + gs - 1) / gs

    private fun blend(weights: FloatArray) = StaticRestore.blend(stack, stackW, stackH, weights, gw, gh, gs, companion, outW, outH)

    private fun meanError(a: IntArray, b: IntArray, xs: IntRange): Double {
        var sum = 0.0
        var n = 0
        for (y in 0 until outH) for (x in xs) {
            sum += abs(value(a[y * outW + x]) - value(b[y * outW + x]))
            n++
        }
        return sum / n
    }

    @Test
    fun `where variance is zero the companion's detail returns, where it is high the upscaled stack stays`() {
        // Left half static (weight 1), right half moving (weight 0), away from the feather in the middle.
        val weights = FloatArray(gw * gh) { i -> if (i % gw < gw / 2) 1f else 0f }
        val none = blend(FloatArray(gw * gh))
        val restored = blend(weights)

        val left = 2..20
        val right = 44..61
        val upscaleError = meanError(none, companion, left)
        val restoredError = meanError(restored, companion, left)
        assertTrue("upscale $upscaleError restored $restoredError", upscaleError > 20.0 && restoredError < upscaleError * 0.35)
        // The moving side is untouched: identical to the plain upscaled stack.
        for (y in 0 until outH) for (x in right) assertEquals(none[y * outW + x], restored[y * outW + x])
    }

    @Test
    fun `a full-weight blend of a stack that is the companion at low resolution reproduces the companion`() {
        // Flat scene: nothing to restore, and nothing may be invented.
        val flatCompanion = IntArray(outW * outH) { grey(90) }
        val flatStack = IntArray(stackW * stackH) { grey(90) }
        val out = StaticRestore.blend(flatStack, stackW, stackH, FloatArray(gw * gh) { 1f }, gw, gh, gs, flatCompanion, outW, outH)
        assertTrue(out.all { it == grey(90) })
    }

    @Test
    fun `the stack's tone wins over the companion's where they differ`() {
        // The companion is 30 levels brighter (another pipeline): only its detail is added, not its tone.
        val bright = IntArray(outW * outH) { grey((value(companion[it]) + 30).coerceAtMost(255)) }
        val out = StaticRestore.blend(stack, stackW, stackH, FloatArray(gw * gh) { 1f }, gw, gh, gs, bright, outW, outH)
        val ref = blend(FloatArray(gw * gh) { 1f })
        var diff = 0.0
        for (i in out.indices) diff += abs(value(out[i]) - value(ref[i]))
        assertTrue("tone leaked: ${diff / out.size}", diff / out.size < 3.0)
    }

    @Test
    fun `strip blending equals whole-image blending`() {
        val weights = FloatArray(gw * gh) { i -> if (i % gw < 5) 1f else 0.3f }
        val whole = blend(weights)
        val alignment = StaticRestore.Alignment(0f, 0f, 0f)
        val radius = StaticRestore.detailRadius(outW.toFloat() / stackW)
        val stripped = IntArray(outW * outH)
        var y0 = 0
        val rowsPer = 10
        while (y0 < outH) {
            val rows = minOf(rowsPer, outH - y0)
            val top = (y0 - radius).coerceAtLeast(0)
            val bottom = (y0 + rows + radius).coerceAtMost(outH)
            val strip = companion.copyOfRange(top * outW, bottom * outW)
            val out = IntArray(outW * rows)
            StaticRestore.blendStrip(stack, stackW, stackH, weights, gw, gh, gs, strip, top, bottom - top, outW, outH, alignment, radius, y0, rows, out)
            out.copyInto(stripped, y0 * outW)
            y0 += rows
        }
        // Rows next to a strip boundary see the same companion rows, so the result is identical.
        for (i in whole.indices) assertEquals("pixel $i", whole[i], stripped[i])
    }

    // ---- alignment

    private val agw = 64
    private val agh = 48

    private fun field(x: Float, y: Float): Float = 0.5f + 0.25f * sin(x * 0.31f) * sin(y * 0.23f) + 0.15f * sin(x * 0.11f + y * 0.17f)

    private fun guide(shiftX: Float, shiftY: Float) = FloatArray(agw * agh) { i -> field((i % agw) - shiftX, (i / agw) - shiftY) }

    @Test
    fun `block matching recovers a known whole-pixel shift`() {
        // stack(x, y) = companion(x - 3, y + 2), so stack(x + 3, y - 2) = companion(x, y).
        val companionGuide = guide(0f, 0f)
        val stackGuide = guide(3f, -2f)
        val result = StaticRestore.align(stackGuide, companionGuide, FloatArray(agw * agh) { 1f }, agw, agh)
        assertNotNull(result)
        assertEquals(3f, result!!.dx, 0.1f)
        assertEquals(-2f, result.dy, 0.1f)
        assertTrue(StaticRestore.matches(result))
    }

    @Test
    fun `block matching recovers a sub-pixel shift`() {
        val result = StaticRestore.align(guide(2.5f, 1.5f), guide(0f, 0f), FloatArray(agw * agh) { 1f }, agw, agh)!!
        assertEquals(2.5f, result.dx, 0.25f)
        assertEquals(1.5f, result.dy, 0.25f)
    }

    @Test
    fun `block matching ignores brightness differences between the sources`() {
        val brighter = guide(1f, 0f).map { it + 0.15f }.toFloatArray()
        val result = StaticRestore.align(brighter, guide(0f, 0f), FloatArray(agw * agh) { 1f }, agw, agh)!!
        assertEquals(1f, result.dx, 0.1f)
        assertTrue(StaticRestore.matches(result))
    }

    @Test
    fun `unrelated pictures do not match and a missing static area gives no alignment`() {
        var seed = 7
        val noise = FloatArray(agw * agh) { seed = seed * 1103515245 + 12345; ((seed ushr 8) and 0xFF) / 255f }
        val result = StaticRestore.align(noise, guide(0f, 0f), FloatArray(agw * agh) { 1f }, agw, agh)
        assertFalse(StaticRestore.matches(result))
        assertNull(StaticRestore.align(guide(0f, 0f), guide(0f, 0f), FloatArray(agw * agh), agw, agh))
        assertFalse(StaticRestore.matches(null))
    }

    // ---- helpers

    @Test
    fun `rotation by 90 degrees clockwise moves pixels and four turns come home`() {
        // 3 wide, 2 tall:  1 2 3 / 4 5 6  ->  4 1 / 5 2 / 6 3
        val px = intArrayOf(1, 2, 3, 4, 5, 6)
        val (r90, w, h) = StaticRestore.rotate(px, 3, 2, 90)
        assertEquals(2, w)
        assertEquals(3, h)
        assertEquals(listOf(4, 1, 5, 2, 6, 3), r90.toList())
        assertEquals(listOf(6, 5, 4, 3, 2, 1), StaticRestore.rotate(px, 3, 2, 180).first.toList())
        assertEquals(listOf(3, 6, 2, 5, 1, 4), StaticRestore.rotate(px, 3, 2, 270).first.toList())
        var cur = px
        var cw = 3
        var ch = 2
        repeat(4) { val r = StaticRestore.rotate(cur, cw, ch, 90); cur = r.first; cw = r.second; ch = r.third }
        assertEquals(px.toList(), cur.toList())
        assertEquals(3, cw)
        assertEquals(2, ch)
    }

    @Test
    fun `the variance grid follows the same rotation as the pixels`() {
        val grid = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val (r, w, h) = StaticRestore.rotateGrid(grid, 3, 2, 90)
        assertEquals(2, w)
        assertEquals(3, h)
        assertEquals(listOf(4f, 1f, 5f, 2f, 6f, 3f), r.toList())
        assertTrue(StaticRestore.rotateGrid(grid, 3, 2, 0).first === grid)
    }

    @Test
    fun `low-pass keeps a flat image and spreads an impulse`() {
        val flat = IntArray(10 * 8) { grey(77) }
        assertTrue(StaticRestore.lowPass(flat, 10, 8, 2).all { it == grey(77) })
        val impulse = IntArray(9 * 9) { grey(0) }.also { it[4 * 9 + 4] = grey(90) }
        val out = StaticRestore.lowPass(impulse, 9, 9, 1)
        assertEquals(10, value(out[4 * 9 + 4]))
        assertEquals(10, value(out[3 * 9 + 3]))
        assertEquals(0, value(out[2 * 9 + 2]))
    }

    @Test
    fun `resampling luma averages the area`() {
        val px = IntArray(4 * 4) { i -> if (i % 4 < 2) grey(0) else grey(255) }
        val g = StaticRestore.resampleLuma(px, 4, 4, 2, 2)
        assertEquals(listOf(0f, 1f, 0f, 1f), g.map { Math.round(it * 100) / 100f })
    }

    @Test
    fun `the detail radius follows the scale between stack and still`() {
        assertEquals(1, StaticRestore.detailRadius(1.2f))
        assertEquals(1, StaticRestore.detailRadius(2.1f))
        assertEquals(1, StaticRestore.detailRadius(3.1f))
        assertEquals(2, StaticRestore.detailRadius(5f))
    }
}

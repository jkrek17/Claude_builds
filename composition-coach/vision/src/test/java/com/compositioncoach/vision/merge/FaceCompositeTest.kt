package com.compositioncoach.vision.merge

import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.vision.PixelShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FaceCompositeTest {

    private val w = 200
    private val h = 100

    /** Face A: x 20..80, face B: x 120..180, both y 20..80 (pixels), as normalized boxes. */
    private val boxA = NormalizedRect(0.10f, 0.20f, 0.40f, 0.80f)
    private val boxB = NormalizedRect(0.60f, 0.20f, 0.90f, 0.80f)

    /** Flat grey scene with a low-contrast textured "face" in each box; [soft] faces are the same texture averaged over 7x7. */
    private fun scene(softA: Boolean, softB: Boolean, seed: Int = 1): IntArray {
        val base = IntArray(w * h) { gray(128) }
        paint(base, boxA, softA, seed)
        paint(base, boxB, softB, seed + 100)
        return base
    }

    private fun paint(pixels: IntArray, box: NormalizedRect, soft: Boolean, seed: Int) {
        val x0 = (box.left * w).toInt()
        val x1 = (box.right * w).toInt()
        val y0 = (box.top * h).toInt()
        val y1 = (box.bottom * h).toInt()
        val random = Random(seed)
        val texture = Array(y1 - y0) { IntArray(x1 - x0) { 128 + random.nextInt(-14, 15) } }
        for (y in y0 until y1) for (x in x0 until x1) {
            var v = texture[y - y0][x - x0]
            if (soft) {
                var sum = 0
                var n = 0
                for (dy in -3..3) for (dx in -3..3) {
                    val ty = (y - y0 + dy).coerceIn(0, y1 - y0 - 1)
                    val tx = (x - x0 + dx).coerceIn(0, x1 - x0 - 1)
                    sum += texture[ty][tx]
                    n++
                }
                v = sum / n
            }
            pixels[y * w + x] = gray(v)
        }
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun luma(px: IntArray) = FloatArray(px.size) { FaceComposite.luma(px[it]) }

    private fun sharp(px: IntArray, box: NormalizedRect) = FaceComposite.sharpness(luma(px), w, h, box)

    private fun face(box: NormalizedRect, eyes: Float? = 0.9f) = FaceComposite.Face(box, eyes)

    @Test
    fun `two faces sharp in different frames yield both sharp`() {
        val frame0 = scene(softA = false, softB = true)
        val frame1 = scene(softA = true, softB = false)
        val faces = listOf(listOf(face(boxA), face(boxB)), listOf(face(boxA), face(boxB)))

        val (out, plan) = FaceComposite.composite(listOf(frame0, frame1), w, h, faces)

        assertEquals(1, plan.replacements.size)
        assertEquals(0, plan.rejected)
        val sharpA = sharp(frame0, boxA)
        val sharpB = sharp(frame1, boxB)
        assertTrue(sharpA > 0f && sharpB > 0f)
        assertTrue("A stays sharp", sharp(out, boxA) > sharpA * 0.9f)
        assertTrue("B becomes sharp", sharp(out, boxB) > sharpB * 0.9f)
        assertTrue("B was soft in the base", sharp(frame0, boxB) < sharpB * 0.2f)
    }

    @Test
    fun `a face with closed eyes in the base is replaced`() {
        // Same sharpness everywhere; only the eye state differs, and frame 1's face has a different texture seed
        // so a replacement is visible in the pixels.
        val frame0 = scene(softA = false, softB = false, seed = 1)
        val frame1 = scene(softA = false, softB = false, seed = 1)
        paint(frame1, boxA, false, seed = 1)
        val faces = listOf(
            listOf(face(boxA, eyes = 0.1f), face(boxB, eyes = 0.95f)),
            listOf(face(boxA, eyes = 0.95f), face(boxB, eyes = 0.95f)),
        )
        // Mark frame 1's face A so the paste can be detected: slightly brighter texture.
        val x0 = (boxA.left * w).toInt()
        val x1 = (boxA.right * w).toInt()
        val y0 = (boxA.top * h).toInt()
        val y1 = (boxA.bottom * h).toInt()
        for (y in y0 until y1) for (x in x0 until x1) frame1[y * w + x] = gray(((frame1[y * w + x] and 0xFF) + 6).coerceAtMost(255))

        val (out, plan) = FaceComposite.composite(listOf(frame0, frame1), w, h, faces, base = 0)

        assertEquals(1, plan.replacements.size)
        assertEquals(1, plan.replacements[0].source)
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        assertEquals("the centre of face A comes from frame 1", frame1[cy * w + cx], out[cy * w + cx])
        val bx = ((boxB.left + boxB.right) / 2 * w).toInt()
        assertEquals("face B is untouched", frame0[cy * w + bx], out[cy * w + bx])
    }

    @Test
    fun `a large alignment residual keeps the base`() {
        val frame0 = scene(softA = false, softB = false, seed = 1)
        // Frame 1's face A is a different, much brighter pattern (the person turned): residual is far over the limit.
        val frame1 = scene(softA = false, softB = false, seed = 1)
        val x0 = (boxA.left * w).toInt()
        val x1 = (boxA.right * w).toInt()
        val y0 = (boxA.top * h).toInt()
        val y1 = (boxA.bottom * h).toInt()
        val random = Random(9)
        for (y in y0 until y1) for (x in x0 until x1) frame1[y * w + x] = gray(if (random.nextBoolean()) 230 else 40)
        val faces = listOf(
            listOf(face(boxA, eyes = 0.1f), face(boxB)),
            listOf(face(boxA, eyes = 0.95f), face(boxB)),
        )

        val (out, plan) = FaceComposite.composite(listOf(frame0, frame1), w, h, faces, base = 0)

        assertTrue(plan.replacements.isEmpty())
        assertEquals(1, plan.rejected)
        assertTrue(frame0.contentEquals(out))
    }

    @Test
    fun `block matching recovers a known shift`() {
        val frame0 = scene(softA = false, softB = false, seed = 3)
        val shifted = IntArray(w * h) { i ->
            // frame1(x, y) = frame0(x - 5, y + 3), so frame0 at (x, y) is found in frame1 at (x + 5, y - 3).
            val x = (i % w - 5).coerceIn(0, w - 1)
            val y = (i / w + 3).coerceIn(0, h - 1)
            frame0[y * w + x]
        }
        val box = FaceComposite.growBox(boxA, w, h)
        val alignment = FaceComposite.alignBox(luma(frame0), luma(shifted), w, h, box)
        assertEquals(5, alignment.dx)
        assertEquals(-3, alignment.dy)
        assertTrue(alignment.residual < 0.01f)
    }

    @Test
    fun `base frame has the most open eyes then the sharpest`() {
        val faces = listOf(
            listOf(face(boxA, 0.9f), face(boxB, 0.1f)),
            listOf(face(boxA, 0.9f), face(boxB, 0.9f)),
            listOf(face(boxA, 0.9f), face(boxB, 0.9f)),
        )
        assertEquals(2, FaceComposite.chooseBase(faces, listOf(9f, 1f, 2f)))
        assertEquals(0, FaceComposite.chooseBase(listOf(faces[1], faces[2]), listOf(5f, 5f)))
    }

    @Test
    fun `faces are matched across frames by centre distance`() {
        val shiftedA = NormalizedRect(0.12f, 0.21f, 0.42f, 0.81f)
        val shiftedB = NormalizedRect(0.58f, 0.19f, 0.88f, 0.79f)
        // The second frame lists the faces in the opposite order.
        val frames = listOf(listOf(face(boxA), face(boxB)), listOf(face(shiftedB), face(shiftedA)))
        val tracks = FaceComposite.matchFaces(frames, 0, w.toFloat() / h)
        assertEquals(shiftedA, tracks[0][1]!!.bounds)
        assertEquals(shiftedB, tracks[1][1]!!.bounds)
        // A detection far from every base face matches nothing.
        val far = FaceComposite.matchFaces(listOf(listOf(face(boxA)), listOf(face(boxB))), 0, w.toFloat() / h)
        assertEquals(null, far[0][1])
    }

    @Test
    fun `best frame needs open eyes and a clear sharpness gain`() {
        val tracks = listOf(
            arrayOf<FaceComposite.Face?>(face(boxA, 0.9f), face(boxA, 0.9f), face(boxA, 0.3f)),
            arrayOf<FaceComposite.Face?>(face(boxB, 0.9f), face(boxB, 0.9f), null),
        )
        // Track 0: frame 2 is sharpest but eyes closed, frame 1 is 5 % sharper (not enough); track 1: 50 % sharper.
        val sources = FaceComposite.chooseSources(
            tracks, 0,
            listOf(floatArrayOf(1f, 1.05f, 9f), floatArrayOf(1f, 1.5f, 0f)),
        )
        assertEquals(0, sources[0])
        assertEquals(1, sources[1])
    }

    @Test
    fun `box is grown by a quarter and the feather is eight percent of its width`() {
        val box = FaceComposite.growBox(NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f), 1000, 1000)
        assertEquals(375, box.left)
        assertEquals(625, box.right)
        assertEquals(250, box.width)
        assertEquals(20, FaceComposite.featherPx(box))
        assertEquals(0f, FaceComposite.featherWeight(box, 20, 374, 500), 0f)
        assertTrue(FaceComposite.featherWeight(box, 20, 376, 500) < 0.1f)
        assertEquals(1f, FaceComposite.featherWeight(box, 20, 500, 500), 0f)
        assertEquals(1f, FaceComposite.featherWeight(box, 20, 400, 500), 0f)
    }

    @Test
    fun `upright boxes map back to the stored buffer`() {
        val upright = NormalizedRect(0.1f, 0.2f, 0.3f, 0.5f)
        // A buffer rotated 90 degrees clockwise to be upright: buffer (bx, by) -> upright (1 - by, bx).
        val buffer = FaceComposite.uprightRectToBuffer(upright, 90)
        assertEquals(0.2f, buffer.left, 1e-6f)
        assertEquals(0.7f, buffer.top, 1e-6f)
        assertEquals(0.5f, buffer.right, 1e-6f)
        assertEquals(0.9f, buffer.bottom, 1e-6f)
        for (degrees in listOf(0, 90, 180, 270)) {
            // Forward-rotate the buffer box by hand and check it round-trips.
            val back = FaceComposite.uprightRectToBuffer(forward(buffer, degrees), degrees)
            assertEquals(buffer.left, back.left, 1e-6f)
            assertEquals(buffer.bottom, back.bottom, 1e-6f)
        }
    }

    private fun forward(r: NormalizedRect, degrees: Int): NormalizedRect {
        fun p(x: Float, y: Float) = when (degrees) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
        val a = p(r.left, r.top)
        val b = p(r.right, r.bottom)
        return NormalizedRect(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    @Test
    fun `gyro shift seeds the block match and the paste is source safe`() {
        val box = FaceComposite.PixelBox(0, 0, 50, 50)
        val safe = FaceComposite.sourceSafeBox(box, dx = 6, dy = -4, width = 100, height = 100)!!
        assertEquals(0, safe.left)
        assertEquals(4, safe.top)
        val frame = scene(softA = false, softB = false)
        val faces = listOf(listOf(face(boxA)), listOf(face(boxA)))
        val plan = FaceComposite.plan(listOf(luma(frame), luma(frame)), w, h, faces, listOf(PixelShift.NONE, PixelShift(2, 1)))
        assertTrue(plan.replacements.isEmpty())
    }
}

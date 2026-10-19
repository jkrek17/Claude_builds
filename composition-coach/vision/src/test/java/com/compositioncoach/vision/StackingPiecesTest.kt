package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** YuvConverter, StackingSession and FaceClassificationGate: the pure pieces around the live stack. */
class StackingPiecesTest {

    private fun planes(w: Int, h: Int, luma: (Int, Int) -> Int, cb: Int = 128, cr: Int = 128): Triple<YuvConverter.Plane, YuvConverter.Plane, YuvConverter.Plane> {
        val y = ByteArray(w * h) { i -> luma(i % w, i / w).toByte() }
        val cw = w / 2
        val ch = h / 2
        val u = ByteArray(cw * ch) { cb.toByte() }
        val v = ByteArray(cw * ch) { cr.toByte() }
        return Triple(YuvConverter.Plane(y, w, 1), YuvConverter.Plane(u, cw, 1), YuvConverter.Plane(v, cw, 1))
    }

    @Test
    fun `grey YUV converts to equal RGB channels`() {
        val (y, u, v) = planes(4, 4, { _, _ -> 200 })
        val out = YuvConverter.toUprightArgb(y, u, v, FrameCoordinateMapper(4, 4, 0, 0, 0, 4, 4, false))
        assertTrue(out.all { it == (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200 })
    }

    @Test
    fun `strong red chroma gives a red pixel`() {
        val (y, u, v) = planes(2, 2, { _, _ -> 76 }, cb = 85, cr = 255)
        val c = YuvConverter.toUprightArgb(y, u, v, FrameCoordinateMapper(2, 2, 0, 0, 0, 2, 2, false))[0]
        assertTrue(((c shr 16) and 0xFF) > 240)
        assertTrue(((c shr 8) and 0xFF) < 20)
    }

    @Test
    fun `conversion applies the mapper's rotation`() {
        // A 4x2 landscape buffer with a bright top-left pixel, rotated 90 clockwise into a 2x4 portrait frame:
        // the top-left of the buffer becomes the top-right of the upright image.
        val (y, u, v) = planes(4, 2, { x, yy -> if (x == 0 && yy == 0) 250 else 10 })
        val mapper = FrameCoordinateMapper(4, 2, 90, 0, 0, 4, 2, false)
        val out = YuvConverter.toUprightArgb(y, u, v, mapper)
        assertEquals(2, mapper.outputWidth)
        assertEquals(250, out[1] and 0xFF)
        assertEquals(10, out[0] and 0xFF)
    }

    @Test
    fun `stacking session counts frames, stops at the limit and aligns with the gyro`() {
        val (y, u, v) = planes(8, 8, { x, yy -> if (x == 4 && yy == 4) 250 else 0 })
        var delta = AngularDelta.ZERO
        val session = StackingSession(StackBlend.LIGHTEN, 2, { delta }, 1f, 0.5f, deviceRotationAtStart = 0)
        val mapper = FrameCoordinateMapper(8, 8, 0, 0, 0, 8, 8, false)
        session.offer(y, u, v, mapper, false, 0L)
        // Tilted up by 2/8 rad: the scene moved down 2 px, so this frame's dot lands back where it was.
        delta = AngularDelta(0.25f, 0f, 0f)
        val (y2, u2, v2) = planes(8, 8, { x, yy -> if (x == 4 && yy == 6) 250 else 0 })
        session.offer(y2, u2, v2, mapper, false, 1L)
        session.offer(y2, u2, v2, mapper, false, 2L)
        assertEquals(2, session.framesStacked)
        assertEquals(1f, session.progress)
        val result = session.result()
        assertNotNull(result)
        result!!
        assertEquals(250, result.rgb[4 * 8 + 4] and 0xFF)
        assertEquals(0, result.rgb[6 * 8 + 4] and 0xFF)
    }

    @Test
    fun `extra rotation for the physical hold`() {
        assertEquals(0, StackingSession.extraRotation(90, 0, false))
        assertEquals(270, StackingSession.extraRotation(90, 90, false))
        assertEquals(180, StackingSession.extraRotation(90, 180, false))
        assertEquals(270, StackingSession.extraRotation(270, 90, true))
    }

    @Test
    fun `classification turns on after a second of two faces and off after two seconds without`() {
        val gate = FaceClassificationGate()
        assertFalse(gate.update(2, 0, groupMode = false))
        assertFalse(gate.update(2, 900, groupMode = false))
        assertTrue(gate.update(2, 1_000, groupMode = false))
        assertTrue(gate.update(1, 1_500, groupMode = false))
        assertTrue(gate.update(1, 2_900, groupMode = false))
        assertFalse(gate.update(1, 3_100, groupMode = false))
        assertTrue(gate.update(0, 3_200, groupMode = true))
    }

    @Test
    fun `eyes open is the lower of the two eyes`() {
        assertEquals(0.2f, MlKitResultMapping.eyesOpen(0.9f, 0.2f))
        assertEquals(0.7f, MlKitResultMapping.eyesOpen(null, 0.7f))
        assertEquals(null, MlKitResultMapping.eyesOpen(null, null))
    }

    @Test
    fun `still working size keeps the aspect and never upscales`() {
        assertEquals(1024 to 768, StillImageAnalyzer.workingSize(4080, 3060))
        assertEquals(768 to 1024, StillImageAnalyzer.workingSize(3000, 4000))
        assertEquals(640 to 480, StillImageAnalyzer.workingSize(640, 480))
    }
}

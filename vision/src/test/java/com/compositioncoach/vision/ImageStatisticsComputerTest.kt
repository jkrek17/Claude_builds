package com.compositioncoach.vision

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ImageStatisticsComputerTest {

    private fun luma(width: Int, height: Int, value: (x: Int, y: Int) -> Int): ByteArray {
        val bytes = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bytes[y * width + x] = value(x, y).coerceIn(0, 255).toByte()
            }
        }
        return bytes
    }

    private fun identityMapper(width: Int, height: Int, rotation: Int = 0, mirror: Boolean = false) = FrameCoordinateMapper(
        sensorWidth = width, sensorHeight = height,
        rotationDegrees = rotation,
        cropLeft = 0, cropTop = 0, cropRight = width, cropBottom = height,
        mirror = mirror,
    )

    @Test
    fun `uniform image has near-zero edge density and contrast`() {
        val w = 64; val h = 64
        val bytes = luma(w, h) { _, _ -> 128 }
        val mapper = identityMapper(w, h)
        val stats = ImageStatisticsComputer.compute(bytes, w, h, rowStride = w, pixelStride = 1, mapper = mapper)

        assertTrue("contrast should be ~0, was ${stats.contrast}", stats.contrast < 0.01f)
        val meanEdge = stats.edgeDensity.average()
        assertTrue("edge density should be ~0, was $meanEdge", meanEdge < 0.01)
    }

    @Test
    fun `bright top dark bottom image yields a level horizon near the middle`() {
        val w = 100; val h = 100
        val bytes = luma(w, h) { _, y -> if (y < h / 2) 220 else 40 }
        val mapper = identityMapper(w, h)
        val stats = ImageStatisticsComputer.compute(bytes, w, h, rowStride = w, pixelStride = 1, mapper = mapper)

        val angle = stats.estimatedHorizonAngleDegrees
        assertNotNull("expected a horizon to be found", angle)
        assertTrue("expected a roughly level horizon, was $angle deg", abs(angle!!) < 5f)

        val horizon = stats.dominantLines.maxBy { it.strength }
        val avgY = (horizon.start.y + horizon.end.y) / 2f
        assertTrue("expected the horizon near y=0.5, was $avgY", abs(avgY - 0.5f) < 0.08f)
    }

    @Test
    fun `a symmetric striped pattern scores high horizontal symmetry`() {
        val w = 100; val h = 100
        // Stripes defined by distance-from-center, so the pattern is symmetric about the vertical
        // center line by construction, with plenty of edge content for the confidence weighting.
        val bytes = luma(w, h) { x, _ -> if ((minOf(x, w - 1 - x) / 10) % 2 == 0) 220 else 40 }
        val mapper = identityMapper(w, h)
        val stats = ImageStatisticsComputer.compute(bytes, w, h, rowStride = w, pixelStride = 1, mapper = mapper)

        assertTrue("expected high horizontal symmetry, was ${stats.horizontalSymmetry}", stats.horizontalSymmetry > 0.75f)
    }

    @Test
    fun `a one-sided striped pattern scores lower horizontal symmetry than its symmetric counterpart`() {
        val w = 100; val h = 100
        val symmetricBytes = luma(w, h) { x, _ -> if ((minOf(x, w - 1 - x) / 10) % 2 == 0) 220 else 40 }
        // The same stripes, but only on the left half; the right half is flat -- not mirror-symmetric.
        val oneSidedBytes = luma(w, h) { x, _ -> if (x < w / 2 && (x / 10) % 2 == 0) 220 else 40 }
        val mapper = identityMapper(w, h)
        val symmetric = ImageStatisticsComputer.compute(symmetricBytes, w, h, rowStride = w, pixelStride = 1, mapper = mapper)
        val oneSided = ImageStatisticsComputer.compute(oneSidedBytes, w, h, rowStride = w, pixelStride = 1, mapper = mapper)

        assertTrue(
            "expected the one-sided pattern (${oneSided.horizontalSymmetry}) to score clearly lower than " +
                "the symmetric one (${symmetric.horizontalSymmetry})",
            oneSided.horizontalSymmetry < symmetric.horizontalSymmetry - 0.15f,
        )
    }

    @Test
    fun `a vertical bright bar shows elevated edge density near its edges`() {
        val w = 100; val h = 100
        val bytes = luma(w, h) { x, _ -> if (x in 45..55) 220 else 60 }
        val mapper = identityMapper(w, h)
        val stats = ImageStatisticsComputer.compute(bytes, w, h, rowStride = w, pixelStride = 1, mapper = mapper)

        val midRow = stats.gridHeight / 2
        val colEdges = (0 until stats.gridWidth).map { stats.edgeDensityAt(it, midRow) }
        val maxEdge = colEdges.max()
        val farEdge = colEdges[1] // column 1 is far from the bar (bar sits around normalized x=0.45-0.55)
        assertTrue("expected some column to show strong edge density, max was $maxEdge", maxEdge > 0.1f)
        assertTrue("expected the bar's edge ($maxEdge) to exceed a flat region ($farEdge)", maxEdge > farEdge)
    }

    @Test
    fun `low-confidence images report no horizon`() {
        val w = 64; val h = 64
        val bytes = luma(w, h) { _, _ -> 128 }
        val mapper = identityMapper(w, h)
        val stats = ImageStatisticsComputer.compute(bytes, w, h, rowStride = w, pixelStride = 1, mapper = mapper)
        assertNull(stats.estimatedHorizonAngleDegrees)
    }

    @Test
    fun `rotation 90 of a bright-top image yields bright-right in upright space`() {
        val w = 100; val h = 100
        val bytes = luma(w, h) { _, y -> if (y < h / 2) 220 else 40 }
        // rotationDegrees=90: sensor row y=0 (bright) maps to the upright frame's right edge (nx~1),
        // and sensor row y=h-1 (dark) maps to the upright frame's left edge (nx~0) -- see
        // FrameCoordinateMapper's rotation derivation.
        val mapper = identityMapper(w, h, rotation = 90)
        val stats = ImageStatisticsComputer.compute(bytes, w, h, rowStride = w, pixelStride = 1, mapper = mapper)

        var leftSum = 0f; var rightSum = 0f
        for (row in 0 until stats.gridHeight) {
            leftSum += stats.luminanceAt(0, row)
            rightSum += stats.luminanceAt(stats.gridWidth - 1, row)
        }
        assertTrue(
            "expected the right edge ($rightSum) brighter than the left edge ($leftSum) after a 90 deg rotation",
            rightSum > leftSum,
        )
    }
}

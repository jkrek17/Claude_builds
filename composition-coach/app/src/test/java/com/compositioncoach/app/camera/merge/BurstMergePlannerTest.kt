package com.compositioncoach.app.camera.merge

import com.compositioncoach.app.camera.Steadiness
import com.compositioncoach.vision.PerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstMergePlannerTest {

    @Test
    fun `frame counts follow the spec and the thermal tier`() {
        assertEquals(5, BurstMergePlanner.frameCount(BurstMergeMode.BURST_MERGE, PerformanceTier.FULL))
        assertEquals(8, BurstMergePlanner.frameCount(BurstMergeMode.NIGHT_STACK, PerformanceTier.FULL))
        assertEquals(3, BurstMergePlanner.frameCount(BurstMergeMode.BURST_MERGE, PerformanceTier.REDUCED))
        assertEquals(4, BurstMergePlanner.frameCount(BurstMergeMode.NIGHT_STACK, PerformanceTier.REDUCED))
        assertEquals(1, BurstMergePlanner.frameCount(BurstMergeMode.BURST_MERGE, PerformanceTier.MINIMAL))
        assertEquals(1, BurstMergePlanner.frameCount(BurstMergeMode.NIGHT_STACK, PerformanceTier.MINIMAL))
    }

    @Test
    fun `merged files carry the spec's suffixes`() {
        assertEquals("_merge", BurstMergeMode.BURST_MERGE.suffix)
        assertEquals("_night", BurstMergeMode.NIGHT_STACK.suffix)
    }

    @Test
    fun `the bias is a third of a stop when the step allows it`() {
        assertEquals(-1, BurstMergePlanner.biasIndexDelta(1f / 3f))
        assertEquals(-2, BurstMergePlanner.biasIndexDelta(1f / 6f))
        assertEquals(-1, BurstMergePlanner.biasIndexDelta(0.5f))
        assertEquals(0, BurstMergePlanner.biasIndexDelta(1f))
        assertEquals(0, BurstMergePlanner.biasIndexDelta(0f))
        assertEquals(0, BurstMergePlanner.biasIndexDelta(Float.NaN))
    }

    @Test
    fun `the lift undoes exactly what was applied`() {
        assertEquals(1f / 3f, BurstMergePlanner.liftStops(-1, 1f / 3f), 1e-6f)
        assertEquals(1f / 3f, BurstMergePlanner.liftStops(-2, 1f / 6f), 1e-6f)
        assertEquals(0.5f, BurstMergePlanner.liftStops(-1, 0.5f), 1e-6f)
        assertEquals(0f, BurstMergePlanner.liftStops(0, 1f / 3f), 0f)
    }

    @Test
    fun `the night shutter follows the steadiness`() {
        assertEquals(125_000_000L, BurstMergePlanner.nightShutterNs(Steadiness.STEADY))
        assertEquals(66_666_667L, BurstMergePlanner.nightShutterNs(Steadiness.SLIGHT_MOTION))
        assertNull(BurstMergePlanner.nightShutterNs(Steadiness.MOVING))
    }

    @Test
    fun `a longer shutter divides the iso by its gain and keeps the exposure`() {
        // Auto picked 1/30 s at ISO 1600; steady allows 1/8 s: 3.75x the light per frame, so ISO 1600 / 3.75.
        val plan = BurstMergePlanner.nightExposure(
            autoExposureNs = 33_333_333L, autoIso = 1600, steadiness = Steadiness.STEADY,
            exposureRangeNs = 10_000L..1_000_000_000L, isoRange = 50..6400,
        )
        assertNotNull(plan)
        assertEquals(125_000_000L, plan!!.exposureNs)
        assertEquals(427, plan.iso)
        val autoLight = 33_333_333.0 * 1600
        val manualLight = plan.exposureNs.toDouble() * plan.iso
        assertEquals(1.0, manualLight / autoLight, 0.01)
    }

    @Test
    fun `the iso floor shortens the shutter so the picture is not brighter than auto`() {
        // ISO 100 / 3.75 would be below the sensor's floor of 100: keep ISO 100, shorten the shutter to match.
        val plan = BurstMergePlanner.nightExposure(
            autoExposureNs = 33_333_333L, autoIso = 100, steadiness = Steadiness.STEADY,
            exposureRangeNs = null, isoRange = 100..3200,
        )
        assertNull("auto already uses the floor ISO, so no longer shutter fits", plan)
        val partial = BurstMergePlanner.nightExposure(
            autoExposureNs = 33_333_333L, autoIso = 200, steadiness = Steadiness.STEADY,
            exposureRangeNs = null, isoRange = 100..3200,
        )
        assertNotNull(partial)
        assertEquals(100, partial!!.iso)
        assertEquals(66_666_666.0, partial.exposureNs.toDouble(), 2.0)
    }

    @Test
    fun `no manual exposure when moving or when auto already has a long shutter`() {
        assertNull(BurstMergePlanner.nightExposure(33_333_333L, 800, Steadiness.MOVING, null, null))
        assertNull(BurstMergePlanner.nightExposure(125_000_000L, 800, Steadiness.STEADY, null, null))
        assertNull(BurstMergePlanner.nightExposure(110_000_000L, 800, Steadiness.STEADY, null, null))
        assertNull(BurstMergePlanner.nightExposure(0L, 800, Steadiness.STEADY, null, null))
        assertNull(BurstMergePlanner.nightExposure(33_333_333L, 0, Steadiness.STEADY, null, null))
    }

    @Test
    fun `the shutter is clamped to the sensor's range`() {
        val plan = BurstMergePlanner.nightExposure(
            autoExposureNs = 10_000_000L, autoIso = 1000, steadiness = Steadiness.STEADY,
            exposureRangeNs = 1_000_000L..50_000_000L, isoRange = 50..6400,
        )
        assertNotNull(plan)
        assertTrue(plan!!.exposureNs <= 50_000_000L)
        assertEquals(1.0, plan.exposureNs.toDouble() * plan.iso / (10_000_000.0 * 1000), 0.02)
    }
}

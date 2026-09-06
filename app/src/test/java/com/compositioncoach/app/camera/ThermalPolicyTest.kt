package com.compositioncoach.app.camera

import android.os.Build
import com.compositioncoach.vision.PerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises [ThermalPolicy]'s pure tier-mapping function; see its KDoc for the "worse wins" rationale. */
class ThermalPolicyTest {

    private fun tier(thermalStatus: Int, batterySaverOn: Boolean, sdkInt: Int = Build.VERSION_CODES.Q) =
        ThermalPolicy.computeTier(thermalStatus, batterySaverOn, sdkInt)

    @Test
    fun `no thermal throttling and no battery saver is FULL`() {
        assertEquals(PerformanceTier.FULL, tier(thermalStatus = 0, batterySaverOn = false))
    }

    @Test
    fun `moderate thermal status alone is REDUCED`() {
        assertEquals(PerformanceTier.REDUCED, tier(thermalStatus = 2, batterySaverOn = false))
    }

    @Test
    fun `severe thermal status alone is MINIMAL`() {
        assertEquals(PerformanceTier.MINIMAL, tier(thermalStatus = 3, batterySaverOn = false))
    }

    @Test
    fun `a thermal status worse than severe is still MINIMAL`() {
        assertEquals(PerformanceTier.MINIMAL, tier(thermalStatus = 6, batterySaverOn = false))
    }

    @Test
    fun `battery saver alone is REDUCED`() {
        assertEquals(PerformanceTier.REDUCED, tier(thermalStatus = 0, batterySaverOn = true))
    }

    @Test
    fun `battery saver never escalates a FULL thermal reading past REDUCED`() {
        assertEquals(PerformanceTier.REDUCED, tier(thermalStatus = 1, batterySaverOn = true))
    }

    @Test
    fun `severe thermal status wins over battery saver`() {
        assertEquals(PerformanceTier.MINIMAL, tier(thermalStatus = 3, batterySaverOn = true))
    }

    @Test
    fun `below API 29 thermal status is ignored entirely, only battery saver applies`() {
        assertEquals(PerformanceTier.FULL, tier(thermalStatus = 3, batterySaverOn = false, sdkInt = Build.VERSION_CODES.P))
        assertEquals(PerformanceTier.REDUCED, tier(thermalStatus = 3, batterySaverOn = true, sdkInt = Build.VERSION_CODES.P))
    }
}

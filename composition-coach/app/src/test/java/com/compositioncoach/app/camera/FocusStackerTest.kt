package com.compositioncoach.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusStackerTest {

    private fun assertClose(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-4f) }
    }

    @Test
    fun `the far set runs from infinity through a third and two thirds of the closest focus`() {
        assertClose(listOf(0f, 3f, 6f), FocusStacker.diopters(FocusStacker.FAR_FRACTIONS, 9f))
    }

    @Test
    fun `the near set starts at the closest focus and backs off to 0_8 and 0_6 of it`() {
        assertClose(listOf(10f, 8f, 6f), FocusStacker.diopters(FocusStacker.NEAR_FRACTIONS, 10f))
    }

    @Test
    fun `both sets have three distances and the companion is the first`() {
        assertEquals(3, FocusStacker.FAR_FRACTIONS.size)
        assertEquals(3, FocusStacker.NEAR_FRACTIONS.size)
        assertEquals(0f, FocusStacker.FAR_FRACTIONS.first(), 0f)
        assertEquals(1f, FocusStacker.NEAR_FRACTIONS.first(), 0f)
    }
}

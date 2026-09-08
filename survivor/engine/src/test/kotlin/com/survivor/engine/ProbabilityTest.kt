package com.survivor.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbabilityTest {
    @Test fun `no-vig removes the overround from the spec example`() {
        // -200 → 66.7% raw, +170 → 37.0% raw; normalised to 64.3% / 35.7%.
        val a = Probability.noVig(-200, 170)
        val b = Probability.noVig(170, -200)
        assertEquals(1.0, a + b, 1e-12)
        assertEquals(0.643, a, 0.001)
    }

    @Test fun `spread conversion matches historical NFL closing-line win rates`() {
        // Empirical closing-line favourite win rates (2000-2024, approx): -3 ≈ 59%, -7 ≈ 70%, -10 ≈ 77%, -14 ≈ 85%.
        assertEquals(0.50, Probability.winProbabilityFromSpread(0.0), 1e-9)
        assertEquals(0.588, Probability.winProbabilityFromSpread(3.0), 0.01)
        assertEquals(0.699, Probability.winProbabilityFromSpread(7.0), 0.01)
        assertEquals(0.771, Probability.winProbabilityFromSpread(10.0), 0.01)
        assertEquals(0.851, Probability.winProbabilityFromSpread(14.0), 0.01)
        assertEquals(1 - Probability.winProbabilityFromSpread(7.0), Probability.winProbabilityFromSpread(-7.0), 1e-9)
    }

    @Test fun `inverse conversion round-trips`() {
        for (s in listOf(-13.5, -6.0, -2.5, 0.0, 1.0, 4.5, 9.0, 16.5)) {
            val p = Probability.winProbabilityFromSpread(s)
            assertTrue(abs(Probability.spreadFromWinProbability(p) - s) < 0.01, "spread $s")
        }
    }

    @Test fun `probabilities always stay inside 0 and 100 percent`() {
        assertTrue(Probability.winProbabilityFromSpread(60.0) < 1.0)
        assertTrue(Probability.winProbabilityFromSpread(-60.0) > 0.0)
        assertTrue(Probability.noVig(-100000, 50000) < 1.0)
    }

    @Test fun `discount shrinks toward a coin flip only for future weeks`() {
        assertEquals(0.8, Probability.discount(0.8, 0, 0.03), 1e-12)
        val d = Probability.discount(0.8, 10, 0.03)
        assertTrue(d < 0.8 && d > 0.5)
        assertEquals(0.5 + 0.3 * Math.pow(0.97, 10.0), d, 1e-9)
    }

    @Test fun `point shift moves a probability in the right direction`() {
        val p = 0.75
        assertTrue(Probability.shiftByPoints(p, 3.0, 13.45) > p)
        assertTrue(Probability.shiftByPoints(p, -3.0, 13.45) < p)
        assertEquals(p, Probability.shiftByPoints(p, 0.0, 13.45), 1e-12)
    }
}

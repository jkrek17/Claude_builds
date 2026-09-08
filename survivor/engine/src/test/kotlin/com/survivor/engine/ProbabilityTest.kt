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

    @Test fun `spread conversion is calibrated to 2010-2025 closing-line outcomes`() {
        // nflverse closing lines 2010-2025, favourite win rate by implied bucket at sigma = 11.0:
        // 60-65% implied -> 60.7% actual, 70-75% -> 73.8%, 80-85% -> 82.5%, 85-90% -> 88.1%, 90-95% -> 93.2%.
        assertEquals(11.0, ModelSettings().marginSigma, 1e-9)
        assertEquals(0.50, Probability.winProbabilityFromSpread(0.0), 1e-9)
        assertEquals(0.607, Probability.winProbabilityFromSpread(3.0), 0.005)
        assertEquals(0.738, Probability.winProbabilityFromSpread(7.0), 0.005)
        assertEquals(0.818, Probability.winProbabilityFromSpread(10.0), 0.005)
        assertEquals(0.898, Probability.winProbabilityFromSpread(14.0), 0.005)
        assertEquals(1 - Probability.winProbabilityFromSpread(7.0), Probability.winProbabilityFromSpread(-7.0), 1e-9)
        // The raw margin standard deviation (~13.5) is still available as an explicit sigma.
        assertEquals(0.699, Probability.winProbabilityFromSpread(7.0, sigma = 13.45), 0.01)
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

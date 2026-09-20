package com.survivor.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyNumbersTest {

    private fun assertApprox(expected: Double, actual: Double, tolerance: Double, label: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$label: expected ~$expected, got $actual (tolerance $tolerance)")
    }

    @Test fun `marginDistribution always sums to 1`() {
        for (s in listOf(-10.0, -3.0, 0.0, 2.5, 3.0, 7.0, 14.0)) {
            val dist = KeyNumbers.marginDistribution(s)
            assertApprox(1.0, dist.sum(), 1e-9, "sum at s=$s")
        }
    }

    @Test fun `laying -3 covers about 49-6 percent of non-pushes with an 8-3 percent push`() {
        val outcome = KeyNumbers.coverProbability(teamSpread = -3.0, teamIsHome = true, homeSpreadConsensus = 3.0)
        assertApprox(0.083, outcome.push, 0.01, "push at -3")
        assertApprox(0.496, outcome.winOfDecided, 0.02, "cover% of non-push at -3")
    }

    @Test fun `laying -7 pushes about 5-4 percent of the time`() {
        val outcome = KeyNumbers.coverProbability(teamSpread = -7.0, teamIsHome = true, homeSpreadConsensus = 7.0)
        assertApprox(0.054, outcome.push, 0.01, "push at -7")
    }

    @Test fun `laying -2 point 5 (no key number) covers about 52-3 percent`() {
        val outcome = KeyNumbers.coverProbability(teamSpread = -2.5, teamIsHome = true, homeSpreadConsensus = 2.5)
        assertApprox(0.0, outcome.push, 1e-9, "no push on a half-point line")
        assertApprox(0.523, outcome.winOfDecided, 0.02, "cover% at -2.5")
    }

    @Test fun `half point off -3 to -2-5 is worth about +4-2 cover points`() {
        val value = KeyNumbers.halfPointValue(fromLine = -3.0, toLine = -2.5, teamIsHome = true, homeSpreadConsensus = 3.0)
        assertApprox(4.2, value, 1.0, "half-point value off 3")
    }

    @Test fun `half point off -5 to -4-5 is worth about +1-2 cover points`() {
        val value = KeyNumbers.halfPointValue(fromLine = -5.0, toLine = -4.5, teamIsHome = true, homeSpreadConsensus = 5.0)
        assertApprox(1.2, value, 1.0, "half-point value off 5")
    }

    @Test fun `win probability at -3, -7, -10`() {
        assertApprox(0.589, KeyNumbers.winProbability(3.0), 0.02, "win% at -3")
        assertApprox(0.701, KeyNumbers.winProbability(7.0), 0.02, "win% at -7")
        assertApprox(0.776, KeyNumbers.winProbability(10.0), 0.02, "win% at -10")
    }

    @Test fun `home covering -3 mirrors away covering +3 at the same consensus`() {
        // Home lays -3.0 with the market (and model) expecting the home team favored by exactly 3.
        val home = KeyNumbers.coverProbability(teamSpread = -3.0, teamIsHome = true, homeSpreadConsensus = 3.0)
        // Away gets +3.0 at that same consensus - the exact complementary bet on the same game.
        val away = KeyNumbers.coverProbability(teamSpread = 3.0, teamIsHome = false, homeSpreadConsensus = 3.0)
        assertEquals(home.push, away.push, 1e-9)
        assertEquals(home.win, away.loss, 1e-9)
        assertEquals(home.loss, away.win, 1e-9)
    }

    @Test fun `flipping which side is home mirrors the outcome when the consensus sign also flips`() {
        // A home favorite laying -3 (consensus +3, home favored) covers exactly as often as an away
        // favorite laying -3 does when the consensus is mirrored to -3 (away favored) instead.
        val homeFavLaysThree = KeyNumbers.coverProbability(teamSpread = -3.0, teamIsHome = true, homeSpreadConsensus = 3.0)
        val awayFavLaysThree = KeyNumbers.coverProbability(teamSpread = -3.0, teamIsHome = false, homeSpreadConsensus = -3.0)
        assertEquals(homeFavLaysThree.win, awayFavLaysThree.win, 1e-9)
        assertEquals(homeFavLaysThree.push, awayFavLaysThree.push, 1e-9)
        assertEquals(homeFavLaysThree.loss, awayFavLaysThree.loss, 1e-9)
    }
}

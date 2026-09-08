package com.survivor.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurvivalTest {
    private val ps = listOf(0.8, 0.7, 0.9, 0.6)

    /** Brute-force enumeration of every win/loss combination as ground truth. */
    private fun bruteForce(ps: List<Double>, losses: Int): Double {
        var total = 0.0
        for (mask in 0 until (1 shl ps.size)) {
            var prob = 1.0; var l = 0
            for (i in ps.indices) if (mask and (1 shl i) != 0) { prob *= (1 - ps[i]); l++ } else prob *= ps[i]
            if (l == losses) total += prob
        }
        return total
    }

    @Test fun `zero-loss probability is the product`() = assertEquals(0.8 * 0.7 * 0.9 * 0.6, Survival.zeroLoss(ps), 1e-12)

    @Test fun `exactly-one-loss matches enumeration`() = assertEquals(bruteForce(ps, 1), Survival.exactlyOneLoss(ps), 1e-12)

    @Test fun `double elimination survival is zero plus one loss, single elimination is zero only`() {
        assertEquals(bruteForce(ps, 0) + bruteForce(ps, 1), Survival.survive(ps, strikesAllowed = 1), 1e-12)
        assertEquals(bruteForce(ps, 0), Survival.survive(ps, strikesAllowed = 0), 1e-12)
    }

    @Test fun `loss distribution sums to one and matches enumeration`() {
        val dist = Survival.lossDistribution(ps)
        assertEquals(1.0, dist.sum(), 1e-12)
        for (k in 0..ps.size) assertEquals(bruteForce(ps, k), dist[k], 1e-12)
    }

    /** Brute-force P(at most [strikesAllowed] losses among the first [k] games) for the [k]-length prefix. */
    private fun bruteForceAliveAfter(ps: List<Double>, k: Int, strikesAllowed: Int): Double {
        val prefix = ps.subList(0, k)
        var total = 0.0
        for (losses in 0..minOf(strikesAllowed, prefix.size)) total += bruteForce(prefix, losses)
        return total
    }

    @Test fun `aliveAfterEachWeek matches brute-force enumeration for every prefix`() {
        for (strikesAllowed in 0..2) {
            val alive = Survival.aliveAfterEachWeek(ps, strikesAllowed)
            assertEquals(ps.size, alive.size)
            for (k in 1..ps.size) assertEquals(bruteForceAliveAfter(ps, k, strikesAllowed), alive[k - 1], 1e-12)
        }
    }

    @Test fun `expectedWeeksAlive is the sum of aliveAfterEachWeek and matches brute-force`() {
        for (strikesAllowed in 0..2) {
            val expected = (1..ps.size).sumOf { k -> bruteForceAliveAfter(ps, k, strikesAllowed) }
            assertEquals(expected, Survival.expectedWeeksAlive(ps, strikesAllowed), 1e-12)
            assertEquals(Survival.aliveAfterEachWeek(ps, strikesAllowed).sum(), Survival.expectedWeeksAlive(ps, strikesAllowed), 1e-12)
        }
    }

    @Test fun `an all-safe entry has expected weeks alive equal to season length`() {
        val allSafe = List(5) { 1.0 }
        assertEquals(5.0, Survival.expectedWeeksAlive(allSafe, strikesAllowed = 1), 1e-12)
        assertEquals(List(5) { 1.0 }, Survival.aliveAfterEachWeek(allSafe, strikesAllowed = 1))
    }

    @Test fun `objectiveValue matches survive, expectedWeeksAlive and the blend respectively`() {
        val strikesAllowed = 1
        assertEquals(Survival.survive(ps, strikesAllowed), Survival.objectiveValue(ps, strikesAllowed, RouteObjective.SURVIVE_SEASON, horizonWeight = 0.5), 1e-12)
        assertEquals(Survival.expectedWeeksAlive(ps, strikesAllowed), Survival.objectiveValue(ps, strikesAllowed, RouteObjective.EXPECTED_WEEKS_ALIVE, horizonWeight = 0.5), 1e-12)
        val horizonWeight = 0.3
        val expectedBlend = (1 - horizonWeight) * Survival.survive(ps, strikesAllowed) + horizonWeight * (Survival.expectedWeeksAlive(ps, strikesAllowed) / ps.size)
        assertEquals(expectedBlend, Survival.objectiveValue(ps, strikesAllowed, RouteObjective.BLENDED, horizonWeight), 1e-12)
    }

    @Test fun `objectiveValue routes POOL_WIN to poolWinProbability`() {
        val strikesAllowed = 1
        assertEquals(
            Survival.poolWinProbability(ps, strikesAllowed, poolEntries = 40, f = 0.76),
            Survival.objectiveValue(ps, strikesAllowed, RouteObjective.POOL_WIN, horizonWeight = 0.5, poolEntries = 40, fieldWinProbability = 0.76),
            1e-12,
        )
    }

    @Test fun `fieldAliveCurve is monotone non-increasing and matches aliveAfterEachWeek on flat probabilities`() {
        for (n in listOf(0, 1, 5, 10)) {
            for (f in listOf(0.5, 0.7, 0.9, 1.0)) {
                val curve = Survival.fieldAliveCurve(n, f)
                assertEquals(n, curve.size)
                assertEquals(Survival.aliveAfterEachWeek(List(n) { f }, 1), curve)
                for (i in 1 until curve.size) assertTrue(curve[i] <= curve[i - 1] + 1e-12, "n=$n f=$f i=$i")
            }
        }
    }

    /**
     * Ground truth for [Survival.poolWinProbability], built by enumerating every win/loss sequence for "you"
     * (variable weekly probabilities [ps]) and independently for each of [m] other entries (flat weekly
     * probability [f], double elimination): the pool ends the first week the field's alive count hits zero
     * (you win outright iff you're still alive that week) or, failing that, at week n where survivors split
     * the pot 1/(count) each.
     */
    private fun bruteForcePoolWin(ps: List<Double>, strikesAllowed: Int, f: Double, m: Int, strikesAllowedForField: Int = 1): Double {
        val n = ps.size
        var total = 0.0
        for (yourMask in 0 until (1 shl n)) {
            var yourProb = 1.0
            var yourLosses = 0
            val yourAlive = BooleanArray(n)
            for (i in 0 until n) {
                val lost = (yourMask shr i) and 1 == 1
                yourProb *= if (lost) (1 - ps[i]) else ps[i]
                if (lost) yourLosses++
                yourAlive[i] = yourLosses <= strikesAllowed
            }
            if (yourProb == 0.0) continue
            val otherCombos = 1 shl (n * m)
            for (combo in 0 until otherCombos) {
                var otherProb = 1.0
                val aliveCount = IntArray(n)
                for (e in 0 until m) {
                    val mask = (combo shr (e * n)) and ((1 shl n) - 1)
                    var losses = 0
                    for (i in 0 until n) {
                        val lost = (mask shr i) and 1 == 1
                        otherProb *= if (lost) (1 - f) else f
                        if (lost) losses++
                        if (losses <= strikesAllowedForField) aliveCount[i]++
                    }
                }
                val jointProb = yourProb * otherProb
                if (jointProb == 0.0) continue
                var share = 0.0
                var decided = false
                for (k in 0 until n) {
                    if (aliveCount[k] == 0) {
                        if (yourAlive[k]) share = 1.0
                        decided = true
                        break
                    }
                }
                if (!decided && yourAlive[n - 1]) share = 1.0 / (1 + aliveCount[n - 1])
                total += jointProb * share
            }
        }
        return total
    }

    @Test fun `poolWinProbability matches brute-force enumeration for tiny pools`() {
        val f = 0.76
        data class Case(val ps: List<Double>, val strikesAllowed: Int, val m: Int)
        val cases = listOf(
            Case(listOf(0.8, 0.6), 1, 1),
            Case(listOf(0.8, 0.6), 0, 1),
            Case(listOf(0.55, 0.9), 1, 2),
            Case(listOf(0.7, 0.65, 0.9), 1, 1),
            Case(listOf(0.7, 0.65, 0.9), 1, 2),
            Case(listOf(0.7, 0.65, 0.9), 0, 2),
        )
        for (c in cases) {
            val poolEntries = c.m + 1
            val expected = bruteForcePoolWin(c.ps, c.strikesAllowed, f, c.m)
            val actual = Survival.poolWinProbability(c.ps, c.strikesAllowed, poolEntries, f)
            assertEquals(expected, actual, 1e-9, "ps=${c.ps} strikesAllowed=${c.strikesAllowed} m=${c.m}")
            assertTrue(actual in 0.0..1.0, "poolWinProbability out of range: $actual")
        }
    }

    @Test fun `per-week field win probabilities reproduce the flat result when every entry equals f`() {
        val f = 0.76
        val flat = Survival.poolWinProbability(ps, strikesAllowed = 1, poolEntries = 30, f = f)
        val sameEveryWeek = Survival.poolWinProbability(ps, strikesAllowed = 1, poolEntries = 30, f = f, fieldWinProbabilities = List(ps.size) { f })
        assertEquals(flat, sameEveryWeek, 1e-12)
        // objectiveValue and Route.objectiveValue thread the override through the same way.
        val route = Route(ps.mapIndexed { i, p -> RouteStep(i + 1, Team.entries[i], p) }, strikesAllowed = 1)
        val viaObjectiveValue = Survival.objectiveValue(ps, 1, RouteObjective.POOL_WIN, 0.5, 30, f, List(ps.size) { f })
        val viaRoute = route.objectiveValue(RouteObjective.POOL_WIN, 0.5, 30, f, List(ps.size) { f })
        assertEquals(flat, viaObjectiveValue, 1e-12)
        assertEquals(flat, viaRoute, 1e-12)
    }

    @Test fun `a mis-sized or null field win probabilities list falls back to the flat model`() {
        val f = 0.76
        val flat = Survival.poolWinProbability(ps, strikesAllowed = 1, poolEntries = 30, f = f)
        assertEquals(flat, Survival.poolWinProbability(ps, 1, 30, f, fieldWinProbabilities = null), 1e-12)
        assertEquals(flat, Survival.poolWinProbability(ps, 1, 30, f, fieldWinProbabilities = listOf(f, f)), 1e-12)
    }

    @Test fun `a higher current-week field win probability raises the pool-win probability`() {
        val f = 0.76
        val flat = Survival.poolWinProbability(ps, strikesAllowed = 1, poolEntries = 30, f = f)
        val strongerField = Survival.poolWinProbability(
            ps, strikesAllowed = 1, poolEntries = 30, f = f,
            fieldWinProbabilities = listOf(0.95) + List(ps.size - 1) { f },
        )
        // A field that wins its own pick more often this week clears out more slowly, so your pool-win chance drops.
        assertTrue(strongerField < flat)
    }
}

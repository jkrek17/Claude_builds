package com.survivor.engine

import com.survivor.engine.data.SavedState
import com.survivor.engine.data.StateCodec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineHistoryTest {
    private val season = TestSeason.build(seed = 7)
    private val sixHoursMs = 6 * 3_600_000L

    @Test fun `append records one snapshot of every non-final game`() {
        val h = LineHistory().append(season, currentWeek = 1, nowEpochMs = 1_000L)
        assertEquals(1, h.snapshots.size)
        val snap = h.snapshots.single()
        assertEquals(1_000L, snap.takenAtEpochMs)
        assertEquals(1, snap.currentWeek)
        assertEquals(season.games.count { it.state != GameState.FINAL }, snap.lines.size)
        val g = season.games.first()
        val rec = snap.lines.first { it.gameId == g.id }
        assertEquals(g.line?.homeSpread, rec.homeSpread)
        assertEquals(g.line?.homeMoneyline, rec.homeMoneyline)
        assertEquals(g.fpi?.homeWinProbability, rec.fpiHome)
    }

    @Test fun `append with finals excludes final games`() {
        val withFinals = TestSeason.build(seed = 7, finalsThroughWeek = 1)
        val h = LineHistory().append(withFinals, currentWeek = 2, nowEpochMs = 1_000L)
        val snap = h.snapshots.single()
        assertTrue(withFinals.gamesInWeek(1).isNotEmpty())
        assertTrue(snap.lines.none { rec -> withFinals.gamesInWeek(1).any { it.id == rec.gameId } })
    }

    @Test fun `a second append inside the interval with identical lines is skipped`() {
        val h1 = LineHistory().append(season, currentWeek = 1, nowEpochMs = 1_000L)
        val h2 = h1.append(season, currentWeek = 1, nowEpochMs = 1_000L + 60_000L)
        assertEquals(1, h2.snapshots.size)
        assertEquals(h1, h2)
    }

    @Test fun `a second append inside the interval is recorded when a spread changed`() {
        val h1 = LineHistory().append(season, currentWeek = 1, nowEpochMs = 1_000L)
        val g = season.games.first { it.state != GameState.FINAL }
        val moved = season.copy(games = season.games.map { if (it.id == g.id) it.copy(line = it.line!!.copy(homeSpread = (it.line!!.homeSpread ?: 0.0) + 3.0)) else it })
        val h2 = h1.append(moved, currentWeek = 1, nowEpochMs = 1_000L + 60_000L)
        assertEquals(2, h2.snapshots.size)
    }

    @Test fun `an append past the interval is recorded even with identical lines`() {
        val h1 = LineHistory().append(season, currentWeek = 1, nowEpochMs = 1_000L)
        val h2 = h1.append(season, currentWeek = 1, nowEpochMs = 1_000L + sixHoursMs)
        assertEquals(2, h2.snapshots.size)
    }

    @Test fun `at most 60 snapshots are kept, oldest dropped first`() {
        var h = LineHistory()
        for (i in 0 until 70) {
            h = h.append(season, currentWeek = 1, nowEpochMs = i * sixHoursMs)
        }
        assertEquals(60, h.snapshots.size)
        // The 10 oldest (i = 0..9) were dropped; the oldest remaining is i = 10.
        assertEquals(10 * sixHoursMs, h.snapshots.minOf { it.takenAtEpochMs })
        assertEquals(69 * sixHoursMs, h.snapshots.maxOf { it.takenAtEpochMs })
    }

    @Test fun `movement returns the recorded spread over time, oldest first`() {
        val g = season.games.first { it.state != GameState.FINAL }
        var h = LineHistory().append(season, currentWeek = 1, nowEpochMs = 1_000L)
        val bumped = season.copy(games = season.games.map { if (it.id == g.id) it.copy(line = it.line!!.copy(homeSpread = (it.line!!.homeSpread ?: 0.0) + 2.0)) else it })
        h = h.append(bumped, currentWeek = 1, nowEpochMs = 1_000L + sixHoursMs)
        val series = h.movement(g.id)
        assertEquals(2, series.size)
        assertEquals(1_000L, series[0].first)
        assertEquals(g.line?.homeSpread, series[0].second)
        assertEquals(1_000L + sixHoursMs, series[1].first)
        assertEquals((g.line?.homeSpread ?: 0.0) + 2.0, series[1].second)
    }

    @Test fun `biggestMovers ranks games by absolute spread change since the baseline`() {
        val g1 = season.games.first { it.state != GameState.FINAL }
        val g2 = season.games.first { it.state != GameState.FINAL && it.id != g1.id }
        var h = LineHistory().append(season, currentWeek = 1, nowEpochMs = 1_000L)
        val moved = season.copy(
            games = season.games.map {
                when (it.id) {
                    g1.id -> it.copy(line = it.line!!.copy(homeSpread = (it.line!!.homeSpread ?: 0.0) + 5.0))
                    g2.id -> it.copy(line = it.line!!.copy(homeSpread = (it.line!!.homeSpread ?: 0.0) + 1.0))
                    else -> it
                }
            },
        )
        h = h.append(moved, currentWeek = 1, nowEpochMs = 1_000L + sixHoursMs)
        val movers = h.biggestMovers(sinceEpochMs = 1_000L, limit = 5)
        assertTrue(movers.isNotEmpty())
        assertEquals(g1.id, movers.first().gameId)
        assertEquals(5.0, movers.first().delta, 1e-9)
        // Results are sorted by descending absolute delta.
        for (i in 1 until movers.size) assertTrue(abs(movers[i - 1].delta) >= abs(movers[i].delta))
    }

    @Test fun `biggestMovers falls back to the oldest snapshot when nothing precedes sinceEpochMs`() {
        val g1 = season.games.first { it.state != GameState.FINAL }
        var h = LineHistory().append(season, currentWeek = 1, nowEpochMs = 10_000L)
        val moved = season.copy(games = season.games.map { if (it.id == g1.id) it.copy(line = it.line!!.copy(homeSpread = (it.line!!.homeSpread ?: 0.0) + 4.0)) else it })
        h = h.append(moved, currentWeek = 1, nowEpochMs = 10_000L + sixHoursMs)
        // sinceEpochMs is before every snapshot, so the oldest snapshot is the baseline.
        val movers = h.biggestMovers(sinceEpochMs = 0L, limit = 1)
        assertEquals(g1.id, movers.single().gameId)
        assertEquals(4.0, movers.single().delta, 1e-9)
    }

    /**
     * Simulates 18 weeks of refreshes where each future week's line differs from its eventual closing
     * line by exactly [weeksAhead] points (a deterministic stand-in for real line drift): a snapshot taken
     * [k] weeks before a game's own week carries that game's true spread plus `k` points.
     */
    private fun syntheticHistory(): LineHistory {
        val snapshots = (1..REGULAR_SEASON_WEEKS).map { cw ->
            val lines = season.games.filter { it.week >= cw }.map { g ->
                val trueSpread = g.line!!.homeSpread!!
                val k = g.week - cw
                LineRecord(g.id, g.week, trueSpread + k * 1.0, g.line?.homeMoneyline, g.line?.awayMoneyline, g.fpi?.homeWinProbability)
            }
            LineSnapshot(takenAtEpochMs = cw * 1_000L, currentWeek = cw, lines = lines)
        }
        return LineHistory(snapshots)
    }

    @Test fun `calibration buckets mean absolute spread move exactly matches the synthetic weekly drift`() {
        val cal = syntheticHistory().calibration()
        assertTrue(cal.buckets.isNotEmpty())
        for (b in cal.buckets) {
            assertTrue(b.weeksAhead in 1..REGULAR_SEASON_WEEKS - 1, "unexpected bucket ${b.weeksAhead}")
            assertEquals(b.weeksAhead.toDouble(), b.meanAbsSpreadMove, 1e-9)
            assertTrue(b.samples > 0)
        }
        // No k = 0 bucket: a snapshot taken in a game's own week is that game's closing line, not an "earlier" one.
        assertTrue(cal.buckets.none { it.weeksAhead == 0 })
    }

    @Test fun `calibration fits a positive slope when logit movement grows with weeks ahead`() {
        val cal = syntheticHistory().calibration()
        val fitted = cal.buckets.filter { it.samples >= 5 }
        assertTrue(fitted.size >= 2, "need at least two well-sampled buckets to fit a line")
        assertNotNull(cal.tauBase)
        val tauPerWeek = assertNotNull(cal.tauPerWeek)
        assertTrue(tauPerWeek > 0.0, "expected a positive slope, got $tauPerWeek")
    }

    @Test fun `calibration is empty with no history`() {
        val cal = LineHistory().calibration()
        assertTrue(cal.buckets.isEmpty())
        assertNull(cal.tauBase)
        assertNull(cal.tauPerWeek)
    }

    @Test fun `line history round-trips through StateCodec JSON`() {
        val history = syntheticHistory()
        val state = SavedState(season = season, lineHistory = history)
        val json = StateCodec.encode(state)
        val decoded = StateCodec.decode(json)
        assertEquals(history, decoded.lineHistory)
        assertEquals(history.snapshots.size, decoded.lineHistory.snapshots.size)
        assertTrue(decoded.lineHistory.snapshots.isNotEmpty())
    }
}

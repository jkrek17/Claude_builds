package com.survivor.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One game's line at the moment a snapshot was taken. [homeSpread] mirrors [MarketLine.homeSpread]
 * (negative = home favored); moneylines and the FPI home win probability are carried along so a
 * snapshot is self-contained even if the live [Season] later changes shape.
 */
@Serializable
data class LineRecord(
    val gameId: String,
    val week: Int,
    val homeSpread: Double?,
    val homeMoneyline: Int?,
    val awayMoneyline: Int?,
    val fpiHome: Double?,
)

/** Every non-final game's line at one point in time. */
@Serializable
data class LineSnapshot(val takenAtEpochMs: Long, val currentWeek: Int, val lines: List<LineRecord>)

/** One game's biggest recorded line move: [fromSpread] is the older of the two spreads compared, [toSpread] the newer. */
data class Mover(val gameId: String, val week: Int, val fromSpread: Double?, val toSpread: Double?, val delta: Double)

/**
 * Empirical line-movement stats for games [weeksAhead] weeks before their closing (in-week) line: how many
 * (earlier snapshot, game) pairs were observed, the mean absolute spread move in points, and the standard
 * deviation of the win-probability move in logit space.
 */
data class CalibrationBucket(val weeksAhead: Int, val samples: Int, val meanAbsSpreadMove: Double, val logitSd: Double)

/**
 * Per-bucket line-movement stats plus a least-squares fit `logitSd(k) ≈ tauBase + tauPerWeek * k` over
 * buckets with at least 5 samples. [tauBase] and [tauPerWeek] are null until there is enough history to fit.
 * This is the measured counterpart to the assumed noise model `tau(k) = min(0.5, 0.08 + 0.03k)` used
 * elsewhere in the engine to widen future-week uncertainty: once a season's worth of refreshes has
 * accumulated, compare the fit here against those constants.
 */
data class Calibration(val buckets: List<CalibrationBucket>, val tauBase: Double?, val tauPerWeek: Double?)

/**
 * A time series of [LineSnapshot]s, recorded on every successful data refresh, used to measure how much
 * DraftKings lookahead lines move before kickoff. Kept as part of [com.survivor.engine.data.SavedState] so
 * the history survives app restarts.
 */
@Serializable
data class LineHistory(val snapshots: List<LineSnapshot> = emptyList()) {

    /**
     * Records the current line for every non-FINAL game in [season]. Skipped (returns `this` unchanged)
     * when the newest snapshot is younger than [minIntervalMs], unless at least one recorded spread has
     * changed since then. At most 60 snapshots are kept; the oldest are dropped first.
     */
    fun append(season: Season, currentWeek: Int, nowEpochMs: Long, minIntervalMs: Long = 6 * 3_600_000L): LineHistory {
        val candidate = season.games
            .filter { it.state != GameState.FINAL }
            .map { g ->
                LineRecord(
                    gameId = g.id,
                    week = g.week,
                    homeSpread = g.line?.homeSpread,
                    homeMoneyline = g.line?.homeMoneyline,
                    awayMoneyline = g.line?.awayMoneyline,
                    fpiHome = g.fpi?.homeWinProbability,
                )
            }
        val newest = snapshots.maxByOrNull { it.takenAtEpochMs }
        if (newest != null && nowEpochMs - newest.takenAtEpochMs < minIntervalMs) {
            val priorByGame = newest.lines.associateBy { it.gameId }
            val spreadChanged = candidate.any { c -> priorByGame[c.gameId]?.let { it.homeSpread != c.homeSpread } == true }
            if (!spreadChanged) return this
        }
        val appended = snapshots + LineSnapshot(nowEpochMs, currentWeek, candidate)
        val capped = if (appended.size > MAX_SNAPSHOTS) appended.sortedBy { it.takenAtEpochMs }.takeLast(MAX_SNAPSHOTS) else appended
        return copy(snapshots = capped)
    }

    /** [gameId]'s recorded spread over time, oldest first. */
    fun movement(gameId: String): List<Pair<Long, Double?>> =
        snapshots.sortedBy { it.takenAtEpochMs }.mapNotNull { s ->
            s.lines.firstOrNull { it.gameId == gameId }?.let { s.takenAtEpochMs to it.homeSpread }
        }

    /**
     * The [limit] games whose spread moved the most (by absolute points) between a baseline snapshot and
     * the newest one. The baseline is the newest snapshot taken at or before [sinceEpochMs], or the oldest
     * snapshot if none qualifies. Games missing a spread on either end are excluded.
     */
    fun biggestMovers(sinceEpochMs: Long, limit: Int = 10): List<Mover> {
        val sorted = snapshots.sortedBy { it.takenAtEpochMs }
        if (sorted.isEmpty()) return emptyList()
        val newest = sorted.last()
        val baseline = sorted.lastOrNull { it.takenAtEpochMs <= sinceEpochMs } ?: sorted.first()
        val baselineByGame = baseline.lines.associateBy { it.gameId }
        return newest.lines.mapNotNull { cur ->
            val prior = baselineByGame[cur.gameId] ?: return@mapNotNull null
            val from = prior.homeSpread
            val to = cur.homeSpread
            if (from == null || to == null) return@mapNotNull null
            Mover(cur.gameId, cur.week, from, to, to - from)
        }.sortedByDescending { abs(it.delta) }.take(limit)
    }

    /**
     * Buckets every (earlier snapshot, game) pair by how many weeks ahead of the game's week that snapshot
     * was taken (`k = game.week - snapshot.currentWeek`, 0..17), and compares each earlier line to the
     * game's closing line: the last snapshot recorded while `currentWeek == game.week`. The move is measured
     * both in raw spread points and, via [Probability.winProbabilityFromSpread], in logit-probability space
     * so buckets are comparable regardless of how close to a pick'em the game was.
     */
    fun calibration(): Calibration {
        val closing = closingInfo()
        val diffsByWeek = HashMap<Int, MutableList<Double>>()
        val movesByWeek = HashMap<Int, MutableList<Double>>()
        for (s in snapshots) {
            for (rec in s.lines) {
                val close = closing[rec.gameId] ?: continue
                if (s.takenAtEpochMs >= close.takenAtEpochMs) continue
                val k = rec.week - s.currentWeek
                if (k < 0 || k > REGULAR_SEASON_WEEKS - 1) continue
                val earlySpread = rec.homeSpread ?: continue
                val closeSpread = close.record.homeSpread ?: continue
                val pEarly = Probability.winProbabilityFromSpread(-earlySpread)
                val pClose = Probability.winProbabilityFromSpread(-closeSpread)
                diffsByWeek.getOrPut(k) { mutableListOf() } += logit(pClose) - logit(pEarly)
                movesByWeek.getOrPut(k) { mutableListOf() } += abs(closeSpread - earlySpread)
            }
        }
        val buckets = (0..REGULAR_SEASON_WEEKS - 1).mapNotNull { k ->
            val diffs = diffsByWeek[k] ?: return@mapNotNull null
            CalibrationBucket(
                weeksAhead = k,
                samples = diffs.size,
                meanAbsSpreadMove = movesByWeek[k].orEmpty().average(),
                logitSd = stdDev(diffs),
            )
        }
        val fitPoints = buckets.filter { it.samples >= 5 }
        val (tauBase, tauPerWeek) = if (fitPoints.size >= 2) fitLine(fitPoints) else null to null
        return Calibration(buckets, tauBase, tauPerWeek)
    }

    /** For each game id, the timestamp and line of the last snapshot recorded while `currentWeek == game.week`. */
    private fun closingInfo(): Map<String, ClosingInfo> {
        val closing = HashMap<String, ClosingInfo>()
        for (s in snapshots.sortedBy { it.takenAtEpochMs }) {
            for (rec in s.lines) {
                if (s.currentWeek == rec.week) closing[rec.gameId] = ClosingInfo(s.takenAtEpochMs, rec)
            }
        }
        return closing
    }

    private data class ClosingInfo(val takenAtEpochMs: Long, val record: LineRecord)

    companion object {
        private const val MAX_SNAPSHOTS = 60

        /** Natural-log odds of [p]: ln(p / (1 - p)). Kept local so [Probability] stays untouched. */
        private fun logit(p: Double): Double {
            val c = p.coerceIn(1e-6, 1.0 - 1e-6)
            return ln(c / (1.0 - c))
        }

        /** Sample standard deviation; 0.0 for fewer than two observations. */
        private fun stdDev(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
            return sqrt(variance)
        }

        /** Ordinary least squares fit of `y ≈ intercept + slope * x`; returns (intercept, slope). */
        private fun fitLine(points: List<CalibrationBucket>): Pair<Double, Double> {
            val n = points.size.toDouble()
            val sumX = points.sumOf { it.weeksAhead.toDouble() }
            val sumY = points.sumOf { it.logitSd }
            val sumXX = points.sumOf { it.weeksAhead.toDouble() * it.weeksAhead }
            val sumXY = points.sumOf { it.weeksAhead * it.logitSd }
            val denom = n * sumXX - sumX * sumX
            if (denom == 0.0) return 0.0 to 0.0
            val slope = (n * sumXY - sumX * sumY) / denom
            val intercept = (sumY - slope * sumX) / n
            return intercept to slope
        }
    }
}

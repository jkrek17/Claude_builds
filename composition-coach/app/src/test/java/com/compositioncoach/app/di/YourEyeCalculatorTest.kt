package com.compositioncoach.app.di

import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.model.MetricCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YourEyeCalculatorTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun r(
        n: Int,
        score: Int,
        verdict: String = "GOOD",
        ageDays: Long = 1,
        kept: Boolean? = null,
        retaken: Boolean = false,
        cue: MetricCategory? = null,
        followed: List<MetricCategory> = emptyList(),
    ) = JournalRecord(
        id = "content://p/$n",
        timestampMs = now - ageDays * day,
        score = score,
        verdict = verdict,
        scene = "GENERAL",
        outcome = "ANYTHING",
        cueCategoryAtCapture = cue?.name,
        kept = kept,
        retaken = retaken,
        followedCategories = followed.map { it.name },
    )

    @Test
    fun `an empty journal gives an empty snapshot`() {
        assertEquals(YourEyeSnapshot(), YourEyeCalculator.compute(emptyList(), AdaptiveWeights.NEUTRAL, now))
    }

    @Test
    fun `keepers count Keeper verdicts from the last 30 days that were not retaken`() {
        val records = listOf(
            r(1, 85, "KEEPER", ageDays = 2, kept = true),
            r(2, 88, "KEEPER", ageDays = 3), // undecided counts as kept: Keep is the default
            r(3, 90, "KEEPER", ageDays = 40, kept = true), // too old
            r(4, 86, "KEEPER", ageDays = 1, kept = false, retaken = true),
            r(5, 70, "GOOD", ageDays = 1, kept = true),
        )
        assertEquals(2, YourEyeCalculator.compute(records, AdaptiveWeights.NEUTRAL, now).keepersLast30Days)
    }

    @Test
    fun `follow rate is followed over followed plus still showing at capture`() {
        val records = listOf(
            r(1, 60, followed = listOf(MetricCategory.HORIZON, MetricCategory.HORIZON), cue = MetricCategory.HORIZON),
            r(2, 60, followed = listOf(MetricCategory.HORIZON), cue = MetricCategory.HEADROOM),
            r(3, 60, cue = MetricCategory.HEADROOM),
            r(4, 60, cue = MetricCategory.HEADROOM),
        )
        val snapshot = YourEyeCalculator.compute(records, AdaptiveWeights.NEUTRAL, now)
        assertEquals(FollowRate(3, 4), snapshot.followRates[MetricCategory.HORIZON])
        assertEquals(0.75f, snapshot.followRates.getValue(MetricCategory.HORIZON).rate, 1e-6f)
        assertEquals(FollowRate(0, 3), snapshot.followRates[MetricCategory.HEADROOM])
        assertEquals(MetricCategory.HORIZON, snapshot.mostFollowed)
        assertEquals(MetricCategory.HEADROOM, snapshot.leastFollowed)
        assertNull(snapshot.followRates[MetricCategory.LIGHT])
    }

    @Test
    fun `best three are the highest-scoring kept photos`() {
        val records = listOf(
            r(1, 70, kept = true),
            r(2, 95, kept = false, retaken = true),
            r(3, 91, kept = true),
            r(4, 80),
            r(5, 88, kept = true),
            r(6, 60, kept = true),
        )
        val best = YourEyeCalculator.compute(records, AdaptiveWeights.NEUTRAL, now).bestThree
        assertEquals(listOf("content://p/3", "content://p/5", "content://p/4"), best.map { it.photoUri })
        assertEquals(listOf(91, 88, 80), best.map { it.score })
    }

    @Test
    fun `tendencies come from the taste model`() {
        val weights = AdaptiveWeights.of(mapOf(MetricCategory.SUBJECT_PLACEMENT to 0.7f))
        val snapshot = YourEyeCalculator.compute(listOf(r(1, 50)), weights, now)
        assertEquals(weights.summaryTendencies(), snapshot.tendencies)
        assertEquals(listOf("You tend to keep centred subjects"), snapshot.tendencies)
        assertEquals(1, snapshot.totalPhotos)
    }
}

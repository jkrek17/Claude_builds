package com.compositioncoach.app.di

import android.net.Uri
import androidx.core.net.toUri
import com.compositioncoach.app.settings.SettingsRepository
import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.engine.Verdict
import com.compositioncoach.composition.model.MetricCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How often the user acted on one kind of live advice: [followed] of the [shown] times it was the headline. */
data class FollowRate(val followed: Int, val shown: Int) {
    val rate: Float get() = if (shown == 0) 0f else followed.toFloat() / shown
}

/** One of the user's best shots, for the "Your eye" screen. [photoUri] is the Uri string; see [uri]. */
data class BestShot(val photoUri: String, val score: Int, val verdict: String, val timestampMs: Long) {
    val uri: Uri get() = photoUri.toUri()
}

/**
 * Everything the "Your eye" screen shows. INTEGRATOR: the UI engineer defines `ui/youreye/YourEyeData`; this
 * type has the same fields so mapping one onto the other is a field-for-field copy (or a typealias).
 *
 * @param tendencies up to three plain sentences from the taste model (`AdaptiveWeights.summaryTendencies()`).
 * @param keepersLast30Days photos from the last 30 days with a Keeper verdict that were not retaken.
 * @param followRates per category of live advice: followed / shown (shown = followed + still showing at a capture).
 * @param bestThree the highest-scoring photos the user kept (not retaken), best first.
 * @param totalPhotos journal records in all.
 */
data class YourEyeSnapshot(
    val tendencies: List<String> = emptyList(),
    val keepersLast30Days: Int = 0,
    val followRates: Map<MetricCategory, FollowRate> = emptyMap(),
    val bestThree: List<BestShot> = emptyList(),
    val totalPhotos: Int = 0,
) {
    /** The advice the user follows most and least (needs at least [MIN_SHOWN] showings to count). */
    val mostFollowed: MetricCategory? get() = followRates.filterValues { it.shown >= MIN_SHOWN }.maxByOrNull { it.value.rate }?.key
    val leastFollowed: MetricCategory? get() = followRates.filterValues { it.shown >= MIN_SHOWN }.minByOrNull { it.value.rate }?.key

    companion object {
        const val MIN_SHOWN = 3
    }
}

/** Pure "Your eye" maths over the journal, unit-tested in `YourEyeCalculatorTest`. */
object YourEyeCalculator {
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    fun compute(records: List<JournalRecord>, weights: AdaptiveWeights, nowMs: Long): YourEyeSnapshot {
        val kept = records.filter { it.kept != false && !it.retaken }
        val keepers = kept.count { it.timestampMs >= nowMs - WINDOW_MS && it.verdict == Verdict.KEEPER.name }

        val followed = HashMap<MetricCategory, Int>()
        val ignored = HashMap<MetricCategory, Int>()
        for (r in records) {
            r.followedCategories.mapNotNull(::category).forEach { followed.merge(it, 1, Int::plus) }
            category(r.cueCategoryAtCapture)?.let { ignored.merge(it, 1, Int::plus) }
        }
        val rates = (followed.keys + ignored.keys).associateWith { c ->
            val f = followed[c] ?: 0
            FollowRate(followed = f, shown = f + (ignored[c] ?: 0))
        }

        val best = kept.sortedWith(compareByDescending<JournalRecord> { it.score }.thenByDescending { it.timestampMs })
            .take(BEST_COUNT)
            .map { BestShot(it.photoUri, it.score, it.verdict, it.timestampMs) }

        return YourEyeSnapshot(
            tendencies = weights.summaryTendencies(),
            keepersLast30Days = keepers,
            followRates = rates,
            bestThree = best,
            totalPhotos = records.size,
        )
    }

    private fun category(name: String?): MetricCategory? = name?.let { n -> MetricCategory.entries.firstOrNull { it.name == n } }

    private const val BEST_COUNT = 3
}

/** "Your eye" as a [Flow]: recomputed whenever the journal or the taste model changes. */
class YourEyeRepository(
    private val journal: PhotoJournal,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val snapshot: Flow<YourEyeSnapshot> = combine(
        journal.records,
        settings.settings.map { it.adaptiveWeights }.distinctUntilChanged(),
    ) { records, weights -> YourEyeCalculator.compute(records, AdaptiveWeights.parse(weights), clock()) }
}

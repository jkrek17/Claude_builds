package com.compositioncoach.app.ui.youreye

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Everything the "Your eye" screen shows (`docs/PRODUCT.md`, "Learning what you like"). Supplied by the state
 * engineer from `PhotoJournal` and `AdaptiveWeights` through a [YourEyeSource].
 *
 * @param tendencies plain sentences, strongest first (`AdaptiveWeights.summaryTendencies()`); the screen shows three.
 * @param keepers30d photos kept in the last 30 days.
 * @param followRates advice label (the live chip words, e.g. "Closer") to the share of times it was followed, 0..1.
 * @param best the best kept photos and their scores; the screen shows the top three.
 */
data class YourEyeData(
    val tendencies: List<String>,
    val keepers30d: Int,
    val followRates: List<Pair<String, Float>>,
    val best: List<Pair<Uri, Int>>,
) {
    companion object {
        val EMPTY = YourEyeData(emptyList(), 0, emptyList(), emptyList())
    }
}

/** Where [YourEyeData] comes from, and the one-tap reset. The state engineer implements this. */
interface YourEyeSource {
    val data: Flow<YourEyeData>

    /** Forget the taste model and the journal's learning signals; photos are untouched. */
    suspend fun resetCoachingPreferences()
}

/** A [YourEyeSource] over a fixed value: previews, tests, and the placeholder until the journal exists. */
class FakeYourEyeSource(initial: YourEyeData = YourEyeData.EMPTY) : YourEyeSource {
    private val state = MutableStateFlow(initial)
    override val data: Flow<YourEyeData> = state
    var resets = 0
        private set

    override suspend fun resetCoachingPreferences() {
        resets++
        state.value = YourEyeData.EMPTY
    }
}

/** Pure presentation rules for "Your eye" (`YourEyeFormatterTest`). Quiet: no streaks, no badges. */
object YourEyeFormatter {
    const val MAX_TENDENCIES = 3
    const val MAX_BEST = 3

    /** "Keep a few photos and this fills in." until there is anything at all to say. */
    fun isEmpty(data: YourEyeData): Boolean =
        data.keepers30d == 0 && data.tendencies.isEmpty() && data.best.isEmpty() && data.followRates.isEmpty()

    /** Up to three tendencies, each a finished sentence. */
    fun tendencies(data: YourEyeData): List<String> =
        data.tendencies.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TENDENCIES)
            .map { if (it.endsWith('.')) it else "$it." }

    /** A follow rate as "N in 10 times". */
    fun inTen(rate: Float): Int = (rate.coerceIn(0f, 1f) * 10f).roundToInt()

    /** The advice followed most, as (label, N in 10). */
    fun followedMost(data: YourEyeData): Pair<String, Int>? =
        data.followRates.maxByOrNull { it.second }?.let { it.first to inTen(it.second) }

    /** The advice skipped most; only when it is a different piece of advice from [followedMost]. */
    fun followedLeast(data: YourEyeData): Pair<String, Int>? {
        if (data.followRates.size < 2) return null
        val least = data.followRates.minByOrNull { it.second } ?: return null
        val most = data.followRates.maxByOrNull { it.second }
        return if (least.first == most?.first) null else least.first to inTen(least.second)
    }

    /** The best three, highest score first. */
    fun best(data: YourEyeData): List<Pair<Uri, Int>> = data.best.sortedByDescending { it.second }.take(MAX_BEST)
}

/** Holds the screen's [YourEyeData] and runs the reset. */
class YourEyeViewModel(private val source: YourEyeSource) : ViewModel() {
    val data: StateFlow<YourEyeData> =
        source.data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YourEyeData.EMPTY)

    fun reset() {
        viewModelScope.launch { source.resetCoachingPreferences() }
    }

    companion object {
        fun factory(source: YourEyeSource): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(YourEyeViewModel::class.java))
                return YourEyeViewModel(source) as T
            }
        }
    }
}

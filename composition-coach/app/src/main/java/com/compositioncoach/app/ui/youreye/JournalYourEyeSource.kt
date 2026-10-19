package com.compositioncoach.app.ui.youreye

import android.content.res.Resources
import androidx.annotation.StringRes
import com.compositioncoach.app.R
import com.compositioncoach.app.di.YourEyeSnapshot
import com.compositioncoach.composition.model.MetricCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The real [YourEyeSource]: the photo journal and taste model ([YourEyeSnapshot] from
 * `di/YourEyeRepository`) mapped onto the screen's [YourEyeData] by [YourEyeMapper]. [reset] is
 * `CameraViewModel.onResetCoachingPreferences`, the same call as Settings > More > Reset coaching preferences.
 */
class JournalYourEyeSource(
    snapshot: Flow<YourEyeSnapshot>,
    private val label: (MetricCategory) -> String,
    private val reset: () -> Unit,
) : YourEyeSource {
    override val data: Flow<YourEyeData> = snapshot.map { YourEyeMapper.map(it, label) }

    override suspend fun resetCoachingPreferences() = reset()
}

/** [YourEyeSnapshot] to [YourEyeData]; pure (`YourEyeMapperTest`). */
object YourEyeMapper {
    /**
     * Follow rates count only advice shown at least [YourEyeSnapshot.MIN_SHOWN] times (fewer says nothing about
     * the user), labelled in plain words by [label]; the best three keep their order and scores.
     */
    fun map(snapshot: YourEyeSnapshot, label: (MetricCategory) -> String): YourEyeData = YourEyeData(
        tendencies = snapshot.tendencies,
        keepers30d = snapshot.keepersLast30Days,
        followRates = snapshot.followRates
            .filterValues { it.shown >= YourEyeSnapshot.MIN_SHOWN }
            .entries
            .sortedBy { it.key.ordinal }
            .map { (category, rate) -> label(category) to rate.rate },
        best = snapshot.bestThree.map { it.uri to it.score },
    )
}

/** Plain-word names for each kind of advice, as string resources (Your eye's "Advice you take most"). */
object AdviceLabels {
    @StringRes
    fun resId(category: MetricCategory): Int = when (category) {
        MetricCategory.SUBJECT_PLACEMENT -> R.string.advice_subject_placement
        MetricCategory.HORIZON -> R.string.advice_horizon
        MetricCategory.HEADROOM -> R.string.advice_headroom
        MetricCategory.CROPPING -> R.string.advice_cropping
        MetricCategory.LOOKING_ROOM -> R.string.advice_looking_room
        MetricCategory.EDGE_TENSION -> R.string.advice_edge_tension
        MetricCategory.BACKGROUND_DISTRACTION -> R.string.advice_background
        MetricCategory.SUBJECT_SEPARATION -> R.string.advice_separation
        MetricCategory.BALANCE -> R.string.advice_balance
        MetricCategory.SYMMETRY -> R.string.advice_symmetry
        MetricCategory.NEGATIVE_SPACE -> R.string.advice_negative_space
        MetricCategory.LEADING_LINES -> R.string.advice_leading_lines
        MetricCategory.SCENE_SPECIFIC -> R.string.advice_scene_specific
        MetricCategory.LIGHT -> R.string.advice_light
    }

    fun from(resources: Resources): (MetricCategory) -> String = { resources.getString(resId(it)) }
}

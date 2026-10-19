package com.compositioncoach.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.R
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.composition.model.CropSuggestion

/** What the card's interactive pieces are showing; owned by [ReviewScreen] because the photo reacts to it. */
data class CardViewState(
    val selectedCrop: CropSuggestion? = null,
    val whatIfShowing: Boolean = false,
    val portraitShowing: Boolean = false,
    val savedCopy: Boolean = false,
)

/** Everything the card can ask for; see `ReviewActions` for where each goes. */
data class CardCallbacks(
    val onViewChange: (CardViewState) -> Unit = {},
    val onSaveCrop: (CropSuggestion) -> Unit = {},
    val onSaveProcessedCopy: () -> Unit = {},
    val onNotHelpful: () -> Unit = {},
    val onShootAgain: () -> Unit = {},
)

/**
 * The after-action card (`docs/PRODUCT.md`, "After the shot"): readable in five seconds, rewarding a second look.
 * Score ring and verdict word; up to three green "What worked" lines; one amber "Try next time" with its "+N"
 * and a quiet "Not helpful"; "Better crop?" cards; the "What if" and "Portrait" hold chips; Details, collapsed.
 * While [ReviewEntry.analysing] the sections breathe ([analysingShimmer]) and the verdict comes from the live score.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AfterActionCard(
    entry: ReviewEntry,
    painter: Painter,
    photoAspect: Float,
    view: CardViewState,
    callbacks: CardCallbacks,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val result = AfterActionLogic.resultOf(entry)
    val verdict = AfterActionLogic.verdict(entry.report, result)
    val worked = AfterActionLogic.worked(entry.report, result)
    val tryNext = AfterActionLogic.tryNext(entry.report)
    val shimmer = Modifier.analysingShimmer(entry.analysing, animationsEnabled)
    var notHelpfulSent by rememberSaveable(entry.photoUri) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score = result.score, isShootReady = result.isShootReady)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(AfterActionLogic.verdictRes(verdict)),
                    color = AfterActionLogic.verdictColor(verdict),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (entry.analysing) {
                    Text(stringResource(R.string.card_analysing), color = OnScrimMuted, style = MaterialTheme.typography.bodySmall, modifier = shimmer)
                }
            }
        }

        if (worked.isNotEmpty()) {
            SectionLabel(stringResource(R.string.card_what_worked), shimmer)
            Column(shimmer) { worked.forEach { DotLine(it, Accent.Ready) } }
        }

        if (tryNext != null) {
            SectionLabel(stringResource(R.string.card_try_next_time), shimmer)
            Row(shimmer, verticalAlignment = Alignment.Top) {
                DotLine(tryNext.text, Accent.Warn, Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.card_gain, tryNext.gain),
                    color = Accent.Warn,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onShootAgain) { Text(stringResource(R.string.card_shoot_again)) }
                Spacer(Modifier.weight(1f))
                if (notHelpfulSent) {
                    Text(stringResource(R.string.card_not_helpful_thanks), color = OnScrimMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    TextButton(onClick = {
                        notHelpfulSent = true
                        callbacks.onNotHelpful()
                    }) { Text(stringResource(R.string.card_not_helpful), color = OnScrimMuted) }
                }
            }
        } else if (AfterActionLogic.nothingToChange(entry)) {
            Text(
                stringResource(R.string.card_nothing_to_change),
                color = OnScrim.copy(alpha = 0.88f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (entry.crops.isNotEmpty()) {
            SectionLabel(stringResource(R.string.card_better_crop), shimmer)
            CropCardsRow(
                painter = painter,
                photoAspect = photoAspect,
                crops = entry.crops,
                selected = view.selectedCrop,
                onSelect = { callbacks.onViewChange(view.copy(selectedCrop = it, whatIfShowing = false, portraitShowing = false, savedCopy = false)) },
            )
            view.selectedCrop?.let { crop ->
                TextButton(onClick = { callbacks.onSaveCrop(crop) }) { Text(stringResource(R.string.card_save_crop)) }
            }
        }

        val whatIf = AfterActionLogic.whatIfScores(entry)
        if (whatIf != null || entry.processedPreviewUri != null) {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (whatIf != null) {
                    HoldChip(
                        text = stringResource(R.string.card_what_if),
                        description = stringResource(R.string.card_what_if_hint),
                        showing = view.whatIfShowing,
                        onShowingChange = { callbacks.onViewChange(view.copy(whatIfShowing = it, selectedCrop = null, portraitShowing = false)) },
                    )
                }
                if (entry.processedPreviewUri != null) {
                    HoldChip(
                        text = stringResource(R.string.card_portrait),
                        description = stringResource(R.string.card_portrait_hint),
                        showing = view.portraitShowing,
                        onShowingChange = { callbacks.onViewChange(view.copy(portraitShowing = it, selectedCrop = null, whatIfShowing = false)) },
                    )
                    TextButton(onClick = callbacks.onSaveProcessedCopy) { Text(stringResource(R.string.card_save_copy)) }
                }
            }
        }

        if (view.savedCopy) {
            Text(stringResource(R.string.card_saved_copy), color = Accent.Ready, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(4.dp))
        ReviewDetails(entry)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier) {
    Text(
        text = text,
        color = OnScrimMuted,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

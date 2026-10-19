package com.compositioncoach.app.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.R
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.Background
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.composition.model.CropAspect
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.NormalizedRect

/** Height of every crop card; widths follow each crop's own shape ([CropRender.cardWidth]). */
private val CROP_CARD_HEIGHT = 72.dp

/**
 * A press-and-hold chip ("What if", "Portrait"): while held, [onShowingChange] is true and the photo shows the
 * alternative; release returns to the original. TalkBack cannot hold, so for it a click toggles.
 */
@Composable
fun HoldChip(
    text: String,
    description: String,
    showing: Boolean,
    onShowingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(onShowingChange)
    val isShowing by rememberUpdatedState(showing)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    current(true)
                    tryAwaitRelease()
                    current(false)
                })
            }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                selected = showing
                onClick {
                    current(!isShowing)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (showing) Background else OnScrim,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (showing) OnScrim else Scrim)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * "Better crop?": up to three cards, each the same photo clipped to the crop ([ClippedPhoto]) with its predicted
 * score. Tapping one selects it for the full-size preview; tapping it again clears the selection.
 */
@Composable
fun CropCardsRow(
    painter: Painter,
    photoAspect: Float,
    crops: List<CropSuggestion>,
    selected: CropSuggestion?,
    onSelect: (CropSuggestion?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val heightPx = with(LocalDensity.current) { CROP_CARD_HEIGHT.toPx() }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        crops.take(MAX_CROPS).forEach { crop ->
            val isSelected = crop == selected
            val widthDp = with(LocalDensity.current) { CropRender.cardWidth(crop.rect, photoAspect, heightPx).toDp() }
            val description = stringResource(R.string.card_crop_description, crop.aspect.label, crop.predictedScore)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onSelect(if (isSelected) null else crop) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        this.selected = isSelected
                    },
            ) {
                ClippedPhoto(
                    painter = painter,
                    crop = crop.rect,
                    modifier = Modifier
                        .height(CROP_CARD_HEIGHT)
                        .width(widthDp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) Accent.Ready else OnScrim.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
                )
                Text(
                    text = crop.predictedScore.toString(),
                    color = OnScrim,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

const val MAX_CROPS = 3

@Preview(name = "Crop cards", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CropCardsPreview() {
    val crops = listOf(
        CropSuggestion(NormalizedRect(0.1f, 0.1f, 0.85f, 0.85f), CropAspect.ORIGINAL, 84, "Tighter"),
        CropSuggestion(NormalizedRect(0.2f, 0.1f, 0.8f, 0.7f), CropAspect.SQUARE, 81, "Square"),
        CropSuggestion(NormalizedRect(0.15f, 0.05f, 0.75f, 0.85f), CropAspect.FOUR_FIVE, 79, "4:5 portrait"),
    )
    CompositionCoachTheme {
        CropCardsRow(ColorPainter(Scrim), photoAspect = 0.75f, crops = crops, selected = crops[1], onSelect = {})
    }
}

@Preview(name = "Hold chips", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun HoldChipsPreview() {
    CompositionCoachTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldChip(text = stringResource(R.string.card_what_if), description = "", showing = false, onShowingChange = {})
            HoldChip(text = stringResource(R.string.card_portrait), description = "", showing = true, onShowingChange = {})
        }
    }
}

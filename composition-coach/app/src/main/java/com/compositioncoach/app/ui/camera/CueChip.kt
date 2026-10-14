package com.compositioncoach.app.ui.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.Scrim

/** The chip never grows past this share of the preview's short side (`docs/COACHING_UI.md`, "Chip"). */
const val CUE_CHIP_MAX_WIDTH_FRACTION = 0.55f

/**
 * The only live text on the camera screen: 1-3 words labelling whatever spatial cue is showing, on the
 * standard scrim, crossfading over [Motion.CROSSFADE_MS] when the wording changes (the engine's smoother
 * already prevents changes faster than about a second, so this never strobes).
 *
 * TalkBack hears [description] rather than the visible words — "Move slightly right" instead of the bare
 * "Slightly right" — and hears it unprompted as advice changes, because the chip is a polite live region.
 * Sentences are never shown here; they belong on the review screen.
 *
 * Does *not* rotate itself: callers wrap it in [RotatedChrome] so it stays upright to the photographer
 * wherever on the frame it is anchored.
 */
@Composable
fun CueChip(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = Dp.Unspecified,
    emphasized: Boolean = false,
    animationsEnabled: Boolean = true,
) {
    val crossfadeMs = motionDuration(Motion.CROSSFADE_MS, animationsEnabled)
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(crossfadeMs)) togetherWith fadeOut(tween(crossfadeMs)) },
        label = "cueChip",
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    ) { shown ->
        Text(
            text = shown,
            color = if (emphasized) Accent.Ready else OnScrim,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
                .clip(RoundedCornerShape(16.dp))
                .background(Scrim)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Preview(name = "Cue chip", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CueChipPreview() {
    CompositionCoachTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CueChip(text = "Slightly right", description = "Move slightly right")
            CueChip(text = "Step back", description = "Step back")
            CueChip(text = "Give looking room", description = "Give the subject room to look into")
            CueChip(text = GuidanceFormatter.SHOOT_TEXT, description = "Ready to shoot", emphasized = true)
        }
    }
}

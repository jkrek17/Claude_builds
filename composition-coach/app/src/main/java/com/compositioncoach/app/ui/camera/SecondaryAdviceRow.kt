package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WidthNormal
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity

/** The chip's visible diameter, per `docs/COACHING_UI.md`. Its *tap* target is the 48dp minimum. */
private val SECONDARY_CHIP_SIZE = 28.dp

/**
 * The category -> Material Symbols (outlined) icon table from `docs/COACHING_UI.md`. The seven categories
 * the spec names are fixed; the rest get the nearest outlined symbol so a new analyzer never renders a
 * blank chip:
 *
 * | Category | Icon |
 * |---|---|
 * | HORIZON | horizontal rule (a level line) |
 * | HEADROOM | vertical align top |
 * | EDGE_TENSION | crop |
 * | BACKGROUND_DISTRACTION | layers |
 * | SUBJECT_SEPARATION | contrast |
 * | BALANCE | balance |
 * | SYMMETRY | flip |
 * | SUBJECT_PLACEMENT | centre focus |
 * | CROPPING | crop free |
 * | LOOKING_ROOM | visibility |
 * | NEGATIVE_SPACE | width |
 * | LEADING_LINES | timeline |
 * | SCENE_SPECIFIC | landscape |
 */
fun categoryIcon(category: MetricCategory): ImageVector = when (category) {
    MetricCategory.HORIZON -> Icons.Outlined.HorizontalRule
    MetricCategory.HEADROOM -> Icons.Outlined.VerticalAlignTop
    MetricCategory.EDGE_TENSION -> Icons.Outlined.Crop
    MetricCategory.BACKGROUND_DISTRACTION -> Icons.Outlined.Layers
    MetricCategory.SUBJECT_SEPARATION -> Icons.Outlined.Contrast
    MetricCategory.BALANCE -> Icons.Outlined.Balance
    MetricCategory.SYMMETRY -> Icons.Outlined.Flip
    MetricCategory.SUBJECT_PLACEMENT -> Icons.Outlined.CenterFocusWeak
    MetricCategory.CROPPING -> Icons.Outlined.CropFree
    MetricCategory.LOOKING_ROOM -> Icons.Outlined.Visibility
    MetricCategory.NEGATIVE_SPACE -> Icons.Outlined.WidthNormal
    MetricCategory.LEADING_LINES -> Icons.Outlined.Timeline
    MetricCategory.SCENE_SPECIFIC -> Icons.Outlined.Landscape
}

/**
 * Advice that isn't the headline, as up to two small icon chips — never sentences. Balanced shows one,
 * Coach two, Minimal none (the caller trims the list via [GuidanceFormatter.secondary]).
 *
 * At Coach level a tap replaces the cue chip with that recommendation's one-line explanation for three
 * seconds ([onExplain]); at other levels the chips are not interactive. Either way each chip carries the
 * recommendation's own title as its content description, so TalkBack can read what the glyph means.
 *
 * Laid out inside the physical-top chrome stack, so it counter-rotates with the rest of it.
 */
@Composable
fun SecondaryAdviceRow(
    recommendations: List<Recommendation>,
    tappable: Boolean,
    onExplain: (Recommendation) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (recommendations.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        recommendations.forEach { recommendation ->
            SecondaryAdviceChip(recommendation = recommendation, tappable = tappable, onExplain = onExplain)
        }
    }
}

@Composable
private fun SecondaryAdviceChip(recommendation: Recommendation, tappable: Boolean, onExplain: (Recommendation) -> Unit) {
    val clickable = if (tappable) {
        Modifier.minimumInteractiveComponentSize().clickable { onExplain(recommendation) }
    } else {
        Modifier
    }
    Box(
        modifier = clickable.semantics { contentDescription = recommendation.title },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(SECONDARY_CHIP_SIZE).background(Scrim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = categoryIcon(recommendation.category),
                contentDescription = null,
                tint = OnScrim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Preview(name = "Secondary advice row", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun SecondaryAdviceRowPreview() {
    fun rec(category: MetricCategory, title: String) = Recommendation(
        id = "preview.${category.name}",
        category = category,
        priority = Priority.MEDIUM,
        confidence = 0.8f,
        severity = Severity.LOW,
        title = title,
        instruction = title,
        direction = Direction.NONE,
    )
    CompositionCoachTheme {
        SecondaryAdviceRow(
            recommendations = listOf(
                rec(MetricCategory.HORIZON, "Horizon is tilted"),
                rec(MetricCategory.BACKGROUND_DISTRACTION, "Busy background"),
            ),
            tappable = true,
            onExplain = {},
            modifier = Modifier.padding(8.dp),
        )
    }
}

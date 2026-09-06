package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.compositioncoach.composition.model.Direction

/**
 * The vector icon for a [Direction], used by [GuidanceBanner] instead of a text glyph (per the design
 * pass — a real arrow icon reads more like a native camera app than a Unicode character). Deliberately
 * NOT auto-mirrored: these describe a physical camera movement, not a layout-direction-relative one, so
 * "right" must stay right regardless of locale. Null for [Direction.NONE] — nothing to draw.
 *
 * [GuidanceFormatter.glyphFor] keeps the text-glyph mapping alongside this for accessibility strings and
 * anywhere a plain character is still appropriate; it is no longer rendered as visible UI here.
 */
fun directionIconFor(direction: Direction): ImageVector? = when (direction) {
    Direction.LEFT -> Icons.Filled.KeyboardArrowLeft
    Direction.RIGHT -> Icons.Filled.KeyboardArrowRight
    Direction.UP -> Icons.Filled.KeyboardArrowUp
    Direction.DOWN -> Icons.Filled.KeyboardArrowDown
    Direction.CLOSER -> Icons.Filled.ZoomInMap
    Direction.BACK -> Icons.Filled.ZoomOutMap
    Direction.ROTATE_CLOCKWISE -> Icons.Filled.RotateRight
    Direction.ROTATE_COUNTER_CLOCKWISE -> Icons.Filled.RotateLeft
    Direction.NONE -> null
}

/** A small, consistent-stroke direction icon at [size], or nothing when [direction] has none. */
@Composable
fun DirectionIcon(direction: Direction, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val icon = directionIconFor(direction) ?: return
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

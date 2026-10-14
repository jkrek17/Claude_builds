package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * Pure size/offset arithmetic behind [RotatedChrome] — kept free of Compose types so it is unit-testable
 * on the plain JVM (see `RotatedChromeMathTest`). `Modifier.rotate`/`graphicsLayer { rotationZ = ... }`
 * only ever rotates the already-measured *pixels*; it never changes what the layout system thinks that
 * content's size is. That's fine for a square icon, but a wide banner rotated 90 degrees in place still
 * reports its original (wide, short) size to its parent, so it overflows whatever box it was aligned into
 * — this is Bug 2's "the rotated banner runs across the middle of the preview" — instead of the (narrow,
 * tall) footprint it actually occupies once rotated. [RotatedChrome] fixes that by measuring its content
 * with width/height swapped for a 90/270 rotation and reporting the swapped size to ITS parent instead.
 */
object RotatedChromeMath {

    /** Whether [deviceRotationDegrees] swaps width and height (90 or 270) rather than leaving them be (0/180). */
    fun swapsAxes(deviceRotationDegrees: Int): Boolean {
        val theta = ((deviceRotationDegrees % 360) + 360) % 360
        return theta == 90 || theta == 270
    }

    /** The box size [RotatedChrome] reports to its parent, given its content's own (unrotated) measured size. */
    fun boxSize(contentWidth: Int, contentHeight: Int, deviceRotationDegrees: Int): Pair<Int, Int> =
        if (swapsAxes(deviceRotationDegrees)) contentHeight to contentWidth else contentWidth to contentHeight

    /**
     * Where (top-left, in the *reported* box's coordinates) to place the unrotated content so that once
     * it's rotated 90/270 about its own centre, it lands centred within that box.
     *
     * Rotating about the content's own centre never moves that centre point, so centring in the box
     * reduces to solving `place.x + contentWidth / 2 == boxWidth / 2` (and the `y` counterpart) for
     * `place.x` given `boxWidth = contentHeight` (from [boxSize]): `place.x = (contentHeight -
     * contentWidth) / 2`, and symmetrically `place.y = (contentWidth - contentHeight) / 2`. At 0/180 the
     * box is already the same size as the content (no swap), so the offset is zero either way.
     */
    fun placementOffset(contentWidth: Int, contentHeight: Int, deviceRotationDegrees: Int): Pair<Int, Int> =
        if (swapsAxes(deviceRotationDegrees)) {
            (contentHeight - contentWidth) / 2 to (contentWidth - contentHeight) / 2
        } else {
            0 to 0
        }
}

/**
 * Wraps [content] so it rotates in place, Pixel-style, to stay upright to the person holding the phone at
 * [deviceRotationDegrees] (see [rememberControlCounterRotation] / [OverlayMapper.uprightChromeAngleDegrees]
 * for the angle, and the class doc on [RotatedChromeMath] for why a plain `Modifier.rotate` isn't enough
 * on its own). At 90/270 [content] is measured with min/max width and height swapped, and the box reports
 * the swapped size to its own parent — so a caller that aligns this box against an edge (see
 * `CameraScreen`'s `chromeStackAlignment`) gets the content's *actual*, rotated footprint, not its
 * unrotated one. Every chrome element that needs to counter-rotate — the top bar's icons, the bottom bar's
 * thumbnail/shutter/lens switch, and the score badge + guidance banner stack — goes through this, so they
 * all share one rotation/measurement mechanism (see `app/README.md`'s rotation section).
 */
@Composable
fun RotatedChrome(
    deviceRotationDegrees: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val angle = rememberControlCounterRotation(deviceRotationDegrees)
    val swap = RotatedChromeMath.swapsAxes(deviceRotationDegrees)
    Box(
        modifier = modifier.layout { measurable, constraints ->
            val childConstraints = if (swap) {
                Constraints(
                    minWidth = constraints.minHeight,
                    maxWidth = constraints.maxHeight,
                    minHeight = constraints.minWidth,
                    maxHeight = constraints.maxWidth,
                )
            } else {
                constraints
            }
            val placeable = measurable.measure(childConstraints)
            val (boxWidth, boxHeight) = RotatedChromeMath.boxSize(placeable.width, placeable.height, deviceRotationDegrees)
            layout(boxWidth, boxHeight) {
                val (offsetX, offsetY) =
                    RotatedChromeMath.placementOffset(placeable.width, placeable.height, deviceRotationDegrees)
                placeable.placeWithLayer(offsetX, offsetY) {
                    rotationZ = angle
                }
            }
        },
    ) {
        content()
    }
}

/**
 * Which edge of the (never-rotating) preview [CameraScreen]'s score+banner stack should hug once wrapped
 * in [RotatedChrome], so the guidance never sits over the middle of the frame: the top at
 * `deviceRotationDegrees == 0` (Pixel-standard, right under the top bar), otherwise whichever screen edge
 * **physical up currently appears at** — i.e. where the top of the scene is drawn in the portrait-locked
 * preview, so the banner still reads as "above the frame" to the photographer. That is
 * [ChromeStackEdge.RIGHT] for `ROTATION_90` (phone's right edge up: physical up appears at the screen's
 * right — see [OverlayMapper]'s derivation), [ChromeStackEdge.LEFT] for `ROTATION_270`, and
 * [ChromeStackEdge.BOTTOM] for `180` (upside down).
 */
enum class ChromeStackEdge { TOP, RIGHT, BOTTOM, LEFT }

/** Pure (so directly unit-testable) rotation -> edge mapping backing [ChromeStackEdge]'s KDoc. */
fun chromeStackEdgeFor(deviceRotationDegrees: Int): ChromeStackEdge {
    val theta = ((deviceRotationDegrees % 360) + 360) % 360
    return when (theta) {
        90 -> ChromeStackEdge.RIGHT
        180 -> ChromeStackEdge.BOTTOM
        270 -> ChromeStackEdge.LEFT
        else -> ChromeStackEdge.TOP
    }
}

/** The `Box`/`BoxWithConstraints` [Alignment] that hugs this edge. */
fun ChromeStackEdge.toAlignment(): Alignment = when (this) {
    ChromeStackEdge.TOP -> Alignment.TopCenter
    ChromeStackEdge.RIGHT -> Alignment.CenterEnd
    ChromeStackEdge.LEFT -> Alignment.CenterStart
    ChromeStackEdge.BOTTOM -> Alignment.BottomCenter
}

/**
 * The gap from [edge] to the chrome stack: a fixed 60dp at the top (clears [CameraTopBar]), else the
 * flush 12dp every other edge uses (see [ChromeStackEdge]'s KDoc). A `Modifier` extension (rather than a
 * plain function returning `Modifier`) per the Compose lint convention for modifier factories.
 */
fun Modifier.chromeStackEdgePadding(edge: ChromeStackEdge): Modifier = when (edge) {
    ChromeStackEdge.TOP -> this.padding(top = 60.dp)
    ChromeStackEdge.RIGHT -> this.padding(end = 12.dp)
    ChromeStackEdge.LEFT -> this.padding(start = 12.dp)
    ChromeStackEdge.BOTTOM -> this.padding(bottom = 12.dp)
}

package com.compositioncoach.app.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.composition.model.NormalizedRect

/**
 * The arithmetic behind showing a crop by clipping the one decoded photo, never a second bitmap: draw the
 * whole image scaled so the crop window fills the target, shifted so the window's corner lands on the target's
 * corner, and clip. Pure, unit-tested (`CropRenderTest`).
 */
object CropRender {

    /** Where to draw the full image so [crop] exactly fills a `targetW × targetH` box. */
    data class Placement(val imageW: Float, val imageH: Float, val offsetX: Float, val offsetY: Float)

    fun placement(crop: NormalizedRect, targetW: Float, targetH: Float): Placement {
        val w = crop.width.coerceAtLeast(MIN_SIZE)
        val h = crop.height.coerceAtLeast(MIN_SIZE)
        val imageW = targetW / w
        val imageH = targetH / h
        return Placement(imageW, imageH, -crop.left * imageW, -crop.top * imageH)
    }

    /**
     * The crop's own width / height in pixels, for a photo whose width / height is [photoAspect]: a normalized
     * window of a non-square photo is not the shape its numbers suggest.
     */
    fun aspect(crop: NormalizedRect, photoAspect: Float): Float =
        (crop.width.coerceAtLeast(MIN_SIZE) * photoAspect) / crop.height.coerceAtLeast(MIN_SIZE)

    /** A crop card's width for a fixed [height], so every card in the row is as tall as the others. */
    fun cardWidth(crop: NormalizedRect, photoAspect: Float, height: Float): Float = height * aspect(crop, photoAspect)

    /** The crop window in a photo drawn at [photoLeft],[photoTop] with size [photoW] × [photoH] (the dashed outline). */
    fun outline(crop: NormalizedRect, photoLeft: Float, photoTop: Float, photoW: Float, photoH: Float): Pair<Offset, Size> =
        Offset(photoLeft + crop.left * photoW, photoTop + crop.top * photoH) to Size(crop.width * photoW, crop.height * photoH)

    /**
     * Where a photo of [photoAspect] sits inside a `boxW × boxH` box when fitted (letterboxed), as left, top,
     * width, height.
     */
    fun fit(photoAspect: Float, boxW: Float, boxH: Float): FloatArray {
        val boxAspect = boxW / boxH
        return if (photoAspect >= boxAspect) {
            val h = boxW / photoAspect
            floatArrayOf(0f, (boxH - h) / 2f, boxW, h)
        } else {
            val w = boxH * photoAspect
            floatArrayOf((boxW - w) / 2f, 0f, w, boxH)
        }
    }

    private const val MIN_SIZE = 0.01f
}

/** [painter] (the review photo) clipped to [crop], filling this composable's bounds. */
@Composable
fun ClippedPhoto(painter: Painter, crop: NormalizedRect, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = CropRender.placement(crop, size.width, size.height)
        clipRect {
            translate(p.offsetX, p.offsetY) {
                with(painter) { draw(Size(p.imageW, p.imageH)) }
            }
        }
    }
}

/**
 * The tapped crop previewed on the full photo: everything outside the window dimmed, the window itself traced
 * with a 1dp dashed white outline. Draws over a photo fitted into this composable's bounds at [photoAspect].
 */
@Composable
fun CropOutlineOverlay(crop: NormalizedRect, photoAspect: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val (left, top, w, h) = CropRender.fit(photoAspect, size.width, size.height).toList()
        val (corner, cropSize) = CropRender.outline(crop, left, top, w, h)
        val dim = Color.Black.copy(alpha = 0.5f)
        // Four bands around the window rather than a path with a hole: cheap and exact.
        drawRect(dim, Offset(left, top), Size(w, corner.y - top))
        drawRect(dim, Offset(left, corner.y + cropSize.height), Size(w, top + h - corner.y - cropSize.height))
        drawRect(dim, Offset(left, corner.y), Size(corner.x - left, cropSize.height))
        drawRect(dim, Offset(corner.x + cropSize.width, corner.y), Size(left + w - corner.x - cropSize.width, cropSize.height))
        drawRoundRect(
            color = OnScrim.copy(alpha = 0.9f),
            topLeft = corner,
            size = cropSize,
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    }
}

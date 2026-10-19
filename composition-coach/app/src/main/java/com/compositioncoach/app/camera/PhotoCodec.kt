package com.compositioncoach.app.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.compositioncoach.composition.model.NormalizedRect
import kotlin.math.roundToInt

/**
 * Decoding and EXIF helpers behind [CaptureRepository.decodeForAnalysis], [CaptureRepository.decodeFull],
 * [CaptureRepository.exportCrop] and [CaptureRepository.exportProcessed]. Decoding goes through
 * [BitmapFactory] + [ExifInterface] on every API level (rather than `ImageDecoder` on 28+) so the orientation
 * handling is one code path: decode the stored pixels, then apply the EXIF orientation (rotation and the
 * selfie mirror) with a [Matrix], giving the photo exactly as the gallery shows it.
 */
internal object PhotoCodec {

    /** Reads the EXIF orientation tag of [uri] (NORMAL when absent or unreadable). */
    fun exifOrientation(resolver: ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /**
     * Decodes [uri] upright. With [maxLongEdgePx], decodes at the largest power-of-two subsample that is still
     * at least that big, then scales down to fit exactly (filtered); without it, decodes at full size.
     */
    fun decodeUpright(resolver: ContentResolver, uri: Uri, maxLongEdgePx: Int?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; the answer is in [bounds].
        (resolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = if (maxLongEdgePx != null) sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), maxLongEdgePx) else 1
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        if (maxLongEdgePx != null) {
            val (w, h) = fitWithin(bitmap.width, bitmap.height, maxLongEdgePx)
            if (w != bitmap.width || h != bitmap.height) {
                val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
        }
        return applyOrientation(bitmap, exifOrientation(resolver, uri))
    }

    /** Largest power of two that keeps [longEdge] / sample >= [target]. */
    fun sampleSizeFor(longEdge: Int, target: Int): Int {
        var sample = 1
        while (target > 0 && longEdge / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** [width] x [height] scaled so the long edge is at most [maxLongEdge] (never upscaled). */
    fun fitWithin(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxLongEdge) return width to height
        val scale = maxLongEdge.toDouble() / long
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** Applies an EXIF orientation to [bitmap] (recycling it when a new bitmap was needed). */
    fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    /** Rotates [bitmap] clockwise by a multiple of 90 degrees (lossless), recycling the input when replaced. */
    fun rotateClockwise(bitmap: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bitmap
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { setRotate(d.toFloat()) }, false)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    /** The pixel rectangle of [rect] (normalized, clamped) in a [width] x [height] image; at least 1x1. */
    fun cropRectPx(width: Int, height: Int, rect: NormalizedRect): Rect {
        val r = rect.clampToFrame()
        val left = (r.left * width).roundToInt().coerceIn(0, width - 1)
        val top = (r.top * height).roundToInt().coerceIn(0, height - 1)
        val right = (r.right * width).roundToInt().coerceIn(left + 1, width)
        val bottom = (r.bottom * height).roundToInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    /** `CC_20260101_120000_000.jpg` + "_crop" -> `CC_20260101_120000_000_crop.jpg`. */
    fun derivedName(originalName: String?, suffix: String, fallback: String): String {
        val base = originalName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: fallback.substringBeforeLast('.')
        return "$base$suffix.jpg"
    }

    /**
     * Copies the capture facts (exposure, lens, time, GPS) from [source] to [target], sets the orientation to
     * NORMAL (the new pixels are already upright) and the new pixel size, and records [software].
     */
    fun copyExif(source: ExifInterface?, target: ExifInterface, width: Int, height: Int, software: String) {
        if (source != null) {
            for (tag in COPIED_TAGS) source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
        }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        target.setAttribute(ExifInterface.TAG_SOFTWARE, software)
    }

    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )
}

package com.compositioncoach.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

/**
 * Straightens an already-saved photo by the roll measured at shutter time (see [AutoLevelGeometry] for the
 * band, the sign and the crop), then overwrites the saved file.
 *
 * ## Why after saving, and why it costs a little sharpness
 * Levelling needs a full decode, a resampling rotation and a re-encode (~0.5-1 s for 12 MP), so it runs on
 * [Dispatchers.Default] *after* the review screen is already showing the unlevelled photo, and the review
 * entry is updated when it finishes. Any rotation that is not a multiple of 90 degrees resamples every pixel;
 * bilinear filtering acts as a mild low-pass, and the crop then enlarges the result slightly on screen. That
 * is the honest price of a level horizon, and it is why tilts under 0.7 degrees are left alone. The re-encode
 * is at quality 100 to keep the generation loss as small as JPEG allows.
 *
 * ## EXIF
 * The stored pixel buffer is rotated (not the upright image), so the original `Orientation` tag - including
 * the front camera's mirror - stays valid and is written back unchanged; the angle is converted from display
 * space to buffer space by [AutoLevelGeometry.bufferRotationDegrees]. Exposure, lens and date tags are copied
 * from the original so the levelled file still explains itself, and `Software` records the correction.
 */
class AutoLevelProcessor(
    private val repository: CaptureRepository,
    private val originals: OriginalCopyStore,
) {

    /**
     * What levelling did: the clockwise display rotation applied (`-rollDegrees`) and a private cache copy of
     * the pre-level JPEG for the review's Compare chip (null if the copy could not be written; the levelled
     * photo is kept either way). The caller owns [original] and deletes it through [OriginalCopyStore].
     */
    class Levelled(val appliedDegrees: Float, val original: File?)

    /**
     * Levels the photo at [uri] by [rollDegrees] (the reading at shutter time), or returns null if nothing was
     * done (out of band, decode failure); the original copy is only made when the photo was actually levelled.
     */
    suspend fun apply(uri: Uri, rollDegrees: Float, isReliable: Boolean): Levelled? {
        if (!AutoLevelGeometry.shouldLevel(rollDegrees, isReliable)) return null
        val original = repository.readBytes(uri)
        val scratch = repository.newScratchFile()
        try {
            val applied = withContext(Dispatchers.Default) { levelInto(original, rollDegrees, scratch.absolutePath) } ?: return null
            val copy = withContext(Dispatchers.IO) { originals.save(original) }
            try {
                repository.overwrite(uri, scratch)
            } catch (t: Throwable) {
                copy?.let { originals.delete(it.path) }
                throw t
            }
            return Levelled(applied, copy)
        } finally {
            scratch.delete()
        }
    }

    private fun levelInto(original: ByteArray, rollDegrees: Float, outputPath: String): Float? {
        val sourceExif = ExifInterface(ByteArrayInputStream(original))
        val orientation = sourceExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val bufferAngle = AutoLevelGeometry.bufferRotationDegrees(rollDegrees, orientation)

        val source = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val (cropW, cropH) = AutoLevelGeometry.cropSize(source.width, source.height, bufferAngle.toDouble())
        val output = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            translate(cropW / 2f, cropH / 2f)
            rotate(bufferAngle)
            translate(-source.width / 2f, -source.height / 2f)
            drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        source.recycle()
        java.io.FileOutputStream(outputPath).use { output.compress(Bitmap.CompressFormat.JPEG, CaptureQuality.JPEG_QUALITY, it) }
        output.recycle()

        val outExif = ExifInterface(outputPath)
        for (tag in PRESERVED_TAGS) {
            sourceExif.getAttribute(tag)?.let { outExif.setAttribute(tag, it) }
        }
        outExif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        outExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, cropW.toString())
        outExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, cropH.toString())
        outExif.setAttribute(
            ExifInterface.TAG_SOFTWARE,
            String.format(Locale.US, "JustRight (auto-level %.2f deg)", -rollDegrees),
        )
        outExif.saveAttributes()
        Log.i(TAG, "Levelled by ${-rollDegrees} deg (buffer ${bufferAngle} deg, orientation $orientation) to ${cropW}x$cropH")
        return -rollDegrees
    }

    companion object {
        private const val TAG = "AutoLevel"

        private val PRESERVED_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
        )
    }
}

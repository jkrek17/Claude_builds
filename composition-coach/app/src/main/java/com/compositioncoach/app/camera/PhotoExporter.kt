package com.compositioncoach.app.camera

import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.compositioncoach.composition.model.NormalizedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Writes new photos derived from existing ones (crops, stacks, processed copies) into the app's album. The
 * implementation behind [CaptureRepository.exportCrop], [CaptureRepository.exportProcessed] and
 * [CaptureRepository.exportProcessedOriented]: encode at JPEG quality 100 to a cache file, write EXIF
 * (capture facts copied from the source, orientation as given, `Software` recorded), insert as a new photo.
 */
internal class PhotoExporter(private val repository: CaptureRepository) {

    suspend fun exportCrop(uri: Uri, rect: NormalizedRect, suffix: String, fallbackName: String): Uri {
        val sourceExif = runCatching { ExifInterface(ByteArrayInputStream(repository.readExifHeader(uri))) }.getOrNull()
        val name = PhotoCodec.derivedName(displayName(uri), suffix, fallbackName)
        val cropped = withContext(Dispatchers.Default) {
            val full = PhotoCodec.decodeUpright(repository.context.contentResolver, uri, null) ?: error("Cannot decode $uri")
            val r = PhotoCodec.cropRectPx(full.width, full.height, rect)
            val out = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
            if (out !== full) full.recycle()
            out
        }
        return save(cropped, name, sourceExif)
    }

    suspend fun exportOriented(bitmap: Bitmap, name: String, exifOrientation: Int, sourceJpeg: ByteArray?): Uri {
        val exif = sourceJpeg?.let { runCatching { ExifInterface(ByteArrayInputStream(it)) }.getOrNull() }
        return save(bitmap, name, exif, exifOrientation)
    }

    /** Encodes [bitmap] (recycled afterwards) and inserts it as a new photo named [name]. */
    suspend fun save(
        bitmap: Bitmap,
        name: String,
        sourceExif: ExifInterface?,
        exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
    ): Uri = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("cc_export_", ".jpg", repository.context.cacheDir)
        try {
            val width = bitmap.width
            val height = bitmap.height
            temp.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, CaptureQuality.JPEG_QUALITY, out)) error("JPEG encode failed")
            }
            bitmap.recycle()
            ExifInterface(temp.absolutePath).apply {
                PhotoCodec.copyExif(sourceExif, this, width, height, SOFTWARE_TAG)
                setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                if (getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                    val now = EXIF_DATE_FORMAT.format(System.currentTimeMillis())
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                    setAttribute(ExifInterface.TAG_DATETIME, now)
                }
                saveAttributes()
            }
            repository.saveFileAsNewPhoto(temp, name)
        } finally {
            temp.delete()
        }
    }

    /** The photo's file name (MediaStore DISPLAY_NAME, or the file name of a `file:` Uri), or null. */
    private fun displayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        repository.context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val SOFTWARE_TAG = "JustRight"
        private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    }
}

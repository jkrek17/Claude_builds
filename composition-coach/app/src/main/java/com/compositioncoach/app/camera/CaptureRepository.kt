package com.compositioncoach.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Saves captured photos to the shared Pictures collection and can delete them again (Retake).
 *
 * Overlays (score badge, guidance banner, grid, debug panel) are Compose layers drawn on top of the
 * [androidx.camera.view.PreviewView] surface; [ImageCapture] reads frames straight from the camera pipeline,
 * so nothing drawn by Compose is ever baked into the saved JPEG. No extra care is needed here for that.
 */
class CaptureRepository(private val context: Context) {

    /** Takes a photo with [imageCapture] and returns the [Uri] it was saved to (a MediaStore content Uri). */
    suspend fun capture(imageCapture: ImageCapture, isFrontCamera: Boolean): Uri {
        val name = newFileName()
        // Front-camera JPEGs are flipped so the saved photo matches the mirrored preview the user framed with.
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = isFrontCamera }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            captureToMediaStore(imageCapture, name, metadata)
        } else {
            captureToLegacyPicturesDir(imageCapture, name, metadata)
        }
    }

    /**
     * API 29+: scoped storage. CameraX inserts the MediaStore row itself when given the *collection* Uri
     * plus content values (passing a pre-inserted item Uri makes it try to insert into that item and fail),
     * and it also manages IS_PENDING around the write. The saved item Uri comes back in the result.
     */
    private suspend fun captureToMediaStore(imageCapture: ImageCapture, name: String, metadata: ImageCapture.Metadata): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR_NAME")
        }
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(metadata)
            .build()
        val result = takePicture(imageCapture, outputOptions)
        return result.savedUri ?: error("Photo was written but MediaStore returned no Uri")
    }

    /**
     * API 26-28: write straight into the public Pictures/CompositionCoach directory (requires
     * WRITE_EXTERNAL_STORAGE, requested by the screen before the first capture), then hand the file to the
     * media scanner so it appears in the gallery and we get a content Uri back for review/delete.
     */
    private suspend fun captureToLegacyPicturesDir(imageCapture: ImageCapture, name: String, metadata: ImageCapture.Metadata): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")
        val file = File(dir, name)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
        takePicture(imageCapture, outputOptions)
        val scannedUri = suspendCancellableCoroutine<Uri?> { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg")) { _, uri ->
                if (cont.isActive) cont.resume(uri)
            }
        }
        return scannedUri ?: Uri.fromFile(file)
    }

    private suspend fun takePicture(imageCapture: ImageCapture, outputOptions: ImageCapture.OutputFileOptions): ImageCapture.OutputFileResults =
        suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                outputOptions,
                ImmediateExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        cont.resume(outputFileResults)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }

    /**
     * Saves JPEG [bytes] captured in memory (the lucky-imaging path) to the same album, name pattern and
     * MediaStore collection as [capture], without re-encoding them. The pixels stay as the sensor delivered
     * them, so orientation lives in EXIF exactly as in CameraX's own file path: [rotationDegrees]
     * (`ImageInfo.rotationDegrees`, which follows `ImageCapture.targetRotation`, i.e. the phone's physical
     * hold) plus, for the front camera, a display-space horizontal mirror so the selfie matches the mirrored
     * preview (see [ExifOrientations.tagFor]). Runs on [Dispatchers.IO].
     */
    suspend fun saveJpeg(bytes: ByteArray, rotationDegrees: Int, mirrorHorizontally: Boolean): Uri = withContext(Dispatchers.IO) {
        val name = newFileName()
        val temp = File.createTempFile("cc_capture_", ".jpg", context.cacheDir)
        try {
            temp.writeBytes(bytes)
            ExifInterface(temp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifOrientations.tagFor(rotationDegrees, mirrorHorizontally).toString())
                if (getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                    val now = EXIF_DATE_FORMAT.format(System.currentTimeMillis())
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                    setAttribute(ExifInterface.TAG_DATETIME, now)
                }
                saveAttributes()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) insertIntoMediaStore(temp, name) else copyToLegacyPicturesDir(temp, name)
        } finally {
            temp.delete()
        }
    }

    private fun insertIntoMediaStore(source: File, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore refused the insert")
        try {
            val out = resolver.openOutputStream(uri) ?: error("No output stream for $uri")
            out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri
    }

    private suspend fun copyToLegacyPicturesDir(source: File, name: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")
        val file = File(dir, name)
        source.copyTo(file, overwrite = true)
        val scannedUri = suspendCancellableCoroutine<Uri?> { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg")) { _, uri ->
                if (cont.isActive) cont.resume(uri)
            }
        }
        return scannedUri ?: Uri.fromFile(file)
    }

    /**
     * Replaces the bytes of an already-saved photo with [source] (the auto-level step). Handles MediaStore
     * content Uris (the app owns the row it inserted, so no permission is needed on API 29+) and the legacy
     * `file:` fallback.
     */
    suspend fun overwrite(uri: Uri, source: File) = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val target = File(uri.path ?: error("No path in $uri"))
            // Never resurrect a photo the user deleted (Retake) while auto-level was running.
            if (!target.exists()) error("$uri was deleted")
            source.copyTo(target, overwrite = true)
        } else {
            val resolver = context.contentResolver
            val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "w")
                ?: error("No output stream for $uri")
            out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        }
    }

    /** Reads a saved photo's full bytes (for auto-level and for EXIF-based diagnostics). Runs on [Dispatchers.IO]. */
    suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open $uri")
    }

    /**
     * The first [maxBytes] of a saved photo: enough for the EXIF/APP1 segment and the frame header, which is
     * all the diagnostics need, without reading a 10 MB file. Runs on [Dispatchers.IO].
     */
    suspend fun readExifHeader(uri: Uri, maxBytes: Int = 256 * 1024): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(maxBytes)
            var read = 0
            while (read < maxBytes) {
                val n = input.read(buffer, read, maxBytes - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        } ?: error("Cannot open $uri")
    }

    /** A scratch file in the app cache, for building a replacement JPEG before [overwrite]. */
    fun newScratchFile(): File = File.createTempFile("cc_level_", ".jpg", context.cacheDir)

    /** Deletes a photo the user chose to retake. Handles both content Uris and the legacy file Uri fallback. */
    suspend fun delete(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    /**
     * Loads a small thumbnail for [uri] (e.g. for a gallery-shortcut button on the camera screen), sized
     * to roughly [sizePx] on its longest side. Runs on [Dispatchers.IO]; returns null on any failure
     * (missing/deleted file, decode error) rather than throwing, since a thumbnail is a "nice to have",
     * not something worth surfacing an error for.
     *
     * Uses [android.content.ContentResolver.loadThumbnail] on API 29+, which asks the provider for an
     * already-downsampled image (cheap, no full-resolution decode). Below API 29, falls back to decoding
     * the file ourselves at a downsampled size via [BitmapFactory.Options.inSampleSize] — computed from a
     * bounds-only first pass so the full-resolution JPEG is never actually decoded into memory.
     */
    suspend fun loadThumbnail(uri: Uri, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            } else {
                loadThumbnailBySampling(uri, sizePx)
            }
        }.getOrNull()
    }

    private fun loadThumbnailBySampling(uri: Uri, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, sizePx)
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        }
    }

    /** Largest power-of-two downsample factor that keeps both dimensions at least [reqSizePx]. */
    private fun sampleSizeFor(width: Int, height: Int, reqSizePx: Int): Int {
        if (width <= 0 || height <= 0 || reqSizePx <= 0) return 1
        var sampleSize = 1
        var w = width
        var h = height
        while (w / 2 >= reqSizePx && h / 2 >= reqSizePx) {
            sampleSize *= 2
            w /= 2
            h /= 2
        }
        return sampleSize
    }

    /**
     * Finds the most recently added photo under `Pictures/CompositionCoach` via MediaStore — used on a
     * fresh process start to restore [com.compositioncoach.app.ui.camera.CameraUiState.lastPhotoUri]
     * (the in-memory [com.compositioncoach.app.di.AppContainer]-scoped state from a previous capture
     * doesn't survive process death). Returns null if the app has never saved a photo, or on any query
     * failure. Runs on [Dispatchers.IO].
     */
    suspend fun latestPhotoUri(): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?" to
                    arrayOf("${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR_NAME/")
            } else {
                @Suppress("DEPRECATION")
                "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/$ALBUM_DIR_NAME/%")
            }
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            }
        }.getOrNull()
    }

    /**
     * `CC_yyyyMMdd_HHmmss_SSS.jpg`. The milliseconds keep two captures in the same second (easy with the
     * volume key) from colliding; MediaStore would otherwise rename one with a " (1)" suffix.
     */
    private fun newFileName(): String = "CC_${TIMESTAMP_FORMAT.format(System.currentTimeMillis())}.jpg"

    companion object {
        private const val ALBUM_DIR_NAME = "CompositionCoach"
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        private val EXIF_DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    }
}

/** [ImageCapture] requires an [java.util.concurrent.Executor]; the callback itself is what resumes the coroutine. */
private object ImmediateExecutor : java.util.concurrent.Executor {
    override fun execute(command: Runnable) = command.run()
}

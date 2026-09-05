package com.compositioncoach.app.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import kotlinx.coroutines.suspendCancellableCoroutine
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
        val name = "CC_${TIMESTAMP_FORMAT.format(System.currentTimeMillis())}.jpg"
        // Front-camera JPEGs are flipped so the saved photo matches the mirrored preview the user framed with.
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = isFrontCamera }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            captureToMediaStore(imageCapture, name, metadata)
        } else {
            captureToLegacyPicturesDir(imageCapture, name, metadata)
        }
    }

    /** API 29+: scoped storage. Insert a pending row, let CameraX write into it, then publish it. */
    private suspend fun captureToMediaStore(imageCapture: ImageCapture, name: String, metadata: ImageCapture.Metadata): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CompositionCoach")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore did not return a Uri for the new image")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(resolver, uri, ContentValues())
            .setMetadata(metadata)
            .build()
        try {
            takePicture(imageCapture, outputOptions)
        } catch (t: Throwable) {
            // Don't leave an empty pending row in the gallery database behind a failed capture.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /**
     * API 26-28: write straight into the public Pictures/CompositionCoach directory (requires
     * WRITE_EXTERNAL_STORAGE, requested by the screen before the first capture), then hand the file to the
     * media scanner so it appears in the gallery and we get a content Uri back for review/delete.
     */
    private suspend fun captureToLegacyPicturesDir(imageCapture: ImageCapture, name: String, metadata: ImageCapture.Metadata): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CompositionCoach")
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

    companion object {
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}

/** [ImageCapture] requires an [java.util.concurrent.Executor]; the callback itself is what resumes the coroutine. */
private object ImmediateExecutor : java.util.concurrent.Executor {
    override fun execute(command: Runnable) = command.run()
}

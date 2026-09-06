package com.compositioncoach.app.camera

import android.content.Context
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.core.view.doOnLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class FlashMode { OFF, AUTO, ON }

/** Which physical lens is currently bound. */
enum class LensFacing { BACK, FRONT }

/** Result of a [CameraController.bind] attempt, surfaced by the ViewModel as UI state. */
sealed interface CameraBindResult {
    /** @param lensFacing the lens actually bound, which can differ from the request on single-camera devices. */
    data class Success(val camera: Camera, val hasFlashUnit: Boolean, val lensFacing: LensFacing) : CameraBindResult
    data class Failure(val throwable: Throwable) : CameraBindResult
}

/**
 * Owns the CameraX binding lifecycle: [Preview], [ImageCapture] and [ImageAnalysis] are bound together
 * as one [UseCaseGroup] sharing a single [ViewPort] derived from the [PreviewView]. That is what keeps the
 * analysis frame's field of view identical to what the preview shows — see the class doc on
 * `com.compositioncoach.app.ui.camera.OverlayMapper` for why that lets normalized (0..1) coordinates map onto
 * the preview with a plain multiply.
 *
 * One instance is created per camera session (owned by [com.compositioncoach.app.ui.camera.CameraViewModel])
 * and is not itself lifecycle-aware; the caller re-binds in a `DisposableEffect` keyed on the lifecycle owner
 * and lens facing, and calls [unbind] when the composable leaves composition.
 */
class CameraController(private val context: Context) {

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var imageCapture: ImageCapture? = null
        private set
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    var flashMode: FlashMode = FlashMode.OFF
        private set

    val hasFlashUnit: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    /**
     * Binds preview + capture + analysis for [lensFacing] to [lifecycleOwner], targeting [previewView].
     * Safe to call again (e.g. on lens switch): any existing binding is unbound first.
     */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: LensFacing,
        // Null when the vision pipeline is unavailable (e.g. the :vision stub) — the preview and capture
        // use cases still bind so the user still has a working camera; only live analysis is skipped.
        frameAnalyzer: ImageAnalysis.Analyzer?,
    ): CameraBindResult = try {
        val provider = cameraProvider ?: ProcessCameraProvider.getInstance(context).awaitFuture().also { cameraProvider = it }
        provider.unbindAll()
        // The screen binds as soon as it enters composition, before the PreviewView has been measured. A
        // ViewPort built from a 0x0 view would be 1:1 and crop both preview and analysis to a square, so
        // wait for the first layout pass before deriving the viewport from the view.
        previewView.awaitLayout()

        val desiredSelector = if (lensFacing == LensFacing.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val fallbackSelector = if (lensFacing == LensFacing.FRONT) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val cameraSelector = when {
            provider.hasCamera(desiredSelector) -> desiredSelector
            provider.hasCamera(fallbackSelector) -> fallbackSelector
            else -> error("No available camera for this device")
        }
        val boundIsFront = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

        val newPreview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val newImageCapture = ImageCapture.Builder()
            // MINIMIZE_LATENCY over MAXIMIZE_QUALITY: this is a live-coaching camera, so shutter
            // responsiveness matters more than the last bit of JPEG quality once framing is already dialed in.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .build()

        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val newImageAnalysis = frameAnalyzer?.let { analyzer ->
            ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolutionSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(analysisExecutor, analyzer) }
        }

        // Share one ViewPort so the analysis crop matches exactly what the preview shows on screen.
        // PreviewView.viewPort is null until the view is attached to a display; after awaitLayout() the
        // fallback below uses the real measured size, so it only differs in how rotation is sourced.
        val viewPort = previewView.viewPort ?: ViewPort.Builder(
            Rational(previewView.width.coerceAtLeast(1), previewView.height.coerceAtLeast(1)),
            previewView.display?.rotation ?: Surface.ROTATION_0,
        ).build()

        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(newPreview)
            .addUseCase(newImageCapture)
            .apply { newImageAnalysis?.let { addUseCase(it) } }
            .build()

        val boundCamera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)

        preview = newPreview
        imageCapture = newImageCapture
        imageAnalysis = newImageAnalysis
        camera = boundCamera
        applyFlashMode(flashMode)

        val boundLens = if (boundIsFront) LensFacing.FRONT else LensFacing.BACK
        if (boundLens != lensFacing) {
            Log.w(TAG, "Requested $lensFacing but device only has the other lens; bound to $boundLens.")
        }
        CameraBindResult.Success(boundCamera, boundCamera.cameraInfo.hasFlashUnit(), boundLens)
    } catch (t: Throwable) {
        Log.e(TAG, "Camera bind failed", t)
        CameraBindResult.Failure(t)
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        camera = null
        preview = null
        imageCapture = null
        imageAnalysis = null
    }

    fun shutdown() {
        unbind()
        analysisExecutor.shutdown()
    }

    fun cycleFlashMode(): FlashMode {
        flashMode = when (flashMode) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
        }
        applyFlashMode(flashMode)
        return flashMode
    }

    private fun applyFlashMode(mode: FlashMode) {
        val capture = imageCapture ?: return
        capture.flashMode = when (mode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        }
    }

    /** Focuses (and meters) on a tap in [previewView]'s local coordinates. */
    fun tapToFocus(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val point: MeteringPoint = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
    }

    /**
     * Pinch-to-zoom: [delta] is a multiplicative factor applied to the current zoom ratio.
     * @return the zoom ratio actually applied (for the screen's zoom chip), or null if there is no bound
     *   camera yet (a pinch that starts before the bind `DisposableEffect` finishes).
     */
    fun applyZoomDelta(delta: Float): Float? {
        val cam = camera ?: return null
        val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val range = cam.cameraInfo.zoomState.value
        val min = range?.minZoomRatio ?: 1f
        val max = range?.maxZoomRatio ?: 1f
        val next = (current * delta).coerceIn(min, max)
        runCatching { cam.cameraControl.setZoomRatio(next) }
        return next
    }

    companion object {
        private const val TAG = "CameraController"
    }
}

/** Suspends until the view has completed at least one layout pass (returns immediately if it already has). */
private suspend fun PreviewView.awaitLayout() {
    if (width > 0 && height > 0) return
    suspendCancellableCoroutine { cont ->
        doOnLayout { if (cont.isActive) cont.resume(Unit) }
    }
}

/**
 * Awaits a Guava [com.google.common.util.concurrent.ListenableFuture] without pulling in the
 * kotlinx-coroutines-guava artifact (not part of this project's dependency set): registers a direct
 * listener that resumes the coroutine, cancelling the future if the coroutine itself is cancelled.
 */
private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitFuture(): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            { it.run() },
        )
        cont.invokeOnCancellation { cancel(false) }
    }

package com.compositioncoach.app.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraUnavailableException
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
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the CameraX binding lifecycle: [Preview], [ImageCapture] and [ImageAnalysis] are bound together
 * as one [UseCaseGroup] sharing a single [ViewPort] derived from the [PreviewView]. That is what keeps the
 * analysis frame's field of view identical to what the preview shows — see the class doc on
 * `com.compositioncoach.app.ui.camera.OverlayMapper` for why that lets normalized (0..1) coordinates map onto
 * the preview with a plain multiply.
 *
 * ## Why capture is cropped to the shared [ViewPort]
 * [ImageCapture] targets 4:3 at the sensor's highest available resolution ([AspectRatioStrategy]
 * `RATIO_4_3_FALLBACK_AUTO_STRATEGY` + [ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY]) — the classic
 * "Pixel-style" full-quality still aspect — while [ImageAnalysis] stays at ~640×480 4:3 for detector
 * throughput. Both use cases are bound into the same [UseCaseGroup]/[ViewPort] as [Preview], so CameraX
 * crops all three to *the same field of view* regardless of their different output resolutions: what the
 * user sees in the (4:3 letterboxed) preview is exactly what ends up in both the saved photo and what
 * `:vision`'s normalized detector geometry describes, just rendered at three different pixel sizes of the
 * identical crop. Without a shared `ViewPort`, each use case would default to cropping from the sensor's
 * *own* native aspect ratio independently, and a wide-sensor device could silently save a photo showing
 * more (or less) than what the photographer framed in the preview.
 *
 * One instance is created per camera session (owned by [com.compositioncoach.app.ui.camera.CameraViewModel])
 * and is not itself lifecycle-aware; the caller re-binds in a `DisposableEffect` keyed on the lifecycle owner
 * and lens facing, and calls [unbind] when the composable leaves composition.
 */
class CameraController(private val context: Context) {

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var imageCapture: ImageCapture? = null
        private set
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    var flashMode: FlashMode = FlashMode.OFF
        private set

    /**
     * When true, the next (re)bind builds [ImageCapture] with `CAPTURE_MODE_MINIMIZE_LATENCY` instead of
     * the default `CAPTURE_MODE_MAXIMIZE_QUALITY` — set this from a battery-saver/thermal signal via
     * [setPreferFastCapture]. CameraX has no API to change an already-bound [ImageCapture]'s capture mode
     * live (it's fixed at `Builder.build()` time), so a change here only takes effect on the next actual
     * rebind; callers that want it to apply immediately must include whatever drives this flag in their
     * rebind `DisposableEffect`'s keys, the same way `lensFacing` already is.
     */
    @Volatile private var preferFastCapture: Boolean = false

    val hasFlashUnit: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    /**
     * Sets whether the next bind should trade capture quality for shutter speed — see
     * [preferFastCapture]'s KDoc for why this isn't applied to an already-bound [ImageCapture].
     */
    fun setPreferFastCapture(preferFast: Boolean) {
        preferFastCapture = preferFast
    }

    /**
     * Binds preview + capture + analysis for [lensFacing] to [lifecycleOwner], targeting [previewView].
     * Safe to call again (e.g. on lens switch): any existing binding is unbound first.
     *
     * Retries once, after [RETRY_DELAY_MS], on the two CameraX failure modes known to be transient rather
     * than permanent — [IllegalStateException] (CameraX's own catch-all for "camera already
     * in use"/state races, most often seen right after another app or `Activity` instance released the
     * camera) and [CameraUnavailableException] (the camera service briefly busy/unavailable) — before
     * giving up and reporting [CameraBindResult.Failure]. Any other exception fails immediately without a
     * retry, since those aren't expected to resolve themselves on a second attempt a third of a second
     * later.
     */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: LensFacing,
        // Null when the vision pipeline is unavailable (e.g. the :vision stub) — the preview and capture
        // use cases still bind so the user still has a working camera; only live analysis is skipped.
        frameAnalyzer: ImageAnalysis.Analyzer?,
    ): CameraBindResult = bindAttempt(lifecycleOwner, previewView, lensFacing, frameAnalyzer, isRetry = false)

    private suspend fun bindAttempt(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: LensFacing,
        frameAnalyzer: ImageAnalysis.Analyzer?,
        isRetry: Boolean,
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

        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val newPreview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .apply { surfaceProvider = previewView.surfaceProvider }

        val captureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
        val newImageCapture = ImageCapture.Builder()
            // MAXIMIZE_QUALITY by default: this is a coaching camera whose whole point is a better final
            // photo, so once framing is dialed in the extra processing time (typically well under a
            // second) is worth it. Falls back to MINIMIZE_LATENCY when the device itself says it's
            // conserving resources (battery saver or a thermal throttle tier) via setPreferFastCapture —
            // see that property's KDoc for why this only takes effect on the next bind, not live.
            .setCaptureMode(if (preferFastCapture) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .setResolutionSelector(captureResolutionSelector)
            .setTargetRotation(targetRotation)
            .setFlashMode(flashMode.toImageCaptureFlashMode())
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

        // Share one ViewPort so the analysis and capture crops match exactly what the preview shows on
        // screen — see the class doc above for why. PreviewView.viewPort is null until the view is
        // attached to a display; after awaitLayout() the fallback below uses the real measured size, so
        // it only differs in how rotation is sourced.
        val viewPort = previewView.viewPort ?: ViewPort.Builder(
            Rational(previewView.width.coerceAtLeast(1), previewView.height.coerceAtLeast(1)),
            targetRotation,
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
        val exposureState = boundCamera.cameraInfo.exposureState
        val exposureRange = if (exposureState.isExposureCompensationSupported) exposureState.exposureCompensationRange else Range(0, 0)
        CameraBindResult.Success(boundCamera, boundCamera.cameraInfo.hasFlashUnit(), boundLens, exposureRange)
    } catch (t: Throwable) {
        if (!isRetry && (t is IllegalStateException || t is CameraUnavailableException)) {
            Log.w(TAG, "Camera bind failed with a likely-transient error; retrying once in ${RETRY_DELAY_MS}ms", t)
            delay(RETRY_DELAY_MS)
            bindAttempt(lifecycleOwner, previewView, lensFacing, frameAnalyzer, isRetry = true)
        } else {
            Log.e(TAG, "Camera bind failed", t)
            CameraBindResult.Failure(t)
        }
    }

    /**
     * Unbinds all use cases. CameraX requires `bindToLifecycle`/`unbindAll` to run on the main thread;
     * this is normally already true (the screen's `DisposableEffect.onDispose` runs on the composition's
     * main-thread dispatcher), but as a defensive measure against any caller that isn't — a
     * `ViewModel.onCleared` running on a different dispatcher, say — this marshals onto the main thread
     * itself rather than letting CameraX throw. Kept synchronous (not `suspend`) since callers currently
     * expect that; a call from a background thread returns immediately without waiting for the marshaled
     * unbind to actually run; that's fine here since nothing observes `unbind()` completing synchronously.
     */
    fun unbind() {
        runOnMainThread {
            cameraProvider?.unbindAll()
            camera = null
            preview = null
            imageCapture = null
            imageAnalysis = null
        }
    }

    fun shutdown() {
        unbind()
        analysisExecutor.shutdown()
    }

    /**
     * Sets [ImageCapture.targetRotation] to whichever `Surface.ROTATION_*` matches [deviceRotationDegrees]
     * — the phone's quantized *physical* rotation from natural portrait (see `:vision`'s
     * `DeviceRotationQuantizer`/`FrameAnalysis.deviceRotationDegrees`), not `previewView.display.rotation`
     * (which never changes for this portrait-locked app — see [bind]'s `targetRotation`). Unlike the
     * capture mode, `ImageCapture.targetRotation` is a *live* property CameraX honours without a rebind, so
     * this is called every time [deviceRotationDegrees] changes (see `CameraScreen`'s
     * `LaunchedEffect(uiState.deviceRotationDegrees)`) rather than only at bind time — that's what makes
     * CameraX write the correct EXIF orientation so the saved JPEG is upright in the gallery regardless of
     * how the phone was actually held when the shutter fired. [Preview]'s own `targetRotation` is
     * deliberately left alone: the live preview content must never rotate, Pixel-Camera-style — only the
     * saved photo's metadata needs to reflect the true physical orientation. The mapping itself lives in
     * [CaptureRotation.surfaceRotationFor] (pure, unit-tested in `CaptureRotationTest`) with its full
     * hold-by-hold table.
     */
    fun setCaptureRotationDegrees(deviceRotationDegrees: Int) {
        imageCapture?.targetRotation = CaptureRotation.surfaceRotationFor(deviceRotationDegrees)
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
        capture.flashMode = mode.toImageCaptureFlashMode()
    }

    private fun FlashMode.toImageCaptureFlashMode(): Int = when (this) {
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
    }

    /** Focuses (and meters) on a tap in [previewView]'s local coordinates. Auto-cancels after 3s. */
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

    /**
     * Sets exposure compensation, clamped to the bound camera's supported range (see
     * [CameraBindResult.Success.exposureRange]). A no-op if there is no bound camera, or the device
     * doesn't support exposure compensation at all (an empty/zero range).
     */
    fun setExposureCompensation(index: Int) {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val clamped = index.coerceIn(range.lower, range.upper)
        runCatching { cam.cameraControl.setExposureCompensationIndex(clamped) }
    }

    private inline fun runOnMainThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    companion object {
        private const val TAG = "CameraController"
        private const val RETRY_DELAY_MS = 300L
    }
}

package com.compositioncoach.app.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraUnavailableException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.compositioncoach.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the CameraX binding lifecycle: [Preview], [ImageCapture] and [ImageAnalysis] are bound together
 * as one [UseCaseGroup] sharing a single [ViewPort] derived from the [PreviewView]. That is what keeps the
 * analysis frame's field of view identical to what the preview shows — see the class doc on
 * `com.compositioncoach.app.ui.camera.OverlayMapper` for why that lets normalized (0..1) coordinates map onto
 * the preview with a plain multiply. Every capture crops to that same ViewPort; why that is still the full sensor
 * frame (no software re-encode) is on [bindUseCases] in `CameraUseCases.kt`.
 *
 * ## Image-quality configuration (the "why is the stock app sharper" work)
 * Extensions first (the vendor's own multi-frame pipeline, [CameraExtensionsSupport]), then, without one, the
 * high-quality Camera2 request options, JPEG quality 100 and a full-resolution check: all explained, item by
 * item, on [applyQualityOptions] in `CameraQualityOptions.kt`. A [CaptureResultTap] on [ImageCapture] (outside
 * extensions) lets diagnostics report the noise-reduction/edge/OIS modes the HAL actually used. Focus, zoom,
 * exposure, flash and burst controls are extensions in `CameraControls.kt`.
 *
 * One instance is created per camera session (by the screen) and is not itself lifecycle-aware; the caller
 * re-binds in a `DisposableEffect` keyed on the lifecycle owner and lens facing, and calls [unbind] when the
 * composable leaves composition. The view model receives this instance in [CameraBindResult.Success.controller]
 * and uses [rebind] (enhancement changes), [setZoomRatio], [setExposureCompensation] and the capture functions.
 *
 * @param enhancementPreference read at the start of every bind; defaults to the persisted
 *   `capture_enhancement` setting, so the very first bind already uses the right session type.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val enhancementPreference: suspend () -> CaptureEnhancement = defaultEnhancementPreference(context),
) {

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    /** In-memory capture callbacks land here (never the main thread): copying a 10 MB JPEG is not UI work. */
    internal val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindMutex = Mutex()

    var imageCapture: ImageCapture? = null
        private set
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    /** The bound camera; null while unbound. Read by the controls in `CameraControls.kt`. */
    internal var camera: Camera? = null
        private set
    private var cameraProvider: ProcessCameraProvider? = null
    private val extensionsSupport = CameraExtensionsSupport(context)

    /** Incremented by [unbind]; a bind that started under an older generation aborts instead of binding. */
    @Volatile private var bindGeneration = 0
    private var lastBindArgs: BindArgs? = null

    /** What the current bind configured; null while unbound. */
    @Volatile var session: CaptureSessionInfo? = null
        private set

    /** Still-capture result tap of the current bind; null under an extension or while unbound. */
    @Volatile var captureResultTap: CaptureResultTap? = null
        private set

    /** Cycled by `cycleFlashMode` (`CameraControls.kt`) and applied to every new [ImageCapture]. */
    var flashMode: FlashMode = FlashMode.OFF
        internal set

    /**
     * When true, the next (re)bind uses `CAPTURE_MODE_MINIMIZE_LATENCY` and skips the high-quality Camera2 options
     * (battery saver / thermal, [setPreferFastCapture]). Capture mode is fixed at `Builder.build()`, so a change
     * only applies on the next bind: the screen keys its bind `DisposableEffect` on it, as on `lensFacing`.
     */
    @Volatile private var preferFastCapture: Boolean = false

    /**
     * The last physical-hold rotation from [setCaptureRotationDegrees], re-applied to every new [ImageCapture], so
     * a rebind does not fall back to the portrait display rotation and tag a landscape photo as portrait.
     */
    @Volatile private var captureSurfaceRotation: Int? = null

    /** Target size of the [ImageAnalysis] stream for the next bind; see [setAnalysisResolutionHint]. */
    @Volatile private var analysisResolution: Size = DEFAULT_ANALYSIS_RESOLUTION

    /** Outcome-driven enhancement for the next bind, overriding the persisted preference; see [setEnhancementOverride]. */
    @Volatile private var enhancementOverride: CaptureEnhancement? = null

    /**
     * Sets the analysis stream's target size for the **next** bind (CameraX fixes it at build time): pass
     * [MOTION_ANALYSIS_RESOLUTION] (1920x1440, or the fallback after a slow hold) while a Motion outcome is
     * selected, so its stacks are built from 1.2 to 2.8 MP frames, and null to return to the 640x480 coaching default. Returns true when the value changed,
     * i.e. the caller should [rebind] for it to apply. Higher analysis resolution makes live detection slower
     * (detectors scale with input size), which is why it is only raised for Motion outcomes.
     */
    fun setAnalysisResolutionHint(hint: Size?): Boolean {
        val next = hint ?: DEFAULT_ANALYSIS_RESOLUTION
        if (next == analysisResolution) return false
        analysisResolution = next
        return true
    }

    /**
     * Sets the enhancement the next bind uses instead of the persisted `capture_enhancement` preference (null
     * = use the preference again): the outcome's choice from [OutcomeCapturePlanner.plan]. Returns true when the
     * value changed (the caller should [rebind]). [CaptureSessionInfo.requestedEnhancement] keeps reporting the
     * persisted preference; [CaptureSessionInfo.outcomeEnhancement] reports this override.
     */
    fun setEnhancementOverride(enhancement: CaptureEnhancement?): Boolean {
        if (enhancement == enhancementOverride) return false
        enhancementOverride = enhancement
        return true
    }

    /** [ImageCapture.getTargetRotation] in degrees (0/90/180/270, `Surface.ROTATION_*` sense); 0 while unbound. */
    val captureTargetRotationDegrees: Int
        get() = when (imageCapture?.targetRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    /** Camera2 facts and manual focus for the current bind, or null while unbound. */
    val lensControl: LensControl? get() = camera?.let { LensControl(it) }

    val hasFlashUnit: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    /** The bound camera's current zoom ratio, or null while unbound. */
    val currentZoomRatio: Float? get() = camera?.cameraInfo?.zoomState?.value?.zoomRatio

    /** Whether the next bind trades capture quality for shutter speed; see [preferFastCapture]. */
    fun setPreferFastCapture(preferFast: Boolean) {
        preferFastCapture = preferFast
    }

    /**
     * Binds preview + capture + analysis for [lensFacing] to [lifecycleOwner], targeting [previewView].
     * Safe to call again (e.g. on lens switch): any existing binding is unbound first. Binds are serialized,
     * so a screen-driven bind and a view-model-driven [rebind] never interleave.
     *
     * Retries once, after [RETRY_DELAY_MS], on the two transient CameraX failures ([IllegalStateException]: "camera
     * in use" races right after another app released it; [CameraUnavailableException]: the service briefly busy),
     * then reports [CameraBindResult.Failure]; anything else fails at once. Cancellation (the screen disposing the
     * bind, or [unbind] during it) propagates as cancellation, not as a camera failure.
     */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: LensFacing,
        // Null when the vision pipeline is unavailable: preview and capture still bind, only analysis is skipped.
        frameAnalyzer: ImageAnalysis.Analyzer?,
    ): CameraBindResult = bindMutex.withLock {
        val args = BindArgs(lifecycleOwner, previewView, lensFacing, frameAnalyzer)
        lastBindArgs = args
        bindAttempt(args, isRetry = false, generation = bindGeneration)
    }

    /**
     * Re-binds with the arguments of the last [bind] (used when the capture enhancement changes, which needs
     * a different camera selector). Returns null when nothing is bound (the screen has unbound since).
     */
    suspend fun rebind(): CameraBindResult? = bindMutex.withLock {
        val args = lastBindArgs ?: return@withLock null
        bindAttempt(args, isRetry = false, generation = bindGeneration)
    }

    private suspend fun bindAttempt(args: BindArgs, isRetry: Boolean, generation: Int): CameraBindResult = try {
        val provider = cameraProvider ?: ProcessCameraProvider.getInstance(context).awaitFuture().also { cameraProvider = it }
        checkGeneration(generation)
        provider.unbindAll()
        // The screen binds as soon as it enters composition, before the PreviewView has been measured. A
        // ViewPort built from a 0x0 view would be 1:1 and crop both preview and analysis to a square, so
        // wait for the first layout pass before deriving the viewport from the view.
        args.previewView.awaitLayout()
        checkGeneration(generation)

        val desiredSelector = if (args.lensFacing == LensFacing.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val fallbackSelector = if (args.lensFacing == LensFacing.FRONT) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val baseSelector = when {
            provider.hasCamera(desiredSelector) -> desiredSelector
            provider.hasCamera(fallbackSelector) -> fallbackSelector
            else -> error("No available camera for this device")
        }
        val boundLens = if (baseSelector == CameraSelector.DEFAULT_FRONT_CAMERA) LensFacing.FRONT else LensFacing.BACK

        val extensions = extensionsSupport.managerFor(provider)
        checkGeneration(generation)
        val (available, pausesCoaching) = extensionsSupport.availableEnhancements(extensions, baseSelector, needsAnalysis = args.frameAnalyzer != null)
        val requested = runCatching { enhancementPreference() }.getOrElse { CaptureEnhancement.AUTO }
        val override = enhancementOverride
        val resolved = CaptureQuality.resolveEnhancement(override ?: requested, available)

        val config = UseCaseConfig(preferFastCapture, flashMode.toImageCaptureFlashMode(), captureSurfaceRotation, analysisResolution, analysisExecutor)
        val stillCurrent = { checkGeneration(generation) }
        val bound = try {
            bindUseCases(provider, args, baseSelector, resolved, extensions, config, stillCurrent)
        } catch (t: Throwable) {
            if (t is CancellationException || resolved == CaptureEnhancement.OFF) throw t
            Log.w(TAG, "Binding under the $resolved extension failed; falling back to no enhancement", t)
            provider.unbindAll()
            bindUseCases(provider, args, baseSelector, CaptureEnhancement.OFF, extensions, config, stillCurrent)
        }

        preview = bound.preview
        imageCapture = bound.imageCapture
        imageAnalysis = bound.imageAnalysis
        camera = bound.camera
        captureResultTap = bound.tap
        applyFlashMode(flashMode)

        if (boundLens != args.lensFacing) {
            Log.w(TAG, "Requested ${args.lensFacing} but device only has the other lens; bound to $boundLens.")
        }
        val zoom = bound.camera.cameraInfo.zoomState.value
        val info = CaptureSessionInfo(
            lensFacing = boundLens,
            requestedEnhancement = requested,
            outcomeEnhancement = override,
            activeEnhancement = bound.enhancement,
            availableEnhancements = available,
            enhancementsPausingCoaching = pausesCoaching,
            analysisPausedByEnhancement = args.frameAnalyzer != null && bound.imageAnalysis == null,
            captureModeName = if (preferFastCapture) "MINIMIZE_LATENCY" else "MAXIMIZE_QUALITY",
            jpegQuality = CaptureQuality.JPEG_QUALITY,
            qualityFlagsApplied = bound.qualityFlagsApplied,
            minZoomRatio = zoom?.minZoomRatio ?: 1f,
            maxZoomRatio = zoom?.maxZoomRatio ?: 1f,
        ).withResolutionCheck(bound.imageCapture, bound.characteristicsInfo)
        session = info
        Log.i(
            TAG,
            "Bound $boundLens enhancement=${info.activeEnhancement} (requested ${info.requestedEnhancement}, " +
                "available ${info.availableEnhancements}) analysisPaused=${info.analysisPausedByEnhancement} " +
                "qualityFlags=${info.qualityFlagsApplied} mode=${info.captureModeName}",
        )

        val exposureState = bound.camera.cameraInfo.exposureState
        val exposureRange = if (exposureState.isExposureCompensationSupported) exposureState.exposureCompensationRange else Range(0, 0)
        CameraBindResult.Success(
            camera = bound.camera,
            hasFlashUnit = bound.camera.cameraInfo.hasFlashUnit(),
            lensFacing = boundLens,
            exposureRange = exposureRange,
            session = info,
            controller = this,
        )
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        if (!isRetry && (t is IllegalStateException || t is CameraUnavailableException)) {
            Log.w(TAG, "Camera bind failed with a likely-transient error; retrying once in ${RETRY_DELAY_MS}ms", t)
            delay(RETRY_DELAY_MS)
            bindAttempt(args, isRetry = true, generation = generation)
        } else {
            Log.e(TAG, "Camera bind failed", t)
            CameraBindResult.Failure(t)
        }
    }

    private fun checkGeneration(generation: Int) {
        if (generation != bindGeneration) throw CancellationException("Camera was unbound while binding")
    }

    /**
     * Unbinds all use cases, on the main thread as CameraX requires (marshalled there if called elsewhere). Also
     * forgets the last bind's arguments (a later [rebind] is a no-op and the screen's view/lifecycle is no longer
     * referenced) and aborts any bind still in flight.
     */
    fun unbind() {
        runOnMainThread {
            bindGeneration++
            lastBindArgs = null
            cameraProvider?.unbindAll()
            camera = null
            preview = null
            imageCapture = null
            imageAnalysis = null
            session = null
            captureResultTap = null
        }
    }

    fun shutdown() {
        unbind()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    /**
     * Sets [ImageCapture.targetRotation] from the phone's quantized *physical* rotation ([deviceRotationDegrees],
     * `FrameAnalysis.deviceRotationDegrees`), not the display's (always portrait here). It is a live property, so
     * calling this on every change keeps the saved JPEG's EXIF upright however the phone was held; the in-memory
     * path reads it back from `ImageProxy.imageInfo.rotationDegrees`. Mapping: [CaptureRotation.surfaceRotationFor].
     */
    fun setCaptureRotationDegrees(deviceRotationDegrees: Int) {
        val rotation = CaptureRotation.surfaceRotationFor(deviceRotationDegrees)
        captureSurfaceRotation = rotation
        imageCapture?.targetRotation = rotation
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

        /** The coaching analysis stream: enough for the detectors, cheap enough for 10 Hz. */
        val DEFAULT_ANALYSIS_RESOLUTION = Size(640, 480)

        /**
         * The analysis stream while a Motion outcome is selected (stacks are built from these frames): the
         * largest 4:3 size tried, CameraX picks the closest it offers. [MOTION_ANALYSIS_FALLBACK_RESOLUTION]
         * replaces it when a hold measured under 8 fps at this size (see `MotionCapture.analysisResolution`).
         */
        val MOTION_ANALYSIS_RESOLUTION = Size(1920, 1440)

        /** The smaller Motion analysis size for devices that cannot sustain 8 fps at [MOTION_ANALYSIS_RESOLUTION]. */
        val MOTION_ANALYSIS_FALLBACK_RESOLUTION = Size(1280, 960)

        private fun defaultEnhancementPreference(context: Context): suspend () -> CaptureEnhancement = {
            SettingsRepository(context).settings.first().captureEnhancement
        }
    }
}

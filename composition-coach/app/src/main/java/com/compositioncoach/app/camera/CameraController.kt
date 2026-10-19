package com.compositioncoach.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraUnavailableException
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.compositioncoach.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * crops all three to *the same field of view* regardless of their different output resolutions. The preview
 * box is 3:4 portrait, which is the 4:3 sensor stream rotated, so the crop for [ImageCapture] is the whole
 * stream: [bind] verifies this (see "Resolution check" below), because a partial crop would make CameraX
 * decode, crop and *re-encode* every JPEG in software — a silent generation loss.
 *
 * ## Image-quality configuration (the "why is the stock app sharper" work)
 * 1. **CameraX Extensions.** On every bind the lens is asked which of AUTO/HDR/NIGHT its vendor extensions
 *    library offers ([CaptureSessionInfo.availableEnhancements]). When the persisted preference
 *    (`capture_enhancement`, read through [enhancementPreference]) is available, the session binds with
 *    `ExtensionsManager.getExtensionEnabledCameraSelector`, which routes the still capture through the
 *    vendor's own multi-frame merge (on Pixels, the same family of processing the stock app uses). That is the
 *    single biggest lever a third-party app has; everything below is for when it is unavailable or OFF.
 *    Trade-off: many vendor sessions cannot also carry [ImageAnalysis]
 *    (`ExtensionsManager.isImageAnalysisSupported`). Then Preview + ImageCapture bind under the extension and
 *    analysis is simply not bound — live coaching pauses (`CaptureSessionInfo.analysisPausedByEnhancement`,
 *    shown as a small note) rather than running a second camera session, which phones cannot do on the same
 *    lens anyway. That trade is only ever made on an explicit HDR/Night choice: AUTO, the default, is offered
 *    only when its session keeps analysis ([CaptureQuality.offeredEnhancements]), so first launch never opens
 *    with coaching switched off. If binding under the extension throws, the controller falls back to OFF in
 *    the same bind.
 * 2. **Camera2 request options** (only when no extension owns the ISP, and not while conserving power), each
 *    set only when the camera lists the mode as available:
 *    - `NOISE_REDUCTION_MODE` and `EDGE_MODE` = HIGH_QUALITY: the ISP's best spatial denoise and its
 *      full-quality sharpening kernel instead of the real-time ones. `TEMPLATE_STILL_CAPTURE` usually
 *      defaults to these already; stating them stops a repeating-request default from leaking into the still.
 *    - `COLOR_CORRECTION_ABERRATION_MODE` = HIGH_QUALITY: removes lateral chromatic aberration, whose colour
 *      fringes read as softness at high-contrast edges, especially in the corners.
 *    - `HOT_PIXEL_MODE` = HIGH_QUALITY: hot pixels are otherwise sharpened into bright specks.
 *    - `DISTORTION_CORRECTION_MODE` = HIGH_QUALITY (API 28+): the lens distortion model is applied with the
 *      high-quality resampler; it also makes the preview geometry match the saved photo's.
 *    - `LENS_OPTICAL_STABILIZATION_MODE` = ON: OIS cancels most low-frequency hand shake during the
 *      exposure; it is the hardware half of steady-shot (see [MotionSensor]).
 *    - `TONEMAP_MODE` is deliberately **not** set: HIGH_QUALITY would replace the vendor's tuned (often
 *      scene-adaptive, local) tone curve with a generic one, fighting the vendor pipeline for no sharpness gain.
 *    CameraX merges the Camera2Interop options of every use case in a session into the one repeating
 *    request, so the same values are set on [Preview] as on [ImageCapture]; setting them on capture alone
 *    would still reach the preview stream, just non-deterministically.
 * 3. **JPEG quality 100** ([CaptureQuality.JPEG_QUALITY]): quantization at 95 already discards fine,
 *    low-contrast texture (foliage, hair, fabric), which is exactly what "less sharp" means when comparing
 *    files at 100% zoom. The cost is a ~1.5-2x larger file.
 * 4. **Resolution check.** After binding, the [ImageCapture] stream size is compared with the largest 4:3 JPEG
 *    size in the camera's stream configuration map, and its ViewPort crop rect with the full stream; both are
 *    logged and recorded in [CaptureSessionInfo] / `CaptureDiagnostics.isFullResolution`.
 *
 * A [CaptureResultTap] is installed on [ImageCapture] (outside extensions) so each capture's diagnostics can
 * report the noise-reduction/edge/OIS modes the HAL actually used.
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
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindMutex = Mutex()

    var imageCapture: ImageCapture? = null
        private set
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var extensionsManagerLoaded = false

    /** Incremented by [unbind]; a bind that started under an older generation aborts instead of binding. */
    @Volatile private var bindGeneration = 0
    private var lastBindArgs: BindArgs? = null

    /** What the current bind configured; null while unbound. */
    @Volatile var session: CaptureSessionInfo? = null
        private set

    /** Still-capture result tap of the current bind; null under an extension or while unbound. */
    @Volatile var captureResultTap: CaptureResultTap? = null
        private set

    var flashMode: FlashMode = FlashMode.OFF
        private set

    /**
     * When true, the next (re)bind builds [ImageCapture] with `CAPTURE_MODE_MINIMIZE_LATENCY` instead of
     * the default `CAPTURE_MODE_MAXIMIZE_QUALITY`, and skips the high-quality Camera2 request options — set
     * this from a battery-saver/thermal signal via [setPreferFastCapture]. CameraX has no API to change an
     * already-bound [ImageCapture]'s capture mode live (it's fixed at `Builder.build()` time), so a change here
     * only takes effect on the next actual rebind; callers that want it to apply immediately must include
     * whatever drives this flag in their rebind `DisposableEffect`'s keys, the same way `lensFacing` already is.
     */
    @Volatile private var preferFastCapture: Boolean = false

    /**
     * The last physical-hold rotation handed to [setCaptureRotationDegrees], re-applied to every newly built
     * [ImageCapture]: a rebind (lens switch, enhancement change) creates a fresh use case whose rotation would
     * otherwise fall back to the portrait display rotation until the phone happened to rotate again, tagging a
     * landscape photo as portrait.
     */
    @Volatile private var captureSurfaceRotation: Int? = null

    val hasFlashUnit: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    /** The bound camera's current zoom ratio, or null while unbound. */
    val currentZoomRatio: Float? get() = camera?.cameraInfo?.zoomState?.value?.zoomRatio

    /**
     * Sets whether the next bind should trade capture quality for shutter speed — see
     * [preferFastCapture]'s KDoc for why this isn't applied to an already-bound [ImageCapture].
     */
    fun setPreferFastCapture(preferFast: Boolean) {
        preferFastCapture = preferFast
    }

    /**
     * Binds preview + capture + analysis for [lensFacing] to [lifecycleOwner], targeting [previewView].
     * Safe to call again (e.g. on lens switch): any existing binding is unbound first. Binds are serialized,
     * so a screen-driven bind and a view-model-driven [rebind] never interleave.
     *
     * Retries once, after [RETRY_DELAY_MS], on the two CameraX failure modes known to be transient rather
     * than permanent — [IllegalStateException] (CameraX's own catch-all for "camera already
     * in use"/state races, most often seen right after another app or `Activity` instance released the
     * camera) and [CameraUnavailableException] (the camera service briefly busy/unavailable) — before
     * giving up and reporting [CameraBindResult.Failure]. Any other exception fails immediately without a
     * retry. Cancellation (the screen disposing the bind, or [unbind] during a bind) propagates as
     * cancellation rather than being reported as a camera failure.
     */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: LensFacing,
        // Null when the vision pipeline is unavailable (e.g. the :vision stub) — the preview and capture
        // use cases still bind so the user still has a working camera; only live analysis is skipped.
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

        val extensions = extensionsManagerFor(provider)
        checkGeneration(generation)
        val (available, pausesCoaching) = availableEnhancements(extensions, baseSelector, needsAnalysis = args.frameAnalyzer != null)
        val requested = runCatching { enhancementPreference() }.getOrElse { CaptureEnhancement.AUTO }
        val resolved = CaptureQuality.resolveEnhancement(requested, available)

        val bound = try {
            bindUseCases(provider, args, baseSelector, resolved, extensions, generation)
        } catch (t: Throwable) {
            if (t is CancellationException || resolved == CaptureEnhancement.OFF) throw t
            Log.w(TAG, "Binding under the $resolved extension failed; falling back to no enhancement", t)
            provider.unbindAll()
            bindUseCases(provider, args, baseSelector, CaptureEnhancement.OFF, extensions, generation)
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

    /** Builds the three use cases for [enhancement] and binds them; see the class doc for every choice made here. */
    private fun bindUseCases(
        provider: ProcessCameraProvider,
        args: BindArgs,
        baseSelector: CameraSelector,
        enhancement: CaptureEnhancement,
        extensions: ExtensionsManager?,
        generation: Int,
    ): BoundUseCases {
        val extensionMode = enhancement.extensionMode()
        val useExtension = extensionMode != null && extensions != null
        val selector = if (useExtension) extensions!!.getExtensionEnabledCameraSelector(baseSelector, extensionMode!!) else baseSelector
        val analysisAllowed = !useExtension || runCatching { extensions!!.isImageAnalysisSupported(baseSelector, extensionMode!!) }.getOrDefault(false)
        // The base (non-extension) lens's CameraInfo, for its Camera2 characteristics. CameraSelector.filter is
        // the stable API; ProcessCameraProvider.getCameraInfo is still @ExperimentalCameraInfo.
        val characteristics = runCatching { baseSelector.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull()
        val applyQualityFlags = !useExtension && !preferFastCapture && characteristics != null
        val tap = if (!useExtension) CaptureResultTap() else null

        val targetRotation = args.previewView.display?.rotation ?: Surface.ROTATION_0

        val previewBuilder = Preview.Builder().setTargetRotation(targetRotation)
        var flagsApplied = false
        if (applyQualityFlags) {
            flagsApplied = applyQualityOptions(previewBuilder, characteristics!!)
        }
        val newPreview = previewBuilder.build().apply { surfaceProvider = args.previewView.surfaceProvider }

        val captureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
        val captureBuilder = ImageCapture.Builder()
            // MAXIMIZE_QUALITY by default: this is a coaching camera whose whole point is a better final
            // photo, so once framing is dialed in the extra processing time is worth it. Falls back to
            // MINIMIZE_LATENCY when the device itself says it's conserving resources (battery saver or a
            // thermal throttle tier) via setPreferFastCapture.
            .setCaptureMode(if (preferFastCapture) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(CaptureQuality.JPEG_QUALITY)
            .setResolutionSelector(captureResolutionSelector)
            .setTargetRotation(captureSurfaceRotation ?: targetRotation)
            .setFlashMode(flashMode.toImageCaptureFlashMode())
        if (applyQualityFlags) applyQualityOptions(captureBuilder, characteristics!!)
        if (tap != null) Camera2Interop.Extender(captureBuilder).setSessionCaptureCallback(tap)
        val newImageCapture = captureBuilder.build()

        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val newImageAnalysis = args.frameAnalyzer?.takeIf { analysisAllowed }?.let { analyzer ->
            ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolutionSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(analysisExecutor, analyzer) }
        }

        // Share one ViewPort so the analysis and capture crops match exactly what the preview shows on
        // screen — see the class doc above for why. PreviewView.viewPort is null until the view is
        // attached to a display; after awaitLayout() the fallback below uses the real measured size.
        val viewPort = args.previewView.viewPort ?: ViewPort.Builder(
            Rational(args.previewView.width.coerceAtLeast(1), args.previewView.height.coerceAtLeast(1)),
            targetRotation,
        ).build()

        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(newPreview)
            .addUseCase(newImageCapture)
            .apply { newImageAnalysis?.let { addUseCase(it) } }
            .build()

        checkGeneration(generation)
        val boundCamera = provider.bindToLifecycle(args.lifecycleOwner, selector, useCaseGroup)
        return BoundUseCases(
            camera = boundCamera,
            preview = newPreview,
            imageCapture = newImageCapture,
            imageAnalysis = newImageAnalysis,
            enhancement = if (useExtension) enhancement else CaptureEnhancement.OFF,
            qualityFlagsApplied = flagsApplied,
            tap = tap,
            characteristicsInfo = characteristics,
        )
    }

    /**
     * Sets the high-quality Camera2 request options the camera advertises (see the class doc for the
     * photographic reason behind each). Returns whether at least one option was set.
     */
    private fun <T> applyQualityOptions(builder: ExtendableBuilder<T>, cameraInfo: CameraInfo): Boolean {
        val c2 = runCatching { Camera2CameraInfo.from(cameraInfo) }.getOrNull() ?: return false
        val extender = Camera2Interop.Extender(builder)
        var any = false
        fun IntArray?.offers(mode: Int) = this?.contains(mode) == true

        if (c2.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                .offers(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        ) {
            extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            any = true
        }
        if (c2.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES).offers(CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
            extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            any = true
        }
        if (c2.getCameraCharacteristic(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
                .offers(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        ) {
            extender.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            )
            any = true
        }
        if (c2.getCameraCharacteristic(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
                .offers(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        ) {
            extender.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
            any = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            c2.getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                .offers(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
        ) {
            extender.setCaptureRequestOption(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            )
            any = true
        }
        if (c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                .offers(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        ) {
            extender.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
            )
            any = true
        }
        return any
    }

    /**
     * Fills in the resolution check described in the class doc and logs its verdict: a WARN line whenever
     * the photo will not use every pixel a third-party app can get.
     */
    private fun CaptureSessionInfo.withResolutionCheck(capture: ImageCapture, cameraInfo: CameraInfo?): CaptureSessionInfo {
        val resolutionInfo = runCatching { capture.resolutionInfo }.getOrNull()
        val configured = resolutionInfo?.resolution?.let { CaptureQuality.PixelSize(it.width, it.height) }
        val crop = resolutionInfo?.cropRect
        val cropFull = if (resolutionInfo != null && crop != null) {
            crop.left == 0 && crop.top == 0 && crop.width() == resolutionInfo.resolution.width &&
                crop.height() == resolutionInfo.resolution.height
        } else {
            null
        }
        val largest = cameraInfo?.let { info ->
            runCatching {
                val map = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.map { CaptureQuality.PixelSize(it.width, it.height) }.orEmpty()
                CaptureQuality.largestFourByThree(sizes)
            }.getOrNull()
        }
        val checked = copy(configuredCaptureSize = configured, largestFourByThree = largest, cropIsFullFrame = cropFull)
        val message = "ImageCapture stream=$configured crop=${crop?.toShortString()} largest4:3=$largest " +
            "fullResolution=${checked.isFullResolution}"
        if (checked.isFullResolution == false) Log.w(TAG, "$message (photo will not use every available pixel)") else Log.i(TAG, message)
        return checked
    }

    private suspend fun extensionsManagerFor(provider: ProcessCameraProvider): ExtensionsManager? {
        if (extensionsManagerLoaded) return extensionsManager
        extensionsManagerLoaded = true
        extensionsManager = runCatching {
            withTimeoutOrNull(EXTENSIONS_TIMEOUT_MS) { ExtensionsManager.getInstanceAsync(context, provider).awaitFuture() }
        }.onFailure { Log.w(TAG, "CameraX Extensions unavailable", it) }.getOrNull()
        return extensionsManager
    }

    /** The enhancements to offer for [selector]; see [CaptureQuality.offeredEnhancements] for the AUTO rule. */
    private fun availableEnhancements(extensions: ExtensionsManager?, selector: CameraSelector, needsAnalysis: Boolean): Pair<Set<CaptureEnhancement>, Set<CaptureEnhancement>> {
        if (extensions == null) return setOf(CaptureEnhancement.OFF) to emptySet()
        val available = mutableSetOf<CaptureEnhancement>()
        val analysisSupported = mutableSetOf<CaptureEnhancement>()
        for (enhancement in CaptureEnhancement.entries) {
            val mode = enhancement.extensionMode() ?: continue
            if (!runCatching { extensions.isExtensionAvailable(selector, mode) }.getOrDefault(false)) continue
            available += enhancement
            if (runCatching { extensions.isImageAnalysisSupported(selector, mode) }.getOrDefault(false)) analysisSupported += enhancement
        }
        Log.i(TAG, "Extensions available=$available withAnalysis=$analysisSupported")
        val offered = CaptureQuality.offeredEnhancements(available, analysisSupported, needsAnalysis)
        val pausesCoaching = if (needsAnalysis) offered.filter { it != CaptureEnhancement.OFF && it !in analysisSupported }.toSet() else emptySet()
        return offered to pausesCoaching
    }

    /**
     * Unbinds all use cases. CameraX requires `bindToLifecycle`/`unbindAll` to run on the main thread;
     * this is normally already true (the screen's `DisposableEffect.onDispose` runs on the composition's
     * main-thread dispatcher), but as a defensive measure against any caller that isn't, this marshals onto
     * the main thread itself rather than letting CameraX throw. Also forgets the last bind's arguments (so a
     * later [rebind] is a no-op and the controller no longer references the screen's view/lifecycle) and
     * aborts any bind still in flight.
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
     * Sets [ImageCapture.targetRotation] to whichever `Surface.ROTATION_*` matches [deviceRotationDegrees]
     * — the phone's quantized *physical* rotation from natural portrait (see `:vision`'s
     * `DeviceRotationQuantizer`/`FrameAnalysis.deviceRotationDegrees`), not `previewView.display.rotation`
     * (which never changes for this portrait-locked app). `ImageCapture.targetRotation` is a *live* property
     * CameraX honours without a rebind, so this is called every time [deviceRotationDegrees] changes; that's
     * what makes the saved JPEG upright in the gallery regardless of how the phone was held. The in-memory
     * capture path reads the same rotation back from `ImageProxy.imageInfo.rotationDegrees`. The mapping
     * itself lives in [CaptureRotation.surfaceRotationFor].
     */
    fun setCaptureRotationDegrees(deviceRotationDegrees: Int) {
        val rotation = CaptureRotation.surfaceRotationFor(deviceRotationDegrees)
        captureSurfaceRotation = rotation
        imageCapture?.targetRotation = rotation
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
        val current = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        return setZoomRatio(current * delta)
    }

    /**
     * Sets an absolute zoom ratio, clamped to the bound camera's range. CameraX applies it on the next
     * frame (it does not animate); the view model eases between presets by calling this in small steps.
     * @return the ratio applied, or null when no camera is bound.
     */
    fun setZoomRatio(ratio: Float): Float? {
        val cam = camera ?: return null
        val state = cam.cameraInfo.zoomState.value
        val min = state?.minZoomRatio ?: 1f
        val max = state?.maxZoomRatio ?: 1f
        val next = ratio.coerceIn(min, max)
        runCatching { cam.cameraControl.setZoomRatio(next) }
        return next
    }

    /**
     * Sets exposure compensation, clamped to the bound camera's supported range (see
     * [CameraBindResult.Success.exposureRange]). A no-op if there is no bound camera, or the device
     * doesn't support exposure compensation at all (an empty/zero range). CameraX resets the index to 0
     * whenever the camera is rebound, which is why `CameraUiState.withCameraBound` resets its copy too.
     */
    fun setExposureCompensation(index: Int) {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val clamped = index.coerceIn(range.lower, range.upper)
        runCatching { cam.cameraControl.setExposureCompensationIndex(clamped) }
    }

    /**
     * Issues [count] in-memory captures back to back (all requests are queued at once, so CameraX runs them
     * as fast as the pipeline allows) and returns their JPEG bytes in capture order. Each `ImageProxy` is
     * copied and closed inside its callback so the capture `ImageReader` never runs out of buffers.
     * Throws if any frame fails; the caller falls back to a single capture.
     */
    suspend fun captureBurst(capture: ImageCapture, count: Int): List<CapturedJpeg> = withContext(Dispatchers.Main.immediate) {
        val pending = List(count) { CompletableDeferred<CapturedJpeg>() }
        pending.forEach { deferred -> capture.takePicture(captureExecutor, InMemoryCallback(deferred)) }
        pending.awaitAll()
    }

    private class InMemoryCallback(private val result: CompletableDeferred<CapturedJpeg>) : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            try {
                result.complete(image.toCapturedJpeg())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            } finally {
                image.close()
            }
        }

        override fun onError(exception: ImageCaptureException) {
            result.completeExceptionally(exception)
        }
    }

    private inline fun runOnMainThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private class BindArgs(
        val lifecycleOwner: LifecycleOwner,
        val previewView: PreviewView,
        val lensFacing: LensFacing,
        val frameAnalyzer: ImageAnalysis.Analyzer?,
    )

    private class BoundUseCases(
        val camera: Camera,
        val preview: Preview,
        val imageCapture: ImageCapture,
        val imageAnalysis: ImageAnalysis?,
        val enhancement: CaptureEnhancement,
        val qualityFlagsApplied: Boolean,
        val tap: CaptureResultTap?,
        val characteristicsInfo: CameraInfo?,
    )

    companion object {
        private const val TAG = "CameraController"
        private const val RETRY_DELAY_MS = 300L
        private const val EXTENSIONS_TIMEOUT_MS = 2_000L

        private fun defaultEnhancementPreference(context: Context): suspend () -> CaptureEnhancement = {
            SettingsRepository(context).settings.first().captureEnhancement
        }
    }
}

/** One in-memory JPEG capture, copied out of its `ImageProxy`. */
class CapturedJpeg(
    val bytes: ByteArray,
    /** Clockwise rotation that makes the stored pixels upright (`ImageInfo.rotationDegrees`). */
    val rotationDegrees: Int,
    /** `SENSOR_TIMESTAMP` of the frame; matches [CaptureResultTap.StillResult.sensorTimestampNs]. */
    val sensorTimestampNs: Long,
    val width: Int,
    val height: Int,
)

private fun ImageProxy.toCapturedJpeg(): CapturedJpeg {
    require(format == ImageFormat.JPEG) { "Expected a JPEG ImageProxy, got format $format" }
    val buffer = planes[0].buffer
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return CapturedJpeg(bytes, imageInfo.rotationDegrees, imageInfo.timestamp, width, height)
}

/** CameraX Extensions mode for an enhancement, or null for OFF. */
internal fun CaptureEnhancement.extensionMode(): Int? = when (this) {
    CaptureEnhancement.OFF -> null
    CaptureEnhancement.AUTO -> ExtensionMode.AUTO
    CaptureEnhancement.HDR -> ExtensionMode.HDR
    CaptureEnhancement.NIGHT -> ExtensionMode.NIGHT
}

package com.compositioncoach.app.camera

import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * Building and binding [CameraController]'s use cases: Preview, ImageCapture and (when a frame analyzer is given
 * and the session can carry it) ImageAnalysis, as one [UseCaseGroup] sharing one [ViewPort]. Split out of
 * [CameraController] (whose class doc explains every choice) so each file stays readable; the controller calls
 * [bindUseCases] from its serialized bind with the [UseCaseConfig] of that bind.
 */

/** The controller's per-bind settings, snapshotted when a bind starts. */
internal class UseCaseConfig(
    val preferFastCapture: Boolean,
    /** `ImageCapture.FLASH_MODE_*` for the new [ImageCapture]. */
    val imageCaptureFlashMode: Int,
    /** The physical-hold capture rotation to re-apply to a fresh [ImageCapture] (null = display rotation). */
    val captureSurfaceRotation: Int?,
    val analysisResolution: Size,
    val analysisExecutor: Executor,
)

internal class BindArgs(
    val lifecycleOwner: LifecycleOwner,
    val previewView: PreviewView,
    val lensFacing: LensFacing,
    val frameAnalyzer: ImageAnalysis.Analyzer?,
)


internal class BoundUseCases(
    val camera: Camera,
    val preview: Preview,
    val imageCapture: ImageCapture,
    val imageAnalysis: ImageAnalysis?,
    val enhancement: CaptureEnhancement,
    val qualityFlagsApplied: Boolean,
    val tap: CaptureResultTap?,
    val characteristicsInfo: CameraInfo?,
)


/**
 * Builds the three use cases for [enhancement] and binds them; see [CameraController]'s class doc for the rest.
 *
 * ## Why capture is cropped to the shared [ViewPort]
 * [ImageCapture] targets 4:3 at the sensor's highest available resolution ([AspectRatioStrategy]
 * `RATIO_4_3_FALLBACK_AUTO_STRATEGY` + [ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY]) — the classic
 * "Pixel-style" full-quality still aspect — while [ImageAnalysis] stays at ~640×480 4:3 for detector
 * throughput. Both use cases are bound into the same [UseCaseGroup]/[ViewPort] as [Preview], so CameraX
 * crops all three to *the same field of view* regardless of their different output resolutions. The preview
 * box is 3:4 portrait, which is the 4:3 sensor stream rotated, so the crop for [ImageCapture] is the whole
 * stream: the bind verifies this ([withResolutionCheck]), because a partial crop would make CameraX
 * decode, crop and *re-encode* every JPEG in software — a silent generation loss.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun bindUseCases(
    provider: ProcessCameraProvider,
    args: BindArgs,
    baseSelector: CameraSelector,
    enhancement: CaptureEnhancement,
    extensions: ExtensionsManager?,
    config: UseCaseConfig,
    checkGeneration: () -> Unit,
): BoundUseCases {
    val extensionMode = enhancement.extensionMode()
    val useExtension = extensionMode != null && extensions != null
    val selector = if (useExtension) extensions!!.getExtensionEnabledCameraSelector(baseSelector, extensionMode!!) else baseSelector
    val analysisAllowed = !useExtension || runCatching { extensions!!.isImageAnalysisSupported(baseSelector, extensionMode!!) }.getOrDefault(false)
    // The base (non-extension) lens's CameraInfo, for its Camera2 characteristics. CameraSelector.filter is
    // the stable API; ProcessCameraProvider.getCameraInfo is still @ExperimentalCameraInfo.
    val characteristics = runCatching { baseSelector.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull()
    val applyQualityFlags = !useExtension && !config.preferFastCapture && characteristics != null
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
        .setCaptureMode(if (config.preferFastCapture) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setJpegQuality(CaptureQuality.JPEG_QUALITY)
        .setResolutionSelector(captureResolutionSelector)
        .setTargetRotation(config.captureSurfaceRotation ?: targetRotation)
        .setFlashMode(config.imageCaptureFlashMode)
    if (applyQualityFlags) applyQualityOptions(captureBuilder, characteristics!!)
    if (tap != null) Camera2Interop.Extender(captureBuilder).setSessionCaptureCallback(tap)
    val newImageCapture = captureBuilder.build()

    val analysisResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(config.analysisResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
        )
        .build()
    val newImageAnalysis = args.frameAnalyzer?.takeIf { analysisAllowed }?.let { analyzer ->
        ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(config.analysisExecutor, analyzer) }
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

    checkGeneration()
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


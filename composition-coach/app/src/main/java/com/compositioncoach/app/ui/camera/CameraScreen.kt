package com.compositioncoach.app.ui.camera

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.ui.theme.Background
import com.compositioncoach.composition.model.SceneClassification
import kotlinx.coroutines.launch

private const val POST_CAPTURE_FADE_ALPHA = 0.3f

/**
 * The camera experience, in the four zones `docs/APP_UX.md` lays out: a translucent top strip (flash,
 * settings), the 4:3 preview with the coaching layer over it, the mode strip, and the bottom bar with the
 * gallery thumbnail, the shutter (carrying the readiness arc) and the lens switch.
 *
 * The [PreviewView] and *every* overlay that does normalized-to-pixel geometry share one
 * `Modifier.aspectRatio(3f/4f)` box anchored below the status bar; keeping them in that one box, with
 * `PreviewView` staying `FILL_CENTER`, is what lets [OverlayMapper] place things with a plain
 * normalized-to-pixel multiply (see its class doc). The rest of the screen is plain black.
 *
 * All coaching visuals live in [CoachingLayer]; this composable owns camera plumbing, gestures and the
 * screen's zones, and deliberately holds no coaching logic of its own.
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    lastPhotoUri: Uri?,
    onOpenSettings: () -> Unit,
    onNavigateToReview: () -> Unit,
    autoShootOnTap: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val animationsEnabled = rememberAnimationsEnabled()

    val cameraController = remember { CameraController(context.applicationContext) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Pinch readout and the lit preset chip; see ZoomDisplayState.
    val zoom = rememberZoomDisplayState(uiState.zoomRatio)
    // EV per exposure step for the slider's "+0.7", read from the bound camera; 0 until a bind succeeds.
    var exposureStepEv by remember { mutableFloatStateOf(0f) }

    // Tap-to-focus ring (and exposure slider): a fresh id per tap so retapping the same spot restarts them.
    var focusTapId by remember { mutableIntStateOf(0) }
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }

    KeepScreenOn()

    DisposableEffect(viewModel) {
        viewModel.onScreenStarted()
        onDispose { viewModel.onScreenStopped() }
    }
    // Sensors and ML Kit detectors follow the Activity lifecycle, not just composition: backgrounding the app
    // must release them, and coming back must re-arm them and reset the smoother.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onScreenPaused() }

    // Capture mode is fixed at bind time, so a change to the fast-capture preference rebinds.
    DisposableEffect(lifecycleOwner, uiState.lensFacing, uiState.preferFastCapture, retryTrigger) {
        val job = scope.launch {
            cameraController.setPreferFastCapture(uiState.preferFastCapture)
            val result = cameraController.bind(lifecycleOwner, previewView, uiState.lensFacing, viewModel.frameAnalyzer)
            exposureStepEv = exposureStepEvOf(result)
            viewModel.onCameraBindResult(result)
        }
        onDispose {
            job.cancel()
            cameraController.unbind()
        }
    }

    DisposableEffect(Unit) {
        onDispose { cameraController.shutdown() }
    }

    // ImageCapture.targetRotation is a live CameraX property (no rebind needed, unlike capture mode): keep
    // it following the phone's actual physical rotation so a saved photo's EXIF orientation is correct
    // regardless of how the phone was held when the shutter fired.
    LaunchedEffect(uiState.deviceRotationDegrees) {
        cameraController.setCaptureRotationDegrees(uiState.deviceRotationDegrees)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CameraEvent.NavigateToReview -> onNavigateToReview()
                // The one medium tick in the app, fired once on entering shoot-ready by the view model.
                CameraEvent.ShootReadyHapticTick -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    LaunchedEffect(uiState.captureError) {
        uiState.captureError?.let { snackbarHostState.showSnackbar(it) }
    }

    val capture = rememberCaptureAction(viewModel, cameraController)
    // A light tick on an ordinary shutter press; the capture flash and scale carry the rest.
    val onShutterClick: () -> Unit = { capture(HapticFeedbackType.TextHandleMove) }

    // Volume-down as a hardware shutter button (MainActivity.onKeyDown -> AppContainer.volumeDownEvents).
    LaunchedEffect(viewModel) {
        viewModel.volumeDownEvents.collect { onShutterClick() }
    }

    val postCaptureFadeAlpha by animateFloatAsState(
        targetValue = if (uiState.postCaptureFadeActive) POST_CAPTURE_FADE_ALPHA else 1f,
        animationSpec = tween(motionDuration(300, animationsEnabled)),
        label = "postCaptureFade",
    )
    val hasScene = uiState.composition.scene != SceneClassification.UNKNOWN
    val shootReady = GuidanceFormatter.effectiveShootReady(
        uiState.composition.isShootReady,
        uiState.composition.awaitingSubject,
    )
    // Shoot at the peak: hold the shutter (or tap, with "Auto shoot when ready" on) and the photo is taken
    // the first moment the framing is ready and the phone is still. Double tick on arm, medium on fire.
    val shootAtPeak = rememberShootAtPeakState(
        readyToFire = SteadyHint.peakReached(shootReady, uiState.steadiness),
        enabled = !uiState.isCapturing,
        latchOnTap = autoShootOnTap,
        onCapture = onShutterClick,
        onArmed = rememberDoubleTick(),
        onFire = { capture(HapticFeedbackType.LongPress) },
    )
    val holdStill = SteadyHint.showsHoldStill(uiState.steadiness, shootAtPeak.engaged || uiState.isCapturing, shootReady)
    val headline = GuidanceFormatter.headline(uiState.composition.activeRecommendations, uiState.settings.guidanceLevel)
    val quietCheck = GuidanceFormatter.showsQuietCheck(
        hasHeadline = headline != null,
        displayScore = uiState.composition.displayScore,
        isShootReady = uiState.composition.isShootReady,
        hasScene = hasScene,
    )

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, containerColor = Background) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                // The 4:3 preview: PreviewView plus every overlay that does normalized-to-pixel
                // geometry maths live in this exact box, and nothing else does — see the class doc.
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(cameraController) {
                                detectTapGestures { offset ->
                                    cameraController.tapToFocus(previewView, offset.x, offset.y)
                                    focusTapOffset = offset
                                    focusTapId++
                                }
                            }
                            .pointerInput(cameraController) {
                                detectTransformGestures { _, _, pinch, _ ->
                                    cameraController.applyZoomDelta(pinch)?.let(zoom::onPinch)
                                }
                            },
                    )

                    focusTapOffset?.let { offset ->
                        key(focusTapId) { FocusRingOverlay(tapOffset = offset) }
                        ExposureSlider(offset, focusTapId, uiState.exposureRange, uiState.exposureIndex, exposureStepEv, { index ->
                            cameraController.setExposureCompensation(index)
                            viewModel.onExposureCompensationChanged(index)
                        }, animationsEnabled)
                    }

                    if (uiState.settings.showThirdsGrid) ThirdsGridOverlay()

                    CoachingLayer(
                        composition = uiState.composition,
                        guidanceLevel = uiState.settings.guidanceLevel,
                        showScore = uiState.settings.showScore,
                        hasScene = hasScene,
                        deviceRotationDegrees = uiState.deviceRotationDegrees,
                        animationsEnabled = animationsEnabled,
                        modifier = Modifier.alpha(postCaptureFadeAlpha),
                        pausedText = null, // TODO(integrator): wire `analysisPausedByEnhancement`, see app/README.md
                    )

                    if (uiState.settings.debugMode) {
                        DebugGeometryOverlay(
                            uiState.composition,
                            uiState.debugFrame,
                            deviceRotationDegrees = uiState.deviceRotationDegrees,
                        )
                    }

                    CameraTopBar(
                        showDebugChip = uiState.settings.debugMode,
                        flashMode = uiState.flashMode,
                        hasFlashUnit = uiState.hasFlashUnit,
                        onFlashClick = { viewModel.onFlashModeChanged(cameraController.cycleFlashMode()) },
                        onSettingsClick = onOpenSettings,
                        deviceRotationDegrees = uiState.deviceRotationDegrees,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enhancement = uiState.enhancement,
                        availableEnhancements = uiState.availableEnhancements,
                        onEnhancementSelected = viewModel::onEnhancementSelected,
                        animationsEnabled = animationsEnabled,
                    )

                    if (uiState.settings.debugMode) {
                        DebugOverlay(
                            composition = uiState.composition,
                            debugStats = uiState.debugStats,
                            performanceTier = uiState.performanceTier.name,
                            deviceRotationDegrees = uiState.deviceRotationDegrees,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                        )
                    }

                    if (!uiState.settings.onboardingSeen) {
                        OnboardingCard(
                            onDismiss = viewModel::onOnboardingDismissed,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).padding(horizontal = 4.dp),
                        )
                    }
                }

                CameraBottomZone(
                    sceneIntent = uiState.settings.sceneIntent,
                    onSceneIntentSelected = viewModel::onSceneIntentSelected,
                    isCapturing = uiState.isCapturing,
                    isShootReady = shootReady,
                    score = uiState.composition.displayScore,
                    awaitingSubject = uiState.composition.awaitingSubject,
                    showQuietCheck = quietCheck,
                    analysisUnavailable = uiState.analysisUnavailable,
                    zoomRatio = zoom.ratio,
                    zoomChipVisible = zoom.chipVisible,
                    lastPhotoUri = uiState.lastPhotoUri ?: lastPhotoUri,
                    onShutterClick = onShutterClick,
                    onSwitchLensClick = { viewModel.onSwitchLensRequested() },
                    onOpenGallery = { openGallery(context, uiState.lastPhotoUri ?: lastPhotoUri) },
                    deviceRotationDegrees = uiState.deviceRotationDegrees,
                    animationsEnabled = animationsEnabled,
                    modifier = Modifier.weight(1f),
                    holdStill = holdStill,
                    arcAlpha = SteadyHint.arcAlpha(uiState.composition.awaitingSubject, uiState.steadiness),
                    zoomPresets = uiState.zoomPresets,
                    onZoomPresetSelected = { ratio ->
                        zoom.ratio = ratio
                        viewModel.onZoomPresetSelected(ratio)
                    },
                    shootAtPeak = shootAtPeak,
                )
            }

            uiState.cameraError?.let { message ->
                CameraErrorOverlay(
                    message = message,
                    onRetry = {
                        viewModel.dismissCameraError()
                        retryTrigger++
                    },
                )
            }
        }
    }
}

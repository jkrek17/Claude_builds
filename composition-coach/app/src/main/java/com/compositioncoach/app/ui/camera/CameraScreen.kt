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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compositioncoach.app.R
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.cycleFlashMode
import com.compositioncoach.app.camera.tapToFocus
import com.compositioncoach.app.ui.theme.Background
import kotlinx.coroutines.launch

private const val POST_CAPTURE_FADE_ALPHA = 0.3f

/**
 * The camera experience, in the four zones `docs/APP_UX.md` lays out: a translucent top strip (flash, timer,
 * settings), the 4:3 preview with the coaching layer over it, the outcome strip, and the bottom bar with the
 * gallery thumbnail, the shutter (carrying the readiness arc) and the lens switch. The settings in
 * [CameraUiState.settings] pick Easy or Coach and whether Pro controls (exposure slider, zoom presets,
 * enhancement pill, grid, debug) are drawn, and whether a tap arms shoot-at-the-peak (`auto_shoot`).
 *
 * The [PreviewView] and *every* overlay that does normalized-to-pixel geometry share one
 * `Modifier.aspectRatio(3f/4f)` box anchored below the status bar; keeping them in that one box, with
 * `PreviewView` staying `FILL_CENTER`, is what lets [OverlayMapper] place things with a plain
 * normalized-to-pixel multiply (see its class doc). The rest of the screen is plain black.
 *
 * All coaching visuals live in [CoachingLayer]; this composable owns camera plumbing, gestures and the
 * screen's zones, and deliberately holds no coaching logic of its own. What is drawn from [CameraUiState] is in
 * [CameraPreviewChrome] and [CameraBottomChrome] (derived values in [CameraChromeModel]), so the wiring from
 * state to chips is testable without a camera.
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    lastPhotoUri: Uri?,
    onOpenSettings: () -> Unit,
    onNavigateToReview: () -> Unit,
    galleryMenu: GalleryMenuActions = GalleryMenuActions(),
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

    // Capture mode is fixed at bind time, so a change to the fast-capture preference rebinds. The enhancement is
    // deliberately NOT a key here: CameraViewModel.onEnhancementSelected persists it and the view model owns
    // that rebind (CameraController.rebind), so keying on it would bind twice.
    DisposableEffect(lifecycleOwner, uiState.lensFacing, uiState.preferFastCapture, retryTrigger) {
        val job = scope.launch {
            cameraController.setPreferFastCapture(uiState.preferFastCapture)
            // The outcome's enhancement and analysis resolution ride on this bind (no second bind for them).
            viewModel.configureController(cameraController)
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

    val resources = context.resources
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CameraEvent.NavigateToReview -> onNavigateToReview()
                // The one medium tick in the app, fired once on entering shoot-ready by the view model.
                CameraEvent.ShootReadyHapticTick -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                is CameraEvent.Notice -> scope.launch { snackbarHostState.showSnackbar(resources.getString(event.notice.resId)) }
            }
        }
    }

    val captureFailed = uiState.captureError?.let { detail ->
        if (detail.isBlank()) stringResource(R.string.capture_failed) else stringResource(R.string.capture_failed_detail, detail)
    }
    LaunchedEffect(captureFailed) {
        captureFailed?.let { snackbarHostState.showSnackbar(it) }
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
    val chrome = CameraChromeModel.of(uiState)

    // Heads-up guidance (Settings > More): the cue as vibration and/or voice, for shooting without looking.
    HeadsUpGuidanceEffect(chrome.headsUp, vibration = uiState.settings.hapticGuidance, voice = uiState.settings.voiceGuidance)
    // Selfie defaults to a 3-second countdown through the ordinary timer path.
    SelfieTimerDefault(uiState.outcome, uiState.timerSeconds, viewModel::onTimerSelected)
    // Shoot at the peak: hold the shutter (or tap, with "Auto shoot when ready" on) and the photo is taken
    // the first moment the framing is ready and the phone is still. Double tick on arm, medium on fire.
    val shootAtPeak = rememberShootAtPeakState(
        readyToFire = SteadyHint.peakReached(chrome.shootReady, uiState.steadiness),
        enabled = !uiState.isCapturing,
        latchOnTap = uiState.settings.autoShoot,
        onCapture = onShutterClick,
        onArmed = rememberDoubleTick(),
        onFire = { capture(HapticFeedbackType.LongPress) },
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
                                // Through the view model, so a pinch cancels a preset ease still in flight;
                                // zoomRatio then follows the camera's own ZoomState.
                                detectTransformGestures { _, _, pinch, _ ->
                                    viewModel.onPinchZoom(pinch)?.let(zoom::onPinch)
                                }
                            },
                    )

                    focusTapOffset?.let { offset ->
                        key(focusTapId) { FocusRingOverlay(tapOffset = offset) }
                    }
                    // Exposure is a Pro control: in Easy a tap only focuses. The view model applies the index to
                    // the camera; a rebind resets exposureIndex to 0.
                    focusTapOffset?.takeIf { chrome.pro }?.let { offset ->
                        ExposureSlider(
                            tapOffset = offset,
                            tapId = focusTapId,
                            range = uiState.exposureRange,
                            index = uiState.exposureIndex,
                            stepEv = exposureStepEv,
                            onIndexChange = viewModel::onExposureCompensationChanged,
                            animationsEnabled = animationsEnabled,
                        )
                    }

                    if (chrome.pro && uiState.settings.showThirdsGrid) ThirdsGridOverlay()

                    CameraPreviewChrome(
                        state = uiState,
                        animationsEnabled = animationsEnabled,
                        onFlashClick = { viewModel.onFlashModeChanged(cameraController.cycleFlashMode()) },
                        onOpenSettings = onOpenSettings,
                        onEnhancementSelected = viewModel::onEnhancementSelected,
                        onOnboardingDismissed = viewModel::onOnboardingDismissed,
                        coachingAlpha = postCaptureFadeAlpha,
                        model = chrome,
                        onTimerSelected = viewModel::onTimerSelected,
                        onGhostDismiss = { viewModel.onUseAsGhost(null) },
                    )
                }

                CameraBottomChrome(
                    state = uiState,
                    displayZoomRatio = zoom.ratio,
                    zoomChipVisible = zoom.chipVisible,
                    lastPhotoUri = uiState.lastPhotoUri ?: lastPhotoUri,
                    animationsEnabled = animationsEnabled,
                    onShutterClick = onShutterClick,
                    onSwitchLensClick = viewModel::onSwitchLensRequested,
                    onOpenGallery = { openGallery(context, uiState.lastPhotoUri ?: lastPhotoUri) },
                    onOutcomeSelected = viewModel::onOutcomeSelected,
                    onZoomPresetSelected = { ratio ->
                        zoom.ratio = ratio
                        viewModel.onZoomPresetSelected(ratio)
                    },
                    modifier = Modifier.weight(1f),
                    shootAtPeak = shootAtPeak,
                    model = chrome,
                    galleryMenu = galleryMenu,
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

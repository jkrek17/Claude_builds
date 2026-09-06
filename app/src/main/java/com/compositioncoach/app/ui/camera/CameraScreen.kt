package com.compositioncoach.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.ui.theme.Background
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.composition.model.SceneClassification
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ZOOM_CHIP_LINGER_MS = 1_200L
private const val POST_CAPTURE_FADE_ALPHA = 0.3f

/**
 * The camera experience: a [PreviewView] and every overlay share one 4:3 area (Pixel-style — the sensor
 * shoots 4:3, so the preview should too), full width and anchored right below the status bar; the rest of
 * the screen is plain black. Top controls sit in a translucent strip over the top of that area; the score
 * badge and guidance banner overlay its upper half; the shutter row lives in the black area beneath it. See
 * [CameraController] for how the preview and the live analysis stream are kept in the same field of view,
 * and [OverlayMapper] for why keeping every overlay in that one 4:3 box is what lets them place things with
 * a plain normalized-to-pixel multiply — a differently-sized overlay box would break that mapping.
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    lastPhotoUri: Uri?,
    onOpenSettings: () -> Unit,
    onNavigateToReview: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val cameraController = remember { CameraController(context.applicationContext) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Pinch-zoom chip: shown while pinching, fades out ZOOM_CHIP_LINGER_MS after the last pinch delta.
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var zoomChipVisible by remember { mutableStateOf(false) }
    var zoomChipHideJob by remember { mutableStateOf<Job?>(null) }

    // Tap-to-focus ring: a fresh id per tap so retapping the same spot restarts the animation via `key(...)`
    // rather than being a no-op (an unchanged Offset would otherwise not recompose FocusRingOverlay).
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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CameraEvent.NavigateToReview -> onNavigateToReview()
                CameraEvent.ShootReadyHapticTick -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    LaunchedEffect(uiState.captureError) {
        uiState.captureError?.let { snackbarHostState.showSnackbar(it) }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onShutterClick(cameraController.imageCapture)
    }
    val onShutterClick: () -> Unit = {
        val needsLegacyStoragePermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyStoragePermission) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.onShutterClick(cameraController.imageCapture)
        }
    }

    // Volume-down as a hardware shutter button (MainActivity.onKeyDown -> AppContainer.volumeDownEvents).
    LaunchedEffect(viewModel) {
        viewModel.volumeDownEvents.collect { onShutterClick() }
    }

    val postCaptureFadeAlpha by animateFloatAsState(
        targetValue = if (uiState.postCaptureFadeActive) POST_CAPTURE_FADE_ALPHA else 1f,
        animationSpec = tween(300),
        label = "postCaptureFade",
    )
    val hasScene = uiState.composition.scene != SceneClassification.UNKNOWN

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, containerColor = Background) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                // The 4:3 preview: PreviewView plus every overlay that does normalized-to-pixel geometry
                // math live in this exact box, and nothing else does — see the class doc above.
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
                                detectTransformGestures { _, _, zoom, _ ->
                                    val applied = cameraController.applyZoomDelta(zoom) ?: return@detectTransformGestures
                                    zoomRatio = applied
                                    zoomChipVisible = true
                                    zoomChipHideJob?.cancel()
                                    zoomChipHideJob = scope.launch {
                                        delay(ZOOM_CHIP_LINGER_MS)
                                        zoomChipVisible = false
                                    }
                                }
                            },
                    )

                    focusTapOffset?.let { offset -> key(focusTapId) { FocusRingOverlay(tapOffset = offset) } }

                    if (uiState.settings.showThirdsGrid) ThirdsGridOverlay()
                    CompositionOverlay(uiState.composition)
                    if (uiState.settings.debugMode) DebugGeometryOverlay(uiState.composition, uiState.debugFrame)

                    CameraTopBar(
                        showDebugChip = uiState.settings.debugMode,
                        sceneIntent = uiState.settings.sceneIntent,
                        flashMode = uiState.flashMode,
                        hasFlashUnit = uiState.hasFlashUnit,
                        onFlashClick = { viewModel.onFlashModeChanged(cameraController.cycleFlashMode()) },
                        onSettingsClick = onOpenSettings,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    ScoreBadge(
                        score = uiState.composition.displayScore,
                        isShootReady = uiState.composition.isShootReady,
                        hasScene = hasScene,
                        showScore = uiState.settings.showScore,
                        awaitingSubject = uiState.composition.awaitingSubject,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp).alpha(postCaptureFadeAlpha),
                    )

                    EmptySceneHint(
                        hasScene = hasScene,
                        awaitingSubject = uiState.composition.awaitingSubject,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 128.dp).alpha(postCaptureFadeAlpha),
                    )

                    GuidanceBanner(
                        activeRecommendations = uiState.composition.activeRecommendations,
                        guidanceLevel = uiState.settings.guidanceLevel,
                        awaitingSubject = uiState.composition.awaitingSubject,
                        displayScore = uiState.composition.displayScore,
                        isShootReady = uiState.composition.isShootReady,
                        hasScene = hasScene,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 144.dp).alpha(postCaptureFadeAlpha),
                    )

                    if (uiState.settings.debugMode) {
                        DebugOverlay(
                            composition = uiState.composition,
                            debugStats = uiState.debugStats,
                            performanceTier = uiState.performanceTier.name,
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

                // The rest of the screen: plain black, holding the zoom readout and the shutter row.
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (uiState.analysisUnavailable) {
                        Text(
                            text = "Analysis unavailable",
                            color = OnScrimMuted,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                        )
                    }

                    ZoomChip(
                        zoomRatio = zoomRatio,
                        visible = zoomChipVisible,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    )

                    BottomControlBar(
                        isCapturing = uiState.isCapturing,
                        isShootReady = GuidanceFormatter.effectiveShootReady(
                            uiState.composition.isShootReady,
                            uiState.composition.awaitingSubject,
                        ),
                        lastPhotoUri = uiState.lastPhotoUri ?: lastPhotoUri,
                        onShutterClick = onShutterClick,
                        onSwitchLensClick = { viewModel.onSwitchLensRequested() },
                        onOpenGallery = { openGallery(context, uiState.lastPhotoUri ?: lastPhotoUri) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
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

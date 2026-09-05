package com.compositioncoach.app.ui.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.composition.model.SceneClassification
import kotlinx.coroutines.launch

/**
 * The full-screen camera experience: a [PreviewView] with the composition overlays, score badge, guidance
 * banner and controls drawn as Compose layers on top. See [CameraController] for how the preview and the
 * live analysis stream are kept in the same field of view, and [OverlayMapper] for why that lets these
 * overlays place things with a plain normalized-to-pixel multiply.
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
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

    KeepScreenOn()

    DisposableEffect(viewModel) {
        viewModel.onScreenStarted()
        onDispose { viewModel.onScreenStopped() }
    }

    DisposableEffect(lifecycleOwner, uiState.lensFacing, retryTrigger) {
        val job = scope.launch {
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

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, containerColor = Color.Black) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cameraController) {
                        detectTapGestures { offset -> cameraController.tapToFocus(previewView, offset.x, offset.y) }
                    }
                    .pointerInput(cameraController) {
                        detectTransformGestures { _, _, zoom, _ -> cameraController.applyZoomDelta(zoom) }
                    },
            )

            if (uiState.settings.showThirdsGrid) ThirdsGridOverlay()
            CompositionOverlay(uiState.composition)
            if (uiState.settings.debugMode) DebugGeometryOverlay(uiState.composition)

            CameraTopBar(
                showDebugChip = uiState.settings.debugMode,
                onSettingsClick = onOpenSettings,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            ScoreBadge(
                score = uiState.composition.displayScore,
                isShootReady = uiState.composition.isShootReady,
                hasScene = uiState.composition.scene != SceneClassification.UNKNOWN,
                showScore = uiState.settings.showScore,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            )

            GuidanceBanner(
                activeRecommendations = uiState.composition.activeRecommendations,
                guidanceLevel = uiState.settings.guidanceLevel,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 148.dp),
            )

            if (uiState.analysisUnavailable) {
                Text(
                    text = "Analysis unavailable",
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
                )
            }

            if (uiState.settings.debugMode) {
                DebugOverlay(
                    composition = uiState.composition,
                    debugStats = uiState.debugStats,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                )
            }

            BottomControlBar(
                flashMode = uiState.flashMode,
                hasFlashUnit = uiState.hasFlashUnit,
                isCapturing = uiState.isCapturing,
                onFlashClick = { viewModel.onFlashModeChanged(cameraController.cycleFlashMode()) },
                onShutterClick = onShutterClick,
                onSwitchLensClick = { viewModel.onSwitchLensRequested() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

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

/** Sets `FLAG_KEEP_SCREEN_ON` for as long as this composable stays in composition (i.e. the camera screen is showing). */
@Composable
private fun KeepScreenOn() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

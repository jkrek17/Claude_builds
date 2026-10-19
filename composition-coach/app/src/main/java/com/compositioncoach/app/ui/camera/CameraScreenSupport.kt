package com.compositioncoach.app.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.ContextCompat
import com.compositioncoach.app.camera.CameraBindResult
import com.compositioncoach.app.camera.CameraController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ZOOM_CHIP_LINGER_MS = 1_200L

/** Gap between the two ticks of the "armed" double tick. */
private const val DOUBLE_TICK_GAP_MS = 90L

/** Opens [uri] in the platform's photo viewer; a no-op until the first photo has been captured this run. */
internal fun openGallery(context: Context, uri: Uri?) {
    if (uri == null) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

/** Sets `FLAG_KEEP_SCREEN_ON` for as long as this composable stays in composition (i.e. the camera screen is showing). */
@Composable
internal fun KeepScreenOn() {
    // LocalActivity (not a manual `LocalContext.current as? Activity` cast) is the lint-clean way to
    // reach the hosting Activity from Compose — a Context is not always an Activity.
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * The one way the screen takes a photo: a haptic of the caller's choosing (light for a tap, medium for the
 * shoot-at-the-peak fire), then the capture — after asking for legacy storage access first on API <= 28.
 */
@Composable
internal fun rememberCaptureAction(
    viewModel: CameraViewModel,
    cameraController: CameraController,
): (HapticFeedbackType) -> Unit {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onShutterClick(cameraController.imageCapture)
    }
    return remember(viewModel, cameraController, haptic) {
        { tick ->
            haptic.performHapticFeedback(tick)
            val needsLegacyStoragePermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (needsLegacyStoragePermission) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                viewModel.onShutterClick(cameraController.imageCapture)
            }
        }
    }
}

/** Two light ticks in quick succession: "armed". */
@Composable
internal fun rememberDoubleTick(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    return remember(haptic, scope) {
        {
            scope.launch {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                delay(DOUBLE_TICK_GAP_MS)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
}

/**
 * The zoom ratio the screen shows (the pinch readout and the lit preset chip). A pinch writes the ratio
 * CameraX actually applied; a change to `CameraUiState.zoomRatio` (a preset tap, or the camera reporting
 * its own zoom) overwrites it, so the two sources can never disagree for longer than one update.
 */
@Stable
internal class ZoomDisplayState(private val scope: CoroutineScope) {
    var ratio by mutableFloatStateOf(1f)
    var chipVisible by mutableStateOf(false)
        private set
    private var hideJob: Job? = null

    /** A pinch applied [applied]: show the readout, and hide it again [ZOOM_CHIP_LINGER_MS] after the last delta. */
    fun onPinch(applied: Float) {
        ratio = applied
        chipVisible = true
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(ZOOM_CHIP_LINGER_MS)
            chipVisible = false
        }
    }
}

@Composable
internal fun rememberZoomDisplayState(stateRatio: Float): ZoomDisplayState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { ZoomDisplayState(scope) }
    LaunchedEffect(stateRatio) { state.ratio = stateRatio }
    return state
}

/**
 * The EV size of one exposure-compensation step for the camera that just bound (its
 * `ExposureState.exposureCompensationStep`), or 0 when it cannot be read.
 */
internal fun exposureStepEvOf(result: CameraBindResult): Float =
    (result as? CameraBindResult.Success)?.let { success ->
        runCatching { success.camera.cameraInfo.exposureState.exposureCompensationStep.toFloat() }.getOrNull()
    }?.takeIf { it.isFinite() && it > 0f } ?: 0f

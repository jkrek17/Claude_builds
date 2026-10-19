package com.compositioncoach.app.ui.camera

import androidx.camera.core.Camera
import androidx.camera.core.ZoomState
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.applyZoomDelta
import com.compositioncoach.app.camera.setZoomRatio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln

/**
 * Zoom presets and pinch for [CameraViewModel]. CameraX applies `setZoomRatio` instantly, which on a logical
 * multi-camera can read as a jarring cut (especially across the 1x lens switch), so [easeTo] eases to the target
 * over ~200 ms in small steps, Pixel-style, interpolating in log space (equal steps in log(zoom) look like equal
 * steps in magnification). A pinch ([pinch]) cancels an ease still in flight so the two never fight. [follow]
 * reports the bound camera's own `ZoomState` (pinch, preset or lens switch) through [onZoom].
 */
internal class ZoomEaser(
    private val scope: CoroutineScope,
    private val onZoom: (ratio: Float, min: Float, max: Float) -> Unit,
) {
    private var job: Job? = null
    private var live: LiveData<ZoomState>? = null
    private val observer = Observer<ZoomState> { onZoom(it.zoomRatio, it.minZoomRatio, it.maxZoomRatio) }

    /** Follows [camera]'s zoom state from now on (and stops following the previous camera's). */
    fun follow(camera: Camera) {
        live?.removeObserver(observer)
        live = camera.cameraInfo.zoomState.also { it.observeForever(observer) }
    }

    fun release() {
        live?.removeObserver(observer)
        live = null
        job?.cancel()
    }

    fun easeTo(controller: CameraController, ratio: Float, fallbackFrom: Float) {
        if (!(ratio > 0f)) return
        val from = (controller.currentZoomRatio ?: fallbackFrom).takeIf { it > 0f } ?: 1f
        job?.cancel()
        job = scope.launch {
            for (step in 1..STEPS) {
                val t = step.toFloat() / STEPS
                val eased = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
                controller.setZoomRatio(exp(ln(from) + (ln(ratio) - ln(from)) * eased))
                if (step < STEPS) delay(DURATION_MS / STEPS)
            }
        }
    }

    /** Applies a pinch's multiplicative [scale]; returns the ratio applied (null with no camera). */
    fun pinch(controller: CameraController?, scale: Float): Float? {
        job?.cancel()
        return controller?.applyZoomDelta(scale)
    }

    private companion object {
        const val DURATION_MS = 200L
        const val STEPS = 8
    }
}

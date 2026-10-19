package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.NormalizedRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The re-shoot ghost (`docs/WAVE2.md` section 6): the previous photo's subject outline drawn on the preview
 * until the next capture (`withCaptureFinished` clears it) or for [DURATION_MS], whichever is first.
 */
internal class ReshootGhost(private val scope: CoroutineScope, private val state: MutableStateFlow<CameraUiState>) {
    private var job: Job? = null

    /** Shows [rect] as the ghost; null clears it (a tap on the ghost). */
    fun show(rect: NormalizedRect?) {
        job?.cancel()
        state.update { it.withGhost(rect) }
        if (rect == null) return
        job = scope.launch {
            delay(DURATION_MS)
            state.update { if (it.ghostRect == rect) it.withGhost(null) else it }
        }
    }

    companion object {
        /** How long the ghost stays up without a capture. */
        const val DURATION_MS = 20_000L
    }
}

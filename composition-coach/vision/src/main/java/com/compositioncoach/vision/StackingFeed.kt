package com.compositioncoach.vision

import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [VisionPipeline]'s side of [FrameStackingSource]: the [StackingSession] in progress (null when none), its
 * progress for the hold ring, and turning an accepted frame into plane copies for the session. [feed] runs on
 * the pipeline thread with the frame still open; the pipeline skips its detectors while [session] is set.
 */
internal class StackingFeed {
    @Volatile var session: StackingSession? = null
        private set

    private val _progress = MutableStateFlow<Float?>(null)

    /** Convergence estimate of the stack in progress (0..1), null when none. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _status = MutableStateFlow<StackingStatus?>(null)

    /** Frames, convergence and verdict of the stack in progress, null when none. */
    val status: StateFlow<StackingStatus?> = _status.asStateFlow()

    /** Scratch copies of the three YUV planes while stacking; pipeline thread only. */
    private val planeScratch = Array(3) { ByteArray(0) }

    fun start(newSession: StackingSession) {
        session = newSession
        _progress.value = 0f
        _status.value = StackingStatus(0, 0f, false)
    }

    /** Ends the stack (call on the pipeline thread) and returns it for its result, or null when none ran. */
    fun detach(): StackingSession? = session.also {
        session = null
        _progress.value = null
        _status.value = null
    }

    /** Drops any stack in progress (the pipeline stopped). */
    fun clear() {
        session = null
        _progress.value = null
        _status.value = null
    }

    /** Converts the frame and adds it to [session]; runs on the pipeline thread with the frame still open. */
    fun feed(session: StackingSession, imageProxy: ImageProxy, front: Boolean) {
        val crop = imageProxy.cropRect
        val displayMapper = FrameCoordinateMapper(
            imageProxy.width, imageProxy.height, imageProxy.imageInfo.rotationDegrees,
            crop.left, crop.top, crop.right, crop.bottom, mirror = front,
        )
        val planes = imageProxy.planes
        // Plane copies reuse per-plane scratch arrays (pipeline thread only): ~1.8 MB a frame at 1280x960.
        fun copy(index: Int): YuvConverter.Plane {
            val plane = planes[index]
            val buffer = plane.buffer.duplicate().also { it.rewind() }
            val needed = buffer.remaining()
            val bytes = planeScratch[index].takeIf { it.size == needed } ?: ByteArray(needed).also { planeScratch[index] = it }
            buffer.get(bytes, 0, needed)
            return YuvConverter.Plane(bytes, plane.rowStride, plane.pixelStride)
        }
        session.offer(copy(0), copy(1), copy(2), displayMapper, front, imageProxy.imageInfo.timestamp)
        _progress.value = session.convergence
        _status.value = StackingStatus(session.framesStacked, session.convergence, session.converged)
    }
}

package com.compositioncoach.app.camera

import androidx.camera.view.PreviewView
import androidx.core.view.doOnLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Suspends until the view has completed at least one layout pass (returns immediately if it already has). */
internal suspend fun PreviewView.awaitLayout() {
    if (width > 0 && height > 0) return
    suspendCancellableCoroutine { cont ->
        doOnLayout { if (cont.isActive) cont.resume(Unit) }
    }
}

/**
 * Awaits a Guava [com.google.common.util.concurrent.ListenableFuture] without pulling in the
 * kotlinx-coroutines-guava artifact (not part of this project's dependency set): registers a direct
 * listener that resumes the coroutine, cancelling the future if the coroutine itself is cancelled.
 */
internal suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitFuture(): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            { it.run() },
        )
        cont.invokeOnCancellation { cancel(false) }
    }

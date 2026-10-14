package com.compositioncoach.app.di

import android.net.Uri
import com.compositioncoach.composition.model.CompositionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What was captured, waiting to be shown on the review screen. */
data class ReviewEntry(val photoUri: Uri, val result: CompositionResult)

/**
 * Tiny in-memory hand-off between the camera and review screens. Navigation-compose does not carry rich
 * objects between destinations, so the just-captured [ReviewEntry] is parked here instead of round-tripping
 * it through a nav argument. Survives configuration changes (lives on [AppContainer]); does not survive
 * process death, which is fine because a killed process re-launches on the camera screen.
 */
class ReviewStore {
    private val _current = MutableStateFlow<ReviewEntry?>(null)
    val current: StateFlow<ReviewEntry?> = _current

    fun put(entry: ReviewEntry) {
        _current.value = entry
    }

    fun clear() {
        _current.value = null
    }
}

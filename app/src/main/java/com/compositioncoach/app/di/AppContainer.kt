package com.compositioncoach.app.di

import android.content.Context
import android.util.Log
import com.compositioncoach.app.camera.CaptureRepository
import com.compositioncoach.app.camera.ThermalPolicy
import com.compositioncoach.app.settings.SettingsRepository
import com.compositioncoach.composition.engine.CompositionCoach
import com.compositioncoach.vision.FrameAnalysisSource
import com.compositioncoach.vision.VisionPipelineFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Manual dependency container, one instance per process (see [com.compositioncoach.app.CompositionCoachApp]).
 * Everything here is a plain constructor call — no reflection, no generated code — which keeps the ownership
 * of each dependency's lifetime explicit: [frameSource] and [coach] live for the process, the camera binding
 * itself lives and dies with the camera screen (owned by the ViewModel, not here).
 */
class AppContainer(private val appContext: Context) {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val captureRepository: CaptureRepository by lazy { CaptureRepository(appContext) }
    val reviewStore: ReviewStore by lazy { ReviewStore() }

    /** Thermal/battery-saver-driven [com.compositioncoach.vision.PerformanceTier]; see its KDoc. */
    val thermalPolicy: ThermalPolicy by lazy { ThermalPolicy(appContext) }

    /** [CompositionCoach.create] is pure Kotlin/JVM and cheap; never fails. */
    val coach: CompositionCoach by lazy { CompositionCoach.create() }

    /**
     * The vision pipeline the app was handed at build time is a stub whose `create()` throws
     * [NotImplementedError]. That failure is captured here (not rethrown) so the rest of the app can keep
     * showing the camera preview with analysis simply absent — see [frameSourceOrNull].
     */
    private val frameSourceResult: Result<FrameAnalysisSource> by lazy {
        runCatching { VisionPipelineFactory.create(appContext) }
            .onFailure { Log.e(TAG, "Vision pipeline unavailable; running without live composition analysis", it) }
    }

    /** Null when the vision pipeline failed to construct (e.g. the stub in this build). */
    fun frameSourceOrNull(): FrameAnalysisSource? = frameSourceResult.getOrNull()

    /**
     * Volume-down key presses from [com.compositioncoach.app.MainActivity.onKeyDown], forwarded here so
     * the camera screen can trigger a capture without the Activity reaching into the ViewModel/Compose
     * layer directly. `extraBufferCapacity = 1` so a press that lands a frame before the screen's
     * collector is (re)subscribed (e.g. right after a configuration change) is not silently dropped.
     */
    private val _volumeDownEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumeDownEvents: SharedFlow<Unit> = _volumeDownEvents

    /** Called from [com.compositioncoach.app.MainActivity.onKeyDown]. */
    fun onVolumeDownPressed() {
        _volumeDownEvents.tryEmit(Unit)
    }

    companion object {
        private const val TAG = "AppContainer"
    }
}

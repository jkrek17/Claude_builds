package com.compositioncoach.app.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.withTimeoutOrNull

/** Same log tag as the controller, so `adb logcat -s CameraController` still shows what the lens offers. */
private const val EXT_TAG = "CameraController"

/**
 * CameraX Extensions for [CameraController]: the [ExtensionsManager] (loaded once, with a timeout, null when the
 * device has none) and which [CaptureEnhancement]s a lens offers, and which of those would pause live coaching
 * because their session cannot carry `ImageAnalysis`.
 */
internal class CameraExtensionsSupport(private val context: Context) {
    private var extensionsManager: ExtensionsManager? = null
    private var extensionsManagerLoaded = false

    suspend fun managerFor(provider: ProcessCameraProvider): ExtensionsManager? {
        if (extensionsManagerLoaded) return extensionsManager
        extensionsManagerLoaded = true
        extensionsManager = runCatching {
            withTimeoutOrNull(EXTENSIONS_TIMEOUT_MS) { ExtensionsManager.getInstanceAsync(context, provider).awaitFuture() }
        }.onFailure { Log.w(EXT_TAG, "CameraX Extensions unavailable", it) }.getOrNull()
        return extensionsManager
    }

    /** The enhancements to offer for [selector]; see [CaptureQuality.offeredEnhancements] for the AUTO rule. */
    fun availableEnhancements(extensions: ExtensionsManager?, selector: CameraSelector, needsAnalysis: Boolean): Pair<Set<CaptureEnhancement>, Set<CaptureEnhancement>> {
        if (extensions == null) return setOf(CaptureEnhancement.OFF) to emptySet()
        val available = mutableSetOf<CaptureEnhancement>()
        val analysisSupported = mutableSetOf<CaptureEnhancement>()
        for (enhancement in CaptureEnhancement.entries) {
            val mode = enhancement.extensionMode() ?: continue
            if (!runCatching { extensions.isExtensionAvailable(selector, mode) }.getOrDefault(false)) continue
            available += enhancement
            if (runCatching { extensions.isImageAnalysisSupported(selector, mode) }.getOrDefault(false)) analysisSupported += enhancement
        }
        Log.i(EXT_TAG, "Extensions available=$available withAnalysis=$analysisSupported")
        val offered = CaptureQuality.offeredEnhancements(available, analysisSupported, needsAnalysis)
        val pausesCoaching = if (needsAnalysis) offered.filter { it != CaptureEnhancement.OFF && it !in analysisSupported }.toSet() else emptySet()
        return offered to pausesCoaching
    }


    private companion object {
        const val EXTENSIONS_TIMEOUT_MS = 2_000L
    }
}

/** CameraX Extensions mode for an enhancement, or null for OFF. */
internal fun CaptureEnhancement.extensionMode(): Int? = when (this) {
    CaptureEnhancement.OFF -> null
    CaptureEnhancement.AUTO -> ExtensionMode.AUTO
    CaptureEnhancement.HDR -> ExtensionMode.HDR
    CaptureEnhancement.NIGHT -> ExtensionMode.NIGHT
    CaptureEnhancement.BOKEH -> ExtensionMode.BOKEH
}

package com.compositioncoach.app.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.compositioncoach.vision.PerformanceTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Derives a [PerformanceTier] from two live device-health signals and exposes it as a [StateFlow] the
 * ViewModel applies to the vision pipeline (`VisionFeatureToggles.setPerformanceTier`) and, for capture,
 * `CameraController.setPreferFastCapture`:
 *
 *  - **Thermal status** ([PowerManager.addThermalStatusListener], API 29+ only — older devices have no
 *    such API and are simply never throttled by this signal): `THERMAL_STATUS_MODERATE` or worse maps to
 *    [PerformanceTier.REDUCED]; `THERMAL_STATUS_SEVERE` or worse maps to [PerformanceTier.MINIMAL].
 *  - **Battery saver** ([PowerManager.isPowerSaveMode], tracked live via the
 *    `ACTION_POWER_SAVE_MODE_CHANGED` broadcast): on maps to [PerformanceTier.REDUCED]. This is the
 *    *system* battery saver, a separate signal from this app's own `CoachSettings.batterySaver` toggle
 *    (which independently affects the analysis interval and subject-mask toggle — see `:app`'s README) —
 *    the two are complementary, not duplicates: a user can turn on this app's own saver without the
 *    system-wide one being on, and vice versa.
 *
 * The two signals combine by taking whichever tier is *worse* (`maxOf`, comparing [PerformanceTier]'s
 * declared ordinal order FULL < REDUCED < MINIMAL) — a thermally-throttled device on battery saver is
 * still capped at MINIMAL only if either signal alone would already call for MINIMAL, since being on
 * battery saver alone is only ever treated as REDUCED-worthy, matching item 5 of the camera review this
 * class implements.
 *
 * [start]/[stop] are both idempotent, mirroring [com.compositioncoach.vision.VisionPipeline]'s contract,
 * so the ViewModel can call them from the same resume/pause hooks that start/stop the vision pipeline
 * without worrying about double-registration.
 */
class ThermalPolicy(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _tier = MutableStateFlow(PerformanceTier.FULL)
    val tier: StateFlow<PerformanceTier> = _tier

    @Volatile private var thermalStatus: Int = THERMAL_STATUS_NONE
    @Volatile private var batterySaverOn: Boolean = false
    private var started = false

    private val thermalListener: PowerManager.OnThermalStatusChangedListener? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager.OnThermalStatusChangedListener { status ->
                thermalStatus = status
                recompute()
            }
        } else {
            null
        }

    private val batterySaverReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            batterySaverOn = powerManager?.isPowerSaveMode == true
            recompute()
        }
    }

    /** Idempotent; call once the camera screen becomes active (e.g. alongside the vision pipeline's start). */
    fun start() {
        if (started) return
        started = true
        batterySaverOn = powerManager?.isPowerSaveMode == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatus = powerManager?.currentThermalStatus ?: THERMAL_STATUS_NONE
            thermalListener?.let { listener -> runCatching { powerManager?.addThermalStatusListener(listener) } }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                batterySaverReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        recompute()
    }

    /** Idempotent counterpart to [start]. */
    fun stop() {
        if (!started) return
        started = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { listener -> runCatching { powerManager?.removeThermalStatusListener(listener) } }
        }
        runCatching { appContext.unregisterReceiver(batterySaverReceiver) }
    }

    private fun recompute() {
        _tier.value = computeTier(thermalStatus, batterySaverOn, Build.VERSION.SDK_INT)
    }

    companion object {
        // Mirrors PowerManager.THERMAL_STATUS_NONE/MODERATE/SEVERE's int values without requiring API 29
        // to reference the constants themselves at this call site.
        private const val THERMAL_STATUS_NONE = 0
        private const val THERMAL_STATUS_MODERATE = 2
        private const val THERMAL_STATUS_SEVERE = 3

        /**
         * Pure mapping, split out from the stateful listener plumbing above so it's directly
         * unit-testable without a real [PowerManager]. See the class doc for the "worse wins" rationale.
         */
        internal fun computeTier(thermalStatus: Int, batterySaverOn: Boolean, sdkInt: Int): PerformanceTier {
            val tierFromThermal = when {
                sdkInt < Build.VERSION_CODES.Q -> PerformanceTier.FULL
                thermalStatus >= THERMAL_STATUS_SEVERE -> PerformanceTier.MINIMAL
                thermalStatus >= THERMAL_STATUS_MODERATE -> PerformanceTier.REDUCED
                else -> PerformanceTier.FULL
            }
            val tierFromBattery = if (batterySaverOn) PerformanceTier.REDUCED else PerformanceTier.FULL
            return maxOf(tierFromThermal, tierFromBattery)
        }
    }
}

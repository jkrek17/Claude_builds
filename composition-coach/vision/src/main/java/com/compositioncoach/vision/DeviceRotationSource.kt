package com.compositioncoach.vision

import android.content.Context
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener

/**
 * The platform's own device-rotation signal: `android.view.OrientationEventListener`, the same source
 * CameraX's samples and every production camera app use to learn how the phone is physically held while the
 * activity is locked to portrait (so `Display.getRotation()` never changes).
 *
 * Readings are quantized by [PlatformRotationQuantizer] (0/90/180/270 bands, +/-35 degree vote radius,
 * 300 ms debounce, unknown keeps the last value) and converted to the app's `deviceRotationDegrees`
 * convention, `Surface.ROTATION_*` sense. The conversion, with its worked example, is in that class's KDoc:
 * the listener reports degrees **clockwise** from natural portrait, Surface rotation is **counter-clockwise**,
 * so `deviceRotation = (360 - quantizedClockwise) % 360`. Phone turned counter-clockwise, right edge up:
 * listener ~270, Surface sense 90.
 *
 * [currentRotation] can be read from any thread at any time (a volatile read), including synchronously right
 * after [start]: it is the last adopted band, or 0 if the listener hasn't reported a usable angle yet.
 *
 * Callbacks arrive on the main looper (the sensor manager's default handler); the debounce re-check below
 * runs on the main looper too, so the quantizer is only ever mutated from one thread.
 */
class DeviceRotationSource(private val context: Context) {

    private val quantizer = PlatformRotationQuantizer()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var listener: OrientationEventListener? = null

    @Volatile
    private var lastOrientation: Int = PlatformRotationQuantizer.ORIENTATION_UNKNOWN

    @Volatile
    private var enabled = false

    /**
     * Whether the platform listener is enabled and usable. False before [start], after [stop], and when the
     * device reports `canDetectOrientation() == false` (no accelerometer/orientation sensor) — in which case
     * [OrientationSensor] falls back to its gravity-derived rotation.
     */
    val isAvailable: Boolean get() = enabled

    /** The adopted rotation in `Surface.ROTATION_*` sense (0/90/180/270), or 0 if nothing usable yet. */
    val currentRotation: Int get() = quantizer.currentRotation

    private val recheck = Runnable {
        if (enabled) {
            quantizer.update(lastOrientation, SystemClock.elapsedRealtime())
            scheduleRecheckIfPending()
        }
    }

    /**
     * Enables the platform listener (SENSOR_DELAY_NORMAL). Returns [isAvailable]. Safe to call repeatedly,
     * and again after [stop]. Never throws: a failure is logged and reported as "unavailable".
     */
    fun start(): Boolean {
        if (enabled) return true
        return try {
            val l = listener ?: object : OrientationEventListener(context.applicationContext, SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(orientation: Int) = onReading(orientation)
            }.also { listener = it }
            if (!l.canDetectOrientation()) {
                Log.w(TAG, "OrientationEventListener cannot detect orientation on this device; using gravity fallback")
                false
            } else {
                quantizer.onRestart()
                lastOrientation = PlatformRotationQuantizer.ORIENTATION_UNKNOWN
                enabled = true
                l.enable()
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "OrientationEventListener failed to start; using gravity fallback", t)
            enabled = false
            false
        }
    }

    /** Disables the listener. The last adopted rotation is kept (see [currentRotation]). */
    fun stop() {
        enabled = false
        runCatching { mainHandler.removeCallbacks(recheck) }
        runCatching { listener?.disable() }
    }

    private fun onReading(orientation: Int) {
        if (!enabled) return
        lastOrientation = orientation
        quantizer.update(orientation, SystemClock.elapsedRealtime())
        scheduleRecheckIfPending()
    }

    /**
     * The platform only invokes the listener when the angle changes, so a phone turned and then held dead
     * still would never finish its debounce window. Re-feed the last reading once the window has elapsed.
     */
    private fun scheduleRecheckIfPending() {
        mainHandler.removeCallbacks(recheck)
        if (quantizer.hasPendingBand) {
            mainHandler.postDelayed(recheck, PlatformRotationQuantizer.DEBOUNCE_MS + RECHECK_SLACK_MS)
        }
    }

    private companion object {
        const val TAG = "DeviceRotationSource"
        const val RECHECK_SLACK_MS = 20L
    }
}

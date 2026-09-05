package com.compositioncoach.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import com.compositioncoach.composition.model.DeviceOrientation
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Tracks device attitude (tilt/roll relative to gravity) and exposes it as [current] :
 * [DeviceOrientation], in the sign convention documented on that class (`FrameAnalysis.kt` in
 * `:composition`):
 *  - `rollDegrees`: positive = the horizon appears rotated **clockwise** on screen.
 *  - `pitchDegrees`: positive = the camera is pointed **above** the horizon (tilted up).
 *  - `isReliable`: false when pointing within ~25° of straight up/down (roll is undefined there,
 *    since "up" and the camera axis are nearly parallel) or when no orientation sensor exists.
 *
 * ### Sensor strategy
 * Prefers `TYPE_ROTATION_VECTOR` (fused, drift-corrected), falls back to `TYPE_GAME_ROTATION_VECTOR`
 * (fused, no magnetometer — fine since we only need attitude relative to gravity, not heading), and
 * finally to a low-pass-filtered raw `TYPE_ACCELEROMETER` reading if neither rotation-vector sensor
 * is present. All three ultimately produce the same quantity this class actually needs: **the
 * direction of "up" (opposite gravity), expressed in the device's own local axes** — for the rotation
 * vector sensors this is read off the fused rotation matrix; for the accelerometer fallback it *is*
 * the (low-pass filtered) reading directly, since a stationary accelerometer measures exactly the
 * reaction force opposing gravity, i.e. "up" in device coordinates.
 *
 * ### Deriving roll/pitch from the "up in device axes" vector (gx, gy, gz)
 * Android's documented device axis convention (see [SensorEvent]): with the phone held in its
 * natural/default (portrait) orientation, X points right, Y points up (towards the top edge), Z
 * points out of the screen face towards the viewer (and towards the sky when the phone lies flat
 * screen-up on a table — this is literally how Android's docs describe it, and matches the
 * well-known fact that a stationary phone lying flat screen-up reads `az ~= +9.8`).
 *
 * The rear camera looks out along **-Z**. Holding the phone vertically in the normal shooting pose,
 * level, aimed at the horizon: the top edge (Y) points at the sky, so `(gx, gy, gz) ~= (0, g, 0)`.
 *
 * **Pitch.** Tilting the camera to point straight down at the ground swings the device's own +Z axis
 * to point straight up at the sky (since -Z, the camera axis, now points down), giving
 * `(gy, gz) ~= (0, +g)`; pointing straight up at the sky gives `(gy, gz) ~= (0, -g)`. Together with
 * the level case `(g, 0)`, these three fixed points pin down (checked, not guessed):
 * ```
 * pitchDegrees = atan2(-gz, gy)
 * ```
 * (level -> atan2(0,g)=0; pointing up -> atan2(g,0)=+90; pointing down -> atan2(-g,0)=-90.) ✓.
 *
 * **Roll.** Rolling the phone 90° counter-clockwise (as the user looks at their own screen) turns
 * what was the device's +X (right edge) to point where +Y (top edge, i.e. "up") used to point, so
 * the "up-in-device-axes" vector becomes `(gx, gy) ~= (g, 0)`. A 90° CCW physical roll of the camera
 * makes a *fixed* scene appear rotated 90° **clockwise** in the resulting image (rotate the frame of
 * reference CCW and the world appears to sweep CW relative to it — the standard "spinning camera"
 * intuition). Since the contract's positive roll means "horizon appears clockwise on screen", this
 * case must report `rollDegrees = +90`, giving:
 * ```
 * rollDegrees = atan2(gx, gy)
 * ```
 * (level -> atan2(0,g)=0; this CCW-90 physical roll -> atan2(g,0)=+90.) ✓.
 *
 * ### Display-rotation remap
 * The app is locked to portrait, but as a defensive measure this class still reads the current
 * `Display.getRotation()` and remaps the rotation matrix (`SensorManager.remapCoordinateSystem`, or
 * an equivalent manual 2D remap for the raw-accelerometer fallback) using Android's standard
 * rotation table, so `(gx, gy, gz)` above is always expressed relative to *however the display is
 * currently rotated*, not just the device's physical natural orientation.
 *
 * ### Filtering
 * A simple exponential low-pass filter (`alpha` = [LOW_PASS_ALPHA]) is applied to the raw
 * `(gx, gy, gz)` vector (not to the derived angles, which would need messy wraparound handling)
 * before computing roll/pitch, smoothing sensor jitter frame to frame.
 */
class OrientationSensor(private val context: Context) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var activeSensor: Sensor? = null
    private var usingAccelerometerFallback = false

    // Low-pass filtered "up, in device axes" vector.
    private var fx = 0f
    private var fy = 1f
    private var fz = 0f
    private var initialized = false

    @Volatile
    var current: DeviceOrientation = DeviceOrientation(0f, 0f, isReliable = false)
        private set

    /** Registers the best available sensor. Safe to call again after [stop]. */
    fun start() {
        val sm = sensorManager ?: run {
            current = DeviceOrientation(0f, 0f, isReliable = false)
            return
        }
        initialized = false
        val rotationVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gameRotationVector = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val chosen = rotationVector ?: gameRotationVector ?: accelerometer
        if (chosen == null) {
            current = DeviceOrientation(0f, 0f, isReliable = false)
            return
        }
        usingAccelerometerFallback = (chosen === accelerometer)
        activeSensor = chosen
        sm.registerListener(this, chosen, SensorManager.SENSOR_DELAY_GAME)
    }

    /** Unregisters the sensor listener. Call from the camera screen's lifecycle (onStop/onPause). */
    fun stop() {
        sensorManager?.unregisterListener(this)
        activeSensor = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val displayRotation = currentDisplayRotation()
        val (upX, upY, upZ) = if (usingAccelerometerFallback) {
            remapVectorForDisplay(event.values[0], event.values[1], event.values[2], displayRotation)
        } else {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val remapped = FloatArray(9)
            remapMatrixForDisplay(rotationMatrix, remapped, displayRotation)
            // The 3rd row of the device->world rotation matrix equals "up" (gravity direction, the
            // world's Up axis) expressed in the device's own (remapped) axes.
            Triple(remapped[6], remapped[7], remapped[8])
        }

        if (!initialized) {
            fx = upX; fy = upY; fz = upZ
            initialized = true
        } else {
            fx += LOW_PASS_ALPHA * (upX - fx)
            fy += LOW_PASS_ALPHA * (upY - fy)
            fz += LOW_PASS_ALPHA * (upZ - fz)
        }

        val rollRad = atan2(fx.toDouble(), fy.toDouble())
        val pitchRad = atan2(-fz.toDouble(), fy.toDouble())
        val rollDegrees = Math.toDegrees(rollRad).toFloat()
        val pitchDegrees = Math.toDegrees(pitchRad).toFloat()

        // Roll is undefined (numerically unstable) when the device points near straight up/down,
        // i.e. when the "up in device axes" vector has collapsed onto the Z axis (fx, fy both tiny).
        val horizontalMagnitude = hypot(fx.toDouble(), fy.toDouble())
        val nearVertical = Math.abs(pitchDegrees) > RELIABLE_PITCH_LIMIT_DEGREES || horizontalMagnitude < NEAR_VERTICAL_MAGNITUDE
        current = DeviceOrientation(rollDegrees, pitchDegrees, isReliable = !nearVertical)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun currentDisplayRotation(): Int {
        return try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
            }
            when (display?.rotation) {
                Surface.ROTATION_90 -> Surface.ROTATION_90
                Surface.ROTATION_180 -> Surface.ROTATION_180
                Surface.ROTATION_270 -> Surface.ROTATION_270
                else -> Surface.ROTATION_0
            }
        } catch (t: Throwable) {
            Surface.ROTATION_0
        }
    }

    /** Standard Android remap table (see e.g. the "Creating a Compass" sensor guide) for a rotation matrix. */
    private fun remapMatrixForDisplay(input: FloatArray, output: FloatArray, displayRotation: Int) {
        when (displayRotation) {
            Surface.ROTATION_90 ->
                SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, output)
            Surface.ROTATION_180 ->
                SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, output)
            Surface.ROTATION_270 ->
                SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, output)
            else ->
                SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_X, SensorManager.AXIS_Y, output)
        }
    }

    /** Equivalent remap applied directly to a raw (x, y, z) vector, for the accelerometer fallback path. */
    private fun remapVectorForDisplay(x: Float, y: Float, z: Float, displayRotation: Int): Triple<Float, Float, Float> =
        when (displayRotation) {
            Surface.ROTATION_90 -> Triple(y, -x, z)
            Surface.ROTATION_180 -> Triple(-x, -y, z)
            Surface.ROTATION_270 -> Triple(-y, x, z)
            else -> Triple(x, y, z)
        }

    companion object {
        private const val LOW_PASS_ALPHA = 0.2f
        private const val RELIABLE_PITCH_LIMIT_DEGREES = 65.0 // within 25 deg of +/-90
        private const val NEAR_VERTICAL_MAGNITUDE = 0.42 // ~= sin(25 deg), horizontal component of "up"
    }
}

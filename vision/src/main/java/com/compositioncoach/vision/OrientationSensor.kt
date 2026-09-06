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
 * The signs live in the pure functions at the bottom of this file — [gravityToRollDegrees],
 * [gravityToPitchDegrees] and [gravityReadingIsReliable] — where each is derived from Android's device-axis
 * convention and checked against concrete holds (portrait, right-edge-up, upside-down, left-edge-up, plus
 * camera-straight-up/straight-down for pitch). They are top-level and side-effect-free precisely so a test
 * can drive the whole rotation chain from a gravity vector rather than from an already-signed roll:
 * ```
 * rollDegrees  = atan2(gx, gy)     // = the phone's physical rotation from natural portrait, and equally
 *                                  //   how far clockwise the world appears rotated in the captured frame
 * pitchDegrees = atan2(-gz, gy)    // positive = camera pointed above the horizon
 * ```
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
 *
 * ### Physical device rotation (the "Pixel style" fix)
 * The app is portrait-locked, so [Display.getRotation] never itself changes — but the raw `rollDegrees`
 * computed above already *is* the phone's true physical rotation away from natural portrait (the
 * `currentDisplayRotation()` remap above is a defensive no-op in practice, since it always reads back
 * `Surface.ROTATION_0` for a locked activity). [DeviceRotationQuantizer] buckets that continuous roll
 * into a stable [Surface.ROTATION_0]/`90`/`180`/`270`-degree band ([deviceRotationDegrees]), which
 * [VisionPipeline] uses (via [UprightRotation]) to analyze every frame in true physical-up coordinates
 * instead of the fixed portrait-display orientation. [current]'s own `rollDegrees` is then re-referenced
 * onto that same quantized rotation (see [referenceRollToDeviceRotation]) so a level hold reads ~0
 * degrees in *every* physical orientation, not just natural portrait — e.g. a phone held level in
 * landscape (raw roll ~90) reports `rollDegrees` ~0 once [deviceRotationDegrees] has settled on 90.
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

    private val rotationQuantizer = DeviceRotationQuantizer()

    @Volatile
    var current: DeviceOrientation = DeviceOrientation(0f, 0f, isReliable = false)
        private set

    /**
     * The phone's quantized physical rotation from natural portrait (0/90/180/270), per
     * [DeviceRotationQuantizer]. Mirrors [DeviceOrientation.deviceRotationDegrees] on [current]; exposed
     * separately so [VisionPipeline] can read it without unwrapping a nullable [DeviceOrientation].
     */
    val deviceRotationDegrees: Int get() = rotationQuantizer.currentRotation

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

        // All three quantities come from the same pure functions the rotation truth-table test drives
        // directly from a gravity vector (see the bottom of this file) — this class only supplies the
        // vector and the filtering, so the *signs* live in exactly one, independently-testable place.
        val rollDegrees = gravityToRollDegrees(fx, fy)
        val pitchDegrees = gravityToPitchDegrees(fy, fz)
        // The rotation-vector path yields a unit "up"; the raw accelerometer yields ~9.81 m/s^2.
        val magnitudeScale = if (usingAccelerometerFallback) EARTH_GRAVITY else 1f
        val isReliable = gravityReadingIsReliable(fx, fy, fz, magnitudeScale)
        // SensorEvent.timestamp is elapsed-realtime nanoseconds (monotonic, consistent across sensor
        // types), so it doubles as the quantizer's debounce clock without an extra syscall per event.
        val quantizedRotation = rotationQuantizer.update(rollDegrees, isReliable, event.timestamp / 1_000_000L)
        val physicalRoll = referenceRollToDeviceRotation(rollDegrees, quantizedRotation)
        current = DeviceOrientation(physicalRoll, pitchDegrees, isReliable = isReliable, deviceRotationDegrees = quantizedRotation)
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
        private const val EARTH_GRAVITY = 9.81f
    }
}

// --- Pure gravity math ------------------------------------------------------------------------------
// Extracted from [OrientationSensor] so the whole rotation chain can be driven from a plain gravity
// vector in a unit test (see :app's RotationTruthTableTest and DeviceRotationQuantizerTest) instead of
// from an intermediate "roll" whose sign would otherwise have to be taken on trust.
//
// The input in every case is the direction of **up** expressed in the device's own axes: `(gx, gy, gz)`.
// A stationary accelerometer measures exactly that (the reaction force opposing gravity), and the third
// row of `TYPE_ROTATION_VECTOR`'s device->world rotation matrix is the same vector. Android's device
// axes (see `SensorEvent`): +x out the screen's RIGHT edge, +y out its TOP edge, +z out of the screen
// face. So, with g = 9.8:
//
// ```
// hold                     up in device axes     rollDegrees   quantized band
// portrait (natural)       ( 0,  g,  0)                    0   0    = Surface.ROTATION_0
// right edge up            ( g,  0,  0)                  +90   90   = Surface.ROTATION_90
// upside down              ( 0, -g,  0)                  180   180  = Surface.ROTATION_180
// left edge up             (-g,  0,  0)                  -90   270  = Surface.ROTATION_270
// ```

/**
 * Roll from the gravity/"up in device axes" vector: `atan2(gx, gy)`, in degrees, in `(-180, 180]`.
 *
 * Two independent facts pin this down (neither is a guess, and neither depends on any other file's
 * comments):
 *  1. **It is the phone's physical rotation from natural portrait, in `Surface.ROTATION_*`'s own sense.**
 *     `Surface.ROTATION_90` means the phone has been turned 90 degrees counter-clockwise from natural, so
 *     its RIGHT edge points up; up in device axes is then `(+g, 0, 0)` and `atan2(g, 0) = +90`. Likewise
 *     left-edge-up (`ROTATION_270`) gives `atan2(-g, 0) = -90`, which is the same band as 270. That is why
 *     [DeviceRotationQuantizer] can bucket this value straight into `Surface.ROTATION_*` degrees with no
 *     further sign work.
 *  2. **It is also "how far clockwise the world appears to have rotated in the frame the camera captures",**
 *     which is what `DeviceOrientation.rollDegrees` promises. Rotating the camera counter-clockwise by an
 *     angle sweeps a fixed scene clockwise by that angle within the frame — so right-edge-up, a
 *     counter-clockwise turn of 90 degrees, makes the horizon appear rotated +90 degrees clockwise on the
 *     (never-rotating, portrait-locked) screen. Same number, same sign. ✓
 */
fun gravityToRollDegrees(gx: Float, gy: Float): Float =
    Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()

/**
 * Pitch from the gravity/"up in device axes" vector: `atan2(-gz, gy)`, in degrees. Positive = the camera
 * is pointed **above** the horizon.
 *
 * Three fixed points: level and vertical (the normal shooting pose) puts the top edge at the sky, so
 * `(gy, gz) = (g, 0)` and the result is 0; pointing the rear camera (which looks along -z) straight *up*
 * at the sky swings device +z downward, `(0, -g)` -> `atan2(g, 0) = +90`; pointing it straight *down* at
 * the ground gives `(0, +g)` -> `atan2(-g, 0) = -90`.
 */
fun gravityToPitchDegrees(gy: Float, gz: Float): Float =
    Math.toDegrees(atan2(-gz.toDouble(), gy.toDouble())).toFloat()

/**
 * Whether a gravity vector yields a usable roll. Roll is `atan2(gx, gy)`, which is numerically undefined
 * when the phone points near straight up or straight down — "up" has collapsed onto the device's z axis
 * and both gx and gy are tiny. [magnitudeScale] is the expected length of `(gx, gy, gz)`: 1 for the unit
 * vector read off a rotation matrix, ~9.81 for a raw accelerometer reading.
 */
fun gravityReadingIsReliable(gx: Float, gy: Float, gz: Float, magnitudeScale: Float = 1f): Boolean {
    val pitch = gravityToPitchDegrees(gy, gz)
    val horizontal = hypot(gx.toDouble(), gy.toDouble())
    return Math.abs(pitch) <= RELIABLE_PITCH_LIMIT_DEGREES &&
        horizontal >= NEAR_VERTICAL_MAGNITUDE * magnitudeScale
}

/** Within ~25 degrees of straight up/down, roll stops being meaningful. */
private const val RELIABLE_PITCH_LIMIT_DEGREES = 65.0

/** ~= sin(25 deg): the horizontal component of a unit "up" vector at the reliability limit. */
private const val NEAR_VERTICAL_MAGNITUDE = 0.42

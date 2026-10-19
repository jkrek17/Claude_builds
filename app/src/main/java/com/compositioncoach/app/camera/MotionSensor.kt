package com.compositioncoach.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.compositioncoach.composition.model.DeviceOrientation
import com.compositioncoach.vision.DeviceRotationQuantizer
import com.compositioncoach.vision.gravityReadingIsReliable
import com.compositioncoach.vision.gravityToPitchDegrees
import com.compositioncoach.vision.gravityToRollDegrees
import com.compositioncoach.vision.referenceRollToDeviceRotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gyroscope-driven [Steadiness] for steady-shot gating and the "Hold still" hint.
 *
 * ## Why a separate sensor from `:vision`'s `OrientationSensor`
 * `OrientationSensor` answers "which way is up" from a heavily low-passed rotation vector; that filtering is
 * exactly what makes it useless for shake, which lives at 4-12 Hz. Shake is a *rate*, so this listens to the
 * raw `TYPE_GYROSCOPE` (angular velocity, rad/s) at ~100 Hz on its own background thread, and classifies it
 * with [SteadinessClassifier] (thresholds, window and hysteresis are documented there). On a device with no
 * raw gyroscope it falls back to differencing successive `TYPE_GAME_ROTATION_VECTOR` quaternions; with
 * neither, [isAvailable] is false and [steadiness] stays [Steadiness.STEADY] so the shutter is never delayed.
 *
 * ## A second, coarse orientation source
 * It also listens to gravity (`TYPE_GRAVITY`, falling back to a low-passed `TYPE_ACCELEROMETER`) and runs it
 * through `:vision`'s own pure sign functions and `DeviceRotationQuantizer` (reused, not re-derived, so the
 * rotation conventions live in one place), publishing [deviceRotationDegrees] and [orientation]. The app's
 * primary rotation comes from `FrameAnalysis`, which stops arriving when an extension session cannot carry
 * ImageAnalysis (or when the vision pipeline failed to start). Without this fallback, the capture EXIF
 * orientation would freeze at the last analysed hold, and auto-level would have no roll to work from.
 *
 * ## Lifecycle
 * [start]/[stop] are idempotent and are called from the same resume/pause hooks that start/stop the vision
 * pipeline (`CameraViewModel.resumePipeline`/`pausePipeline`), so the gyro is never left running while the app
 * is in the background.
 */
class MotionSensor(context: Context) : SensorEventListener {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val classifier = SteadinessClassifier()

    private val _steadiness = MutableStateFlow(Steadiness.STEADY)
    val steadiness: StateFlow<Steadiness> = _steadiness.asStateFlow()

    /** RMS angular speed over the classifier window, rad/s, for logging/debug. */
    @Volatile var lastRmsRadPerSecond: Float = 0f
        private set

    @Volatile var isAvailable: Boolean = false
        private set

    private val rotationQuantizer = DeviceRotationQuantizer()
    private val _deviceRotationDegrees = MutableStateFlow(0)

    /** Quantized physical rotation (0/90/180/270) from gravity; see the class doc for when it is used. */
    val deviceRotationDegrees: StateFlow<Int> = _deviceRotationDegrees.asStateFlow()

    /** Latest gravity-derived orientation in `DeviceOrientation`'s conventions, or null before the first reading. */
    @Volatile var orientation: DeviceOrientation? = null
        private set

    private var gravitySensor: Sensor? = null
    private var gravityIsAccelerometer = false
    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    private var thread: HandlerThread? = null
    private var usingRotationVector = false
    private var lastQuaternion: FloatArray? = null
    private var lastQuaternionTimestamp = 0L
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val sm = sensorManager ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val rotationVector = if (gyro == null) sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) else null
        val sensor = gyro ?: rotationVector
        usingRotationVector = gyro == null
        gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gravityIsAccelerometer = gravitySensor?.type == Sensor.TYPE_ACCELEROMETER
        gravityInitialized = false
        orientation = null
        classifier.reset()
        lastQuaternion = null
        _steadiness.value = Steadiness.STEADY
        if (sensor == null && gravitySensor == null) {
            Log.i(TAG, "No motion sensors; steady-shot gating and the orientation fallback are disabled")
            isAvailable = false
            return
        }
        val handlerThread = HandlerThread("MotionSensor").also { it.start() }
        thread = handlerThread
        val handler = Handler(handlerThread.looper)
        isAvailable = sensor != null &&
            runCatching { sm.registerListener(this, sensor, SAMPLING_PERIOD_US, handler) }.getOrDefault(false)
        if (sensor == null) Log.i(TAG, "No gyroscope or game rotation vector; steady-shot gating disabled")
        gravitySensor?.let { g -> runCatching { sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME, handler) } }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { sensorManager?.unregisterListener(this) }
        thread?.quitSafely()
        thread = null
        isAvailable = false
        _steadiness.value = Steadiness.STEADY
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor?.type
        if (type == Sensor.TYPE_GRAVITY || type == Sensor.TYPE_ACCELEROMETER) {
            onGravity(event)
            return
        }
        val speed = if (usingRotationVector) {
            val q = FloatArray(4)
            SensorManager.getQuaternionFromVector(q, event.values)
            val previous = lastQuaternion
            val previousTs = lastQuaternionTimestamp
            lastQuaternion = q
            lastQuaternionTimestamp = event.timestamp
            if (previous == null) return
            SteadinessClassifier.angularSpeedFromQuaternions(previous, q, (event.timestamp - previousTs) / 1e9f)
        } else {
            SteadinessClassifier.angularSpeed(event.values[0], event.values[1], event.values[2])
        }
        val next = classifier.update(event.timestamp, speed)
        lastRmsRadPerSecond = classifier.windowRms
        if (_steadiness.value != next) _steadiness.value = next
    }

    private fun onGravity(event: SensorEvent) {
        val alpha = if (gravityIsAccelerometer) ACCELEROMETER_LOW_PASS else 1f
        if (!gravityInitialized) {
            for (i in 0..2) gravity[i] = event.values[i]
            gravityInitialized = true
        } else {
            for (i in 0..2) gravity[i] += alpha * (event.values[i] - gravity[i])
        }
        val (gx, gy, gz) = Triple(gravity[0], gravity[1], gravity[2])
        val roll = gravityToRollDegrees(gx, gy)
        val reliable = gravityReadingIsReliable(gx, gy, gz, magnitudeScale = EARTH_GRAVITY)
        val rotation = rotationQuantizer.update(roll, reliable, event.timestamp / 1_000_000L)
        orientation = DeviceOrientation(
            rollDegrees = referenceRollToDeviceRotation(roll, rotation),
            pitchDegrees = gravityToPitchDegrees(gy, gz),
            isReliable = reliable,
            deviceRotationDegrees = rotation,
        )
        if (_deviceRotationDegrees.value != rotation) _deviceRotationDegrees.value = rotation
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "MotionSensor"
        /** 100 Hz: comfortably above hand tremor (8-12 Hz) and below the 200 Hz high-rate-sensor permission limit. */
        private const val SAMPLING_PERIOD_US = 10_000
        private const val ACCELEROMETER_LOW_PASS = 0.2f
        private const val EARTH_GRAVITY = 9.81f
    }
}

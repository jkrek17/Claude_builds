package com.compositioncoach.app.camera

import android.content.Context
import android.media.MediaActionSound
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Whether this phone must make a shutter sound (a legal or carrier requirement in some regions, notably Japan
 * and South Korea), so the app can stay silent everywhere else, as `PRODUCT.md` asks.
 *
 * ## Signals, strongest first
 * 1. API 33+: [MediaActionSound.mustPlayShutterSound], the platform's own answer (it reads the same
 *    `ro.camera.sound.forced` / audio-policy configuration the stock camera obeys). Authoritative when present.
 * 2. Below 33, the legacy per-camera flag `Camera.CameraInfo.canDisableShutterSound` (false on devices that
 *    enforce the sound). There is no Camera2 `CameraCharacteristics` key for this; the legacy query only reads
 *    static info and does not open the camera.
 * 3. The `ro.camera.sound.forced` system property, read with `getprop` (no hidden-API reflection).
 * 4. Region hint: the network or SIM country is JP or KR.
 *
 * ## Limits
 * The answer is cached per process. Without a SIM or network the region hint is unavailable, and a phone
 * imported into Japan may report no requirement at all; signals 1-3 follow the device's build, which is what
 * the law actually binds. Playing the sound is best effort ([ShutterSound]); the system volume and silent mode
 * may still affect it on devices whose policy does not force it.
 */
object ShutterSoundPolicy {
    private const val TAG = "ShutterSoundPolicy"
    private val REGIONS_REQUIRING_SOUND = setOf("jp", "kr")

    @Volatile private var cached: Boolean? = null

    fun isMandatory(context: Context): Boolean = cached ?: compute(context.applicationContext).also { cached = it }

    private fun compute(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { return MediaActionSound.mustPlayShutterSound() }
        }
        val legacy = runCatching { legacyCannotDisable() }.getOrDefault(false)
        val property = runCatching { readSystemProperty("ro.camera.sound.forced") }.getOrNull()
        val region = runCatching { regionRequiresSound(context) }.getOrDefault(false)
        val mandatory = legacy || property == "1" || property.equals("true", ignoreCase = true) || region
        Log.i(TAG, "Shutter sound mandatory=$mandatory (legacy=$legacy, property=$property, region=$region)")
        return mandatory
    }

    @Suppress("DEPRECATION")
    private fun legacyCannotDisable(): Boolean {
        val count = android.hardware.Camera.getNumberOfCameras()
        val info = android.hardware.Camera.CameraInfo()
        return (0 until count).any { id ->
            android.hardware.Camera.getCameraInfo(id, info)
            !info.canDisableShutterSound
        }
    }

    private fun readSystemProperty(name: String): String? {
        val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        return try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) return null
            process.inputStream.bufferedReader().readText().trim().ifEmpty { null }
        } finally {
            process.destroy()
        }
    }

    private fun regionRequiresSound(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        val countries = listOf(tm.networkCountryIso, tm.simCountryIso).map { it.orEmpty().lowercase(Locale.US) }
        return countries.any { it in REGIONS_REQUIRING_SOUND }
    }
}

/**
 * Plays the shutter click when [ShutterSoundPolicy] says it is mandatory; silent otherwise. Loads the sound
 * lazily (MediaActionSound decodes it on a background thread) and never throws.
 */
class ShutterSound(private val context: Context) {
    private val sound: MediaActionSound? by lazy {
        runCatching { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }.getOrNull()
    }

    fun playIfMandatory() {
        if (!ShutterSoundPolicy.isMandatory(context)) return
        runCatching { sound?.play(MediaActionSound.SHUTTER_CLICK) }
    }
}

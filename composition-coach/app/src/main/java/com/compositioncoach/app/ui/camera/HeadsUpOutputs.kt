package com.compositioncoach.app.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** How often the planner looks at the live cue. Fine enough for a 2.5 s repeat, cheap enough to ignore. */
private const val TICK_MS = 250L

/**
 * Plays [HapticPatterns] on the phone's vibrator. Uses `VibratorManager` (API 31+) or `Vibrator` waveforms when
 * the app holds `VIBRATE`; otherwise it taps the pattern out with [View.performHapticFeedback], which needs no
 * permission and already obeys the system's touch-feedback switch. Either way it stays silent while
 * `Settings.System.HAPTIC_FEEDBACK_ENABLED` is off.
 */
@SuppressLint("MissingPermission") // Vibrator calls only run when checkSelfPermission(VIBRATE) passed, see [vibrator].
class HapticGuide(private val context: Context, private val view: View) {
    private val scope: CoroutineScope = MainScope()

    private val vibrator: Vibrator? by lazy {
        if (context.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) return@lazy null
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        v?.takeIf { it.hasVibrator() }
    }

    private fun systemHapticsOn(): Boolean =
        runCatching { Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 }
            .getOrDefault(true)

    fun play(pattern: LongArray, strong: Boolean = false) {
        if (!systemHapticsOn()) return
        val v = vibrator
        if (v != null) {
            val amplitudes = IntArray(pattern.size) { i -> if (i % 2 == 1) (if (strong) 255 else 160) else 0 }
            runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1)) }
            return
        }
        scope.launch {
            pattern.forEachIndexed { i, ms ->
                if (i % 2 == 0) {
                    delay(ms)
                } else {
                    val constant = if (strong || ms >= HapticPatterns.LONG_MS) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
                    view.performHapticFeedback(constant)
                    delay(ms)
                }
            }
        }
    }

    fun release() {
        scope.cancel()
        vibrator?.cancel()
    }
}

/**
 * Speaks the chip through the on-device `TextToSpeech` engine (`QUEUE_FLUSH`, rate 1.1), choosing an embedded
 * voice for the current language when the engine offers one. Silent while media is playing
 * (`AudioManager.isMusicActive`) or Do Not Disturb filters interruptions.
 */
class VoiceGuide(private val context: Context) {
    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) configure()
    }

    private fun configure() {
        tts.setSpeechRate(SPEECH_RATE)
        val language = Locale.getDefault().language
        runCatching {
            tts.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == language }?.let { tts.voice = it }
        }
    }

    private fun shouldStayQuiet(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val dnd = notifications?.currentInterruptionFilter?.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } ?: false
        return audio?.isMusicActive == true || dnd
    }

    fun speak(text: String) {
        if (!ready || shouldStayQuiet()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun release() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }

    private companion object {
        const val SPEECH_RATE = 1.1f
        const val UTTERANCE_ID = "coach-cue"
    }
}

/**
 * Runs heads-up guidance while either switch is on: a [HeadsUpPlanner] looks at [cue] every [TICK_MS] and its
 * actions go to [HapticGuide] / [VoiceGuide], created only when their switch is on and released when it goes off
 * or the camera screen leaves composition.
 */
@Composable
fun HeadsUpGuidanceEffect(cue: HeadsUpCue, vibration: Boolean, voice: Boolean) {
    if (!vibration && !voice) return
    val context = LocalContext.current
    val view = LocalView.current
    val currentCue by rememberUpdatedState(cue)
    val haptics = remember(vibration) { if (vibration) HapticGuide(context, view) else null }
    val speech = remember(voice) { if (voice) VoiceGuide(context) else null }
    DisposableEffect(haptics, speech) {
        onDispose {
            haptics?.release()
            speech?.release()
        }
    }
    LaunchedEffect(vibration, voice) {
        val planner = HeadsUpPlanner()
        while (true) {
            planner.onTick(SystemClock.uptimeMillis(), currentCue, vibration, voice).forEach { action ->
                when (action) {
                    is HeadsUpAction.Vibrate -> haptics?.play(action.pattern)
                    HeadsUpAction.StrongPulse -> haptics?.play(HapticPatterns.SHOOT_READY, strong = true)
                    is HeadsUpAction.Speak -> speech?.speak(action.text)
                }
            }
            delay(TICK_MS)
        }
    }
}

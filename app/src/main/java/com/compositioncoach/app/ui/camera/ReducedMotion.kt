package com.compositioncoach.app.ui.camera

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether looping and transition motion should run at all. False when the system animator duration scale
 * is 0 — the platform's "remove animations" setting, which `docs/APP_UX.md` requires us to respect: no
 * chevron breathe, no bracket ease, no arc animation, instant crossfades.
 *
 * Read once, at the top of the camera screen, from `Settings.Global.ANIMATOR_DURATION_SCALE` (a
 * `remember` keyed on the context, not a live observer): the setting changes at most once in a session,
 * and polling it per frame on the camera screen would cost more than it is worth. The answer is then
 * passed down as an ordinary parameter rather than through a composition local, so every preview and test
 * can pin it to `false` and render the layer's resting state.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        scale != 0f
    }
}

/** [durationMs] when motion is allowed, 0 (an instant cut) when it is not. */
fun motionDuration(durationMs: Int, animationsEnabled: Boolean): Int = if (animationsEnabled) durationMs else 0

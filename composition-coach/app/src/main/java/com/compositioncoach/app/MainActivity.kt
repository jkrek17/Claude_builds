package com.compositioncoach.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.compositioncoach.app.crash.CrashLog
import com.compositioncoach.app.ui.crash.CrashReportScreen
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.ui.navigation.AppNavGraph
import com.compositioncoach.app.ui.theme.CompositionCoachTheme

/**
 * Single-activity host. All navigation lives in [AppNavGraph]; screen-specific behavior (keeping the
 * screen on while the camera is showing, camera binding, etc.) lives with the screens themselves.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as CompositionCoachApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // If the previous run crashed, show the report first: it must be readable even when the camera
        // screen itself is what crashes, so it is decided here, before the navigation graph is composed.
        val crashReport = CrashLog.consume(this)
        setContent {
            CompositionCoachTheme {
                var showCrashReport by remember { mutableStateOf(crashReport != null) }
                if (showCrashReport && crashReport != null) {
                    CrashReportScreen(report = crashReport, onContinue = { showCrashReport = false })
                } else {
                    AppNavGraph(container = container)
                }
            }
        }
    }

    /**
     * Volume-down acts as a hardware shutter button, like a dedicated camera. Forwarded through
     * [AppContainer.volumeDownEvents] (a [kotlinx.coroutines.flow.SharedFlow]) rather than reaching into
     * the camera screen's Compose tree directly, so this Activity stays free of any Compose/ViewModel
     * knowledge. Consumed here (not passed to `super`) so the system volume UI doesn't also pop up while
     * framing a shot; every other key is left untouched.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            container.onVolumeDownPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

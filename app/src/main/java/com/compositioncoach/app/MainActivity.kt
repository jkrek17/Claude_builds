package com.compositioncoach.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            CompositionCoachTheme {
                AppNavGraph(container = container)
            }
        }
    }
}

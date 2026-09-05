package com.compositioncoach.app

import android.app.Application
import com.compositioncoach.app.di.AppContainer

/** Application entry point. Owns the single [AppContainer] used for manual DI across the app. */
class CompositionCoachApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

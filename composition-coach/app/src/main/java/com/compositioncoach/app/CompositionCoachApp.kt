package com.compositioncoach.app

import android.app.Application
import com.compositioncoach.app.crash.CrashLog
import com.compositioncoach.app.di.AppContainer

/** Application entry point. Owns the single [AppContainer] used for manual DI across the app. */
open class CompositionCoachApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        container = createContainer()
    }

    /** The app's dependency container; a test application overrides this to swap in fakes. */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    /**
     * Records every uncaught exception (logcat + a file shown on the next launch by
     * [com.compositioncoach.app.ui.crash.CrashReportScreen]) and then delegates to the previously installed
     * handler, so the process still terminates exactly as it would without this. Installed before anything
     * else so a failure inside [AppContainer] construction is captured too.
     */
    private fun installCrashLogger() {
        CrashLog.install(this, BuildConfig.VERSION_NAME)
    }

}

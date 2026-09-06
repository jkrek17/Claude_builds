package com.compositioncoach.app

import android.app.Application
import android.util.Log
import com.compositioncoach.app.di.AppContainer

/** Application entry point. Owns the single [AppContainer] used for manual DI across the app. */
class CompositionCoachApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        container = AppContainer(this)
    }

    /**
     * Logs every uncaught exception before delegating to whatever handler was previously installed
     * (the platform's default one, unless something else runs before this). This is deliberately not a
     * silent swallow — the process still terminates exactly as it would without this handler — it only
     * guarantees the crash is visible in logcat with the full stack trace under a stable, greppable tag
     * before that happens, which otherwise depends on which component crashed and how far it unwound.
     */
    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "CompositionCoachApp"
    }
}

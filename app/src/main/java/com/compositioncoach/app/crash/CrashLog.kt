package com.compositioncoach.app.crash

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the last uncaught exception so it can be shown (and copied) on the next launch.
 *
 * Sideloaded testers rarely have adb; without this, a crash on a tester's phone is invisible. The file lives
 * in the app's private storage, is overwritten by the next crash and deleted once shown. Native crashes
 * (SIGSEGV in a TFLite library) do not pass through the Java handler and are not captured here.
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val FILE_NAME = "last_crash.txt"

    /** Installs a handler that writes the trace and then delegates to the previously installed handler. */
    fun install(context: Context, versionName: String) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            runCatching { write(appContext, thread, throwable, versionName) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the last crash report and deletes it, or null when the previous run ended normally. */
    fun consume(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable, versionName: String) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("Composition Coach $versionName")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        File(context.filesDir, FILE_NAME).writeText(report)
    }
}

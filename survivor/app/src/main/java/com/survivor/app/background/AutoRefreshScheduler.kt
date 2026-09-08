package com.survivor.app.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Enqueues and cancels the periodic [AutoRefreshWorker] job, and offers a one-time "Run now". */
object AutoRefreshScheduler {
    private const val PERIODIC_WORK_NAME = "survivor_auto_refresh"
    private const val ONE_TIME_WORK_NAME = "survivor_auto_refresh_now"

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Enqueues (or reschedules, via [ExistingPeriodicWorkPolicy.UPDATE]) the periodic refresh at
     *  [AutoRefreshPrefs.intervalHours]. Call after `onCreate` and again whenever the enabled toggle or
     *  interval changes; does nothing when auto-refresh is turned off (use [cancel] for that). */
    fun schedule(context: Context, prefs: AutoRefreshPrefs) {
        if (!prefs.enabled) {
            cancel(context)
            return
        }
        // WorkManager's default (exponential) backoff policy applies on Result.retry(); no need to set one.
        val request = PeriodicWorkRequestBuilder<AutoRefreshWorker>(prefs.intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    /** One-off immediate run, used by the Settings "Run now" button. Independent of whether the periodic
     *  job is enabled. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoRefreshWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

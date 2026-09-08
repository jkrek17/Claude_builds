package com.survivor.app.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.survivor.app.MainActivity
import com.survivor.app.R
import com.survivor.app.SurvivorApp
import com.survivor.app.data.RefreshStatus
import com.survivor.app.data.SurvivorRepository
import java.util.Calendar
import java.util.TimeZone

private const val TAG = "AutoRefreshWorker"
const val NOTIFICATION_CHANNEL_ID = "survivor_updates"
private const val FULL_REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

/**
 * WorkManager job that periodically refreshes odds/scores (and, once a week, the slower FPI/schedule data),
 * then decides what to notify the user about via the pure [computeNotifications]. Never throws: any failure
 * is logged and turned into [Result.retry] so WorkManager's default backoff handles it.
 */
class AutoRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = AutoRefreshPrefs(applicationContext)
        if (!prefs.enabled) return Result.success()
        val repo = (applicationContext as? SurvivorApp)?.repository ?: return Result.success()

        return try {
            runRefresh(repo, prefs)
        } catch (e: Exception) {
            Log.w(TAG, "Background refresh failed, will retry", e)
            Result.retry()
        }
    }

    private suspend fun runRefresh(repo: SurvivorRepository, prefs: AutoRefreshPrefs): Result {
        val now = System.currentTimeMillis()
        val before = runCatching { repo.evaluate() }.getOrNull()

        val runFullRefresh = now - prefs.lastFullRefreshEpochMs >= FULL_REFRESH_INTERVAL_MS
        if (runFullRefresh) repo.refreshNflData(includeProjections = true) else repo.refreshOdds()

        val refreshStatus = repo.refresh.value
        if (refreshStatus is RefreshStatus.Failed) {
            Log.w(TAG, "Background refresh reported failure: ${refreshStatus.message}")
            return Result.retry()
        }
        if (runFullRefresh) prefs.lastFullRefreshEpochMs = now

        val after = repo.evaluate()
        val outcome = if (after == null) "No season data yet" else (refreshStatus as? RefreshStatus.Done)?.message ?: "Refreshed"
        prefs.recordRun(now, outcome)

        if (after != null) {
            val notices = computeNotifications(
                before = before,
                after = after,
                prefs = prefs.snapshot(),
                isWeekend = isWeekend(),
                lastNoPickWeekNotified = prefs.lastNoPickWeekNotified,
            )
            notices.forEach { postNotification(it) }
            if (notices.any { it.kind == NoticeKind.NO_PICK_WEEKEND }) prefs.lastNoPickWeekNotified = after.currentWeek
        }
        return Result.success()
    }

    private fun isWeekend(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val day = cal.get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    private fun postNotification(notice: Notice) {
        ensureChannel()
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, notice.kind.ordinal * 1000 + notice.week, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.i(TAG, "Skipping notification - POST_NOTIFICATIONS not granted")
            return
        }
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(notice.kind.ordinal * 1000 + notice.week, notification)
        }.onFailure { e -> Log.w(TAG, "Could not post notification", e) }
    }

    private fun ensureChannel() {
        // minSdk is 26 (Android O) already, so notification channels always exist - no version check needed.
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Survivor updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Pick changes, results and reminders from automatic background refreshes"
        }
        manager.createNotificationChannel(channel)
    }
}

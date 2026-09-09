package com.survivor.app.background

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Interval choices offered in Settings for the periodic background refresh. */
val AUTO_REFRESH_INTERVAL_HOURS_CHOICES = listOf(3, 6, 12, 24)

/**
 * Immutable snapshot of [AutoRefreshPrefs], used by the pure notification-decision logic (see
 * [computeNotifications]) so it and its tests never need Android's `SharedPreferences`.
 */
data class AutoRefreshPrefsSnapshot(
    val enabled: Boolean = true,
    val intervalHours: Int = 24,
    val notifyPickChanged: Boolean = true,
    val notifyResultRecorded: Boolean = true,
    val notifyNoPickByWeekend: Boolean = true,
    val lastRunEpochMs: Long = 0L,
    val lastRunOutcome: String = "",
    val lastNoPickWeekNotified: Int? = null,
)

/**
 * Thin `SharedPreferences`-backed store for background-refresh settings. Deliberately app-side only -
 * unlike picks, adjustments and model settings this never touches the engine's [com.survivor.engine.UserState],
 * so it works even before any season data has been downloaded and survives a "Reset Model".
 */
class AutoRefreshPrefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL_HOURS, 24).takeIf { it in AUTO_REFRESH_INTERVAL_HOURS_CHOICES } ?: 24
        set(value) = prefs.edit { putInt(KEY_INTERVAL_HOURS, value.takeIf { it in AUTO_REFRESH_INTERVAL_HOURS_CHOICES } ?: 24) }

    var notifyPickChanged: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_PICK_CHANGED, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFY_PICK_CHANGED, value) }

    var notifyResultRecorded: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_RESULT_RECORDED, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFY_RESULT_RECORDED, value) }

    var notifyNoPickByWeekend: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_NO_PICK_WEEKEND, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFY_NO_PICK_WEEKEND, value) }

    var lastRunEpochMs: Long
        get() = prefs.getLong(KEY_LAST_RUN_EPOCH_MS, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_RUN_EPOCH_MS, value) }

    var lastRunOutcome: String
        get() = prefs.getString(KEY_LAST_RUN_OUTCOME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST_RUN_OUTCOME, value) }

    /** When the worker last ran the slow full [com.survivor.app.data.SurvivorRepository.refreshNflData]
     *  (schedule + FPI) rather than the fast [com.survivor.app.data.SurvivorRepository.refreshOdds]; used to
     *  space full refreshes out to about once every 7 days since FPI changes slowly. */
    var lastFullRefreshEpochMs: Long
        get() = prefs.getLong(KEY_LAST_FULL_REFRESH_EPOCH_MS, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_FULL_REFRESH_EPOCH_MS, value) }

    /** Week the "no pick recorded" weekend reminder was last sent for; null once a new week starts. */
    var lastNoPickWeekNotified: Int?
        get() = prefs.getInt(KEY_LAST_NO_PICK_WEEK, -1).takeIf { it >= 0 }
        set(value) = prefs.edit { putInt(KEY_LAST_NO_PICK_WEEK, value ?: -1) }

    fun snapshot() = AutoRefreshPrefsSnapshot(
        enabled = enabled,
        intervalHours = intervalHours,
        notifyPickChanged = notifyPickChanged,
        notifyResultRecorded = notifyResultRecorded,
        notifyNoPickByWeekend = notifyNoPickByWeekend,
        lastRunEpochMs = lastRunEpochMs,
        lastRunOutcome = lastRunOutcome,
        lastNoPickWeekNotified = lastNoPickWeekNotified,
    )

    fun recordRun(atEpochMs: Long, outcome: String) {
        lastRunEpochMs = atEpochMs
        lastRunOutcome = outcome
    }

    companion object {
        private const val FILE_NAME = "auto_refresh_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_NOTIFY_PICK_CHANGED = "notify_pick_changed"
        private const val KEY_NOTIFY_RESULT_RECORDED = "notify_result_recorded"
        private const val KEY_NOTIFY_NO_PICK_WEEKEND = "notify_no_pick_weekend"
        private const val KEY_LAST_RUN_EPOCH_MS = "last_run_epoch_ms"
        private const val KEY_LAST_RUN_OUTCOME = "last_run_outcome"
        private const val KEY_LAST_FULL_REFRESH_EPOCH_MS = "last_full_refresh_epoch_ms"
        private const val KEY_LAST_NO_PICK_WEEK = "last_no_pick_week"
    }
}

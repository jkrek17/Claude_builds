package com.survivor.app.background

import com.survivor.engine.Evaluation
import com.survivor.engine.PickResult
import kotlin.math.roundToInt

/** Which of the three notification categories a [Notice] belongs to; drives the toggle it respects and
 *  lets [AutoRefreshWorker] build a stable notification id from `kind` + `week`. */
enum class NoticeKind { PICK_CHANGED, RESULT_RECORDED, NO_PICK_WEEKEND }

/** One notification a background refresh decided to raise. Deliberately free of any Android notification
 *  type so the decision logic below is a pure, directly-testable function. */
data class Notice(val kind: NoticeKind, val week: Int, val title: String, val text: String)

/**
 * Decides which notifications (if any) a background refresh should raise, by comparing the [Evaluation]
 * snapshotted immediately before the refresh to the one computed immediately after. Pure and deterministic:
 * no Android imports, no I/O, no clock reads (the caller decides "now" and "is it the weekend"). See
 * [AutoRefreshWorker] for how this plugs into WorkManager + NotificationManager.
 *
 * @param before evaluation from right before the refresh, or null when there is nothing to compare against
 *   (e.g. the very first automatic run, or no season data yet)
 * @param after evaluation from right after the refresh - always required
 * @param prefs which of the three notification categories are turned on
 * @param isWeekend true when the device's local calendar day is Saturday or Sunday
 * @param lastNoPickWeekNotified the week number the "no pick recorded" reminder was last sent for (or null),
 *   so it fires at most once per week
 */
fun computeNotifications(
    before: Evaluation?,
    after: Evaluation,
    prefs: AutoRefreshPrefsSnapshot,
    isWeekend: Boolean,
    lastNoPickWeekNotified: Int?,
): List<Notice> {
    val notices = mutableListOf<Notice>()

    // The recommended team for the current week changed, and the user hasn't locked in a pick yet - once a
    // pick is recorded, Evaluator always reports it as "recommended", so this only ever fires pre-pick.
    if (prefs.notifyPickChanged && before != null && before.currentWeek == after.currentWeek && after.currentPick == null) {
        val previous = before.recommended?.team
        val current = after.recommended
        if (previous != null && current != null && previous != current.team) {
            notices += Notice(
                kind = NoticeKind.PICK_CHANGED,
                week = after.currentWeek,
                title = "Recommended pick changed",
                text = "${previous.abbr} → ${current.team.abbr} (${pct(current.probability)})",
            )
        }
    }

    // A recorded pick's result moved from PENDING to a final result.
    if (prefs.notifyResultRecorded && before != null) {
        val beforeResultByWeek = before.pickOutcomes.associate { it.pick.week to it.result }
        after.pickOutcomes.forEach { outcome ->
            val previousResult = beforeResultByWeek[outcome.pick.week]
            if (previousResult == PickResult.PENDING && outcome.result != PickResult.PENDING) {
                val week = outcome.pick.week
                val team = outcome.pick.team.abbr
                val strikeOf = after.pickOutcomes.count { it.isStrike && it.pick.week <= week }
                val text = when (outcome.result) {
                    PickResult.WIN -> "Week $week: $team won ✓"
                    PickResult.LOSS -> "Week $week: $team lost — strike $strikeOf of 2"
                    PickResult.TIE -> "Week $week: $team tied — strike $strikeOf of 2"
                    PickResult.PENDING -> null
                }
                if (text != null) notices += Notice(NoticeKind.RESULT_RECORDED, week, "Week $week result", text)
            }
        }
    }

    // Weekend and still no pick recorded for the current week, and the entry is still alive.
    if (prefs.notifyNoPickByWeekend && isWeekend && after.currentPick == null && !after.eliminated &&
        lastNoPickWeekNotified != after.currentWeek
    ) {
        notices += Notice(
            kind = NoticeKind.NO_PICK_WEEKEND,
            week = after.currentWeek,
            title = "No pick recorded",
            text = "No pick recorded for Week ${after.currentWeek}",
        )
    }

    return notices
}

private fun pct(p: Double): String = "${(p * 100).roundToInt()}%"

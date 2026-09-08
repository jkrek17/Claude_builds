package com.survivor.app.background

import com.survivor.engine.Adjustment
import com.survivor.engine.Evaluation
import com.survivor.engine.Evaluator
import com.survivor.engine.Game
import com.survivor.engine.GameState
import com.survivor.engine.Pick
import com.survivor.engine.Season
import com.survivor.engine.Team
import com.survivor.engine.UserState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Small, hand-built [Season]s just big enough for [Evaluator.evaluate] to run - see [Optimizer]'s doc: weeks
 *  with no candidate are simply dropped from the route, so a season only needs the one or two weeks a test
 *  actually cares about. Win probabilities are pinned with a manual [Adjustment] override so the outcome
 *  never depends on spread/FPI blending. */
private object Fixtures {
    private const val KICKOFF = 1_800_000_000_000L

    fun week1Game(state: GameState = GameState.SCHEDULED, homeScore: Int? = null, awayScore: Int? = null) = Game(
        id = "w1", week = 1, home = Team.BUF, away = Team.MIA, kickoffEpochMs = KICKOFF,
        state = state, homeScore = homeScore, awayScore = awayScore,
    )

    fun season(vararg games: Game) = Season(year = 2026, games = games.toList())

    fun user(
        picks: List<Pick> = emptyList(),
        favoredTeam: Team? = null,
        underdogTeam: Team? = null,
        week: Int = 1,
    ): UserState {
        val adjustments = buildList {
            if (favoredTeam != null) add(Adjustment(week, favoredTeam, overrideWinProbability = 0.9))
            if (underdogTeam != null) add(Adjustment(week, underdogTeam, overrideWinProbability = 0.35))
        }
        return UserState(picks = picks, adjustments = adjustments)
    }

    fun evaluate(season: Season, user: UserState): Evaluation = Evaluator.evaluate(season, user, nowEpochMs = KICKOFF - 100_000L)

    val defaultPrefs = AutoRefreshPrefsSnapshot()
}

class NotificationLogicTest {

    @Test fun `recommendation change is reported when no pick is recorded yet`() {
        val season = Fixtures.season(Fixtures.week1Game())
        val before = Fixtures.evaluate(season, Fixtures.user(favoredTeam = Team.BUF, underdogTeam = Team.MIA))
        val after = Fixtures.evaluate(season, Fixtures.user(favoredTeam = Team.MIA, underdogTeam = Team.BUF))
        assertEquals(Team.BUF, before.recommended?.team)
        assertEquals(Team.MIA, after.recommended?.team)

        val notices = computeNotifications(before, after, Fixtures.defaultPrefs, isWeekend = false, lastNoPickWeekNotified = null)

        assertEquals(1, notices.size)
        assertEquals(NoticeKind.PICK_CHANGED, notices.single().kind)
        assertTrue(notices.single().text.contains("BUF"))
        assertTrue(notices.single().text.contains("MIA"))
    }

    @Test fun `recommendation change is suppressed once a pick is recorded for the week`() {
        val season = Fixtures.season(Fixtures.week1Game())
        val pick = listOf(Pick(1, Team.BUF))
        val before = Fixtures.evaluate(season, Fixtures.user(picks = pick, favoredTeam = Team.BUF, underdogTeam = Team.MIA))
        val after = Fixtures.evaluate(season, Fixtures.user(picks = pick, favoredTeam = Team.MIA, underdogTeam = Team.BUF))
        // Evaluator always reports the recorded pick as "recommended" once one exists for the week.
        assertEquals(Team.BUF, before.recommended?.team)
        assertEquals(Team.BUF, after.recommended?.team)

        val notices = computeNotifications(before, after, Fixtures.defaultPrefs, isWeekend = false, lastNoPickWeekNotified = null)

        assertTrue(notices.none { it.kind == NoticeKind.PICK_CHANGED })
    }

    @Test fun `recommendation change is suppressed when the toggle is off`() {
        val season = Fixtures.season(Fixtures.week1Game())
        val before = Fixtures.evaluate(season, Fixtures.user(favoredTeam = Team.BUF, underdogTeam = Team.MIA))
        val after = Fixtures.evaluate(season, Fixtures.user(favoredTeam = Team.MIA, underdogTeam = Team.BUF))

        val notices = computeNotifications(
            before, after, Fixtures.defaultPrefs.copy(notifyPickChanged = false), isWeekend = false, lastNoPickWeekNotified = null,
        )

        assertTrue(notices.none { it.kind == NoticeKind.PICK_CHANGED })
    }

    @Test fun `a win landing is reported with a checkmark`() {
        val pick = listOf(Pick(1, Team.BUF))
        val user = Fixtures.user(picks = pick)
        val before = Fixtures.evaluate(Fixtures.season(Fixtures.week1Game()), user)
        val after = Fixtures.evaluate(
            Fixtures.season(Fixtures.week1Game(state = GameState.FINAL, homeScore = 27, awayScore = 17)), user,
        )

        val notices = computeNotifications(before, after, Fixtures.defaultPrefs, isWeekend = false, lastNoPickWeekNotified = null)

        assertEquals(1, notices.size)
        val notice = notices.single()
        assertEquals(NoticeKind.RESULT_RECORDED, notice.kind)
        assertEquals(1, notice.week)
        assertTrue(notice.text.contains("Week 1"))
        assertTrue(notice.text.contains("BUF"))
        assertTrue(notice.text.contains("won"))
    }

    @Test fun `a loss landing reports the strike count`() {
        val pick = listOf(Pick(1, Team.BUF))
        val user = Fixtures.user(picks = pick)
        val before = Fixtures.evaluate(Fixtures.season(Fixtures.week1Game()), user)
        val after = Fixtures.evaluate(
            Fixtures.season(Fixtures.week1Game(state = GameState.FINAL, homeScore = 10, awayScore = 24)), user,
        )

        val notices = computeNotifications(before, after, Fixtures.defaultPrefs, isWeekend = false, lastNoPickWeekNotified = null)

        assertEquals(1, notices.size)
        val notice = notices.single()
        assertTrue(notice.text.contains("lost"))
        assertTrue(notice.text.contains("strike 1 of 2"))
    }

    @Test fun `result notifications are suppressed when the toggle is off`() {
        val pick = listOf(Pick(1, Team.BUF))
        val user = Fixtures.user(picks = pick)
        val before = Fixtures.evaluate(Fixtures.season(Fixtures.week1Game()), user)
        val after = Fixtures.evaluate(
            Fixtures.season(Fixtures.week1Game(state = GameState.FINAL, homeScore = 27, awayScore = 17)), user,
        )

        val notices = computeNotifications(
            before, after, Fixtures.defaultPrefs.copy(notifyResultRecorded = false), isWeekend = false, lastNoPickWeekNotified = null,
        )

        assertTrue(notices.none { it.kind == NoticeKind.RESULT_RECORDED })
    }

    @Test fun `weekend reminder fires once when no pick is recorded and the entry is alive`() {
        val season = Fixtures.season(Fixtures.week1Game())
        val after = Fixtures.evaluate(season, Fixtures.user())

        val firstRun = computeNotifications(null, after, Fixtures.defaultPrefs, isWeekend = true, lastNoPickWeekNotified = null)
        assertEquals(1, firstRun.size)
        assertEquals(NoticeKind.NO_PICK_WEEKEND, firstRun.single().kind)
        assertTrue(firstRun.single().text.contains("Week 1"))

        // Already notified for this week - no repeat.
        val secondRun = computeNotifications(null, after, Fixtures.defaultPrefs, isWeekend = true, lastNoPickWeekNotified = after.currentWeek)
        assertTrue(secondRun.none { it.kind == NoticeKind.NO_PICK_WEEKEND })
    }

    @Test fun `weekend reminder does not fire on a weekday, when a pick exists, or when the toggle is off`() {
        val season = Fixtures.season(Fixtures.week1Game())
        val noPick = Fixtures.evaluate(season, Fixtures.user())
        val withPick = Fixtures.evaluate(season, Fixtures.user(picks = listOf(Pick(1, Team.BUF))))

        assertTrue(
            computeNotifications(null, noPick, Fixtures.defaultPrefs, isWeekend = false, lastNoPickWeekNotified = null)
                .none { it.kind == NoticeKind.NO_PICK_WEEKEND },
        )
        assertTrue(
            computeNotifications(null, withPick, Fixtures.defaultPrefs, isWeekend = true, lastNoPickWeekNotified = null)
                .none { it.kind == NoticeKind.NO_PICK_WEEKEND },
        )
        assertTrue(
            computeNotifications(null, noPick, Fixtures.defaultPrefs.copy(notifyNoPickByWeekend = false), isWeekend = true, lastNoPickWeekNotified = null)
                .none { it.kind == NoticeKind.NO_PICK_WEEKEND },
        )
    }
}

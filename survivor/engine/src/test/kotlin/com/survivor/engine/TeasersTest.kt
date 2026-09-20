package com.survivor.engine

import com.survivor.engine.data.SavedState
import com.survivor.engine.data.StateCodec
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeasersTest {
    private val kickoff = TestSeason.WEEK1_KICKOFF
    private val now = kickoff - 86_400_000L

    private fun game(id: String, home: Team, away: Team, homeSpread: Double?, state: GameState = GameState.SCHEDULED, homeScore: Int? = null, awayScore: Int? = null) = Game(
        id = id, week = 1, home = home, away = away, kickoffEpochMs = kickoff, state = state, homeScore = homeScore, awayScore = awayScore,
        line = homeSpread?.let { MarketLine("DraftKings", homeSpread = it, fetchedAtEpochMs = 1L) },
    )

    private fun assertApprox(expected: Double, actual: Double, tol: Double, label: String) =
        assertTrue(abs(expected - actual) <= tol, "$label: expected ~$expected got $actual")

    // ---- Leg qualification -------------------------------------------------------------------

    @Test fun `a home favorite at -8 qualifies as a FAV leg teased to -2`() {
        // SEA -8 at home => SEA's own line is -8.0, within the -7.5..-8.5 favorite window.
        val season = Season(2026, listOf(game("g1", Team.SEA, Team.NE, homeSpread = -8.0)))
        val board = Teasers.find(season, UserState(), now)
        val leg = board.legs.single { it.team == Team.SEA }
        assertEquals(TeaserWindow.FAV, leg.window)
        assertEquals(-8.0, leg.originalPoint)
        assertEquals(-2.0, leg.teasedPoint)
        // The other side (NE +8, an underdog outside the +1.5..+2.5 window) does not qualify.
        assertTrue(board.legs.none { it.team == Team.NE })
    }

    @Test fun `an away underdog at +2 qualifies as a DOG leg teased to +8`() {
        val season = Season(2026, listOf(game("g1", Team.SEA, Team.NE, homeSpread = -2.0)))
        val board = Teasers.find(season, UserState(), now)
        val leg = board.legs.single { it.team == Team.NE }
        assertEquals(TeaserWindow.DOG, leg.window)
        assertEquals(2.0, leg.originalPoint)
        assertEquals(8.0, leg.teasedPoint)
    }

    @Test fun `exactly -7 and -3 do not qualify`() {
        val season7 = Season(2026, listOf(game("g1", Team.SEA, Team.NE, homeSpread = -7.0)))
        assertTrue(Teasers.find(season7, UserState(), now).legs.isEmpty())
        val season3 = Season(2026, listOf(game("g1", Team.SEA, Team.NE, homeSpread = -3.0)))
        assertTrue(Teasers.find(season3, UserState(), now).legs.isEmpty())
    }

    @Test fun `the whole favorite and underdog windows qualify at both boundaries`() {
        for (favSpread in listOf(-7.5, -8.0, -8.5)) {
            val season = Season(2026, listOf(game("g1", Team.SEA, Team.NE, homeSpread = favSpread)))
            val leg = Teasers.find(season, UserState(), now).legs.single { it.team == Team.SEA }
            assertEquals(TeaserWindow.FAV, leg.window)
        }
        for (dogSpread in listOf(-1.5, -2.0, -2.5)) {
            // Home team is the underdog here (positive home spread => away favored).
            val season = Season(2026, listOf(game("g1", Team.SEA, Team.NE, homeSpread = -dogSpread)))
            val leg = Teasers.find(season, UserState(), now).legs.single { it.team == Team.SEA }
            assertEquals(TeaserWindow.DOG, leg.window)
        }
    }

    @Test fun `a home dog qualifies using its own (positive) line`() {
        // SEA is home and getting +2 (away favored by 2).
        val season = Season(2026, listOf(game("g1", Team.SEA, Team.NE, homeSpread = 2.0)))
        val leg = Teasers.find(season, UserState(), now).legs.single { it.team == Team.SEA }
        assertEquals(TeaserWindow.DOG, leg.window)
        assertEquals(2.0, leg.originalPoint)
        assertEquals(8.0, leg.teasedPoint)
        assertTrue(leg.teamIsHome)
    }

    // ---- Break-even ----------------------------------------------------------------------------

    @Test fun `break-even leg rate is sqrt(1 over decimal odds)`() {
        assertApprox(0.7237, Teasers.breakEvenLegRate(-110), 0.001, "break-even -110")
        assertApprox(0.7385, Teasers.breakEvenLegRate(-120), 0.001, "break-even -120")
        assertApprox(0.7518, Teasers.breakEvenLegRate(-130), 0.001, "break-even -130")
        // Sanity against the raw formula.
        val decimal = BettingEngine.decimalOdds(-120)
        assertEquals(sqrt(1.0 / decimal), Teasers.breakEvenLegRate(-120), 1e-12)
    }

    // ---- EV of a fav+dog pairing at -120, hand-computed ------------------------------------------

    @Test fun `EV of a favorite+underdog pairing at -120 matches the hand-computed number`() {
        val settings = ModelSettings(teaserPrice = -120, teaserLegHaircut = 0.015)
        val favSeason = Season(2026, listOf(game("gfav", Team.SEA, Team.NE, homeSpread = -8.0)))
        val dogSeason = Season(2026, listOf(game("gdog", Team.KC, Team.DEN, homeSpread = -2.0)))
        val combined = Season(2026, favSeason.games + dogSeason.games)
        val board = Teasers.find(combined, UserState(settings = settings), now)
        val fav = board.legs.single { it.team == Team.SEA }
        val dog = board.legs.single { it.team == Team.DEN }
        assertApprox(0.716, fav.legRate, 1e-9, "fav leg rate")
        assertApprox(0.749, dog.legRate, 1e-9, "dog leg rate")
        val candidate = board.candidates.single { c -> c.legs.any { it.team == Team.SEA } && c.legs.any { it.team == Team.DEN } }
        val expectedWinProb = 0.716 * 0.749
        val expectedDecimal = 1.0 + 100.0 / 120.0
        val expectedEv = expectedWinProb * expectedDecimal - 1.0
        assertApprox(expectedWinProb, candidate.winProbability, 1e-9, "win probability")
        assertApprox(expectedEv, candidate.ev, 1e-9, "EV")
        assertApprox(-0.0168, expectedEv, 0.001, "EV vs the hand-computed -1.68%")
    }

    // ---- Grading, all four outcomes --------------------------------------------------------------

    private fun leg(gameId: String, team: Team, teamIsHome: Boolean, teasedPoint: Double, window: TeaserWindow = TeaserWindow.FAV) =
        TeaserLeg(gameId, team, teamIsHome, originalPoint = teasedPoint - 6.0, teasedPoint = teasedPoint, window = window, legRate = 0.7, kickoffEpochMs = kickoff, rationale = "")

    @Test fun `grades WIN when every leg covers its teased number`() {
        val season = Season(
            2026,
            listOf(
                game("g1", Team.SEA, Team.NE, null, GameState.FINAL, 30, 10), // SEA -2 covers by 20
                game("g2", Team.KC, Team.DEN, null, GameState.FINAL, 20, 25), // DEN home dog? DEN away here, gets +8 -> covers
            ),
        )
        val bet = TeaserBet("t1", 1L, 1, listOf(leg("g1", Team.SEA, true, -2.0), leg("g2", Team.DEN, false, 8.0)), -120, 25.0)
        assertEquals(BetResult.WIN, Teasers.grade(bet, season))
    }

    @Test fun `grades LOSS when any leg fails to cover`() {
        val season = Season(
            2026,
            listOf(
                game("g1", Team.SEA, Team.NE, null, GameState.FINAL, 10, 30), // SEA -2, loses outright
                game("g2", Team.KC, Team.DEN, null, GameState.FINAL, 20, 25),
            ),
        )
        val bet = TeaserBet("t1", 1L, 1, listOf(leg("g1", Team.SEA, true, -2.0), leg("g2", Team.DEN, false, 8.0)), -120, 25.0)
        assertEquals(BetResult.LOSS, Teasers.grade(bet, season))
    }

    @Test fun `grades PUSH when a leg lands exactly on the teased number and none lose`() {
        val season = Season(
            2026,
            listOf(
                game("g1", Team.SEA, Team.NE, null, GameState.FINAL, 12, 10), // SEA -2 -> margin exactly 0
                game("g2", Team.KC, Team.DEN, null, GameState.FINAL, 20, 25),
            ),
        )
        val bet = TeaserBet("t1", 1L, 1, listOf(leg("g1", Team.SEA, true, -2.0), leg("g2", Team.DEN, false, 8.0)), -120, 25.0)
        assertEquals(BetResult.PUSH, Teasers.grade(bet, season))
    }

    @Test fun `grades PENDING until every leg is final`() {
        val season = Season(
            2026,
            listOf(
                game("g1", Team.SEA, Team.NE, null, GameState.FINAL, 30, 10),
                game("g2", Team.KC, Team.DEN, null, GameState.SCHEDULED),
            ),
        )
        val bet = TeaserBet("t1", 1L, 1, listOf(leg("g1", Team.SEA, true, -2.0), leg("g2", Team.DEN, false, 8.0)), -120, 25.0)
        assertEquals(BetResult.PENDING, Teasers.grade(bet, season))
    }

    @Test fun `profit is stake times (decimal minus 1) on a win, -stake on a loss, 0 on push or pending`() {
        val bet = TeaserBet("t1", 1L, 1, emptyList(), -120, 100.0)
        assertApprox(83.33, Teasers.profit(bet, BetResult.WIN), 0.01, "win profit")
        assertEquals(-100.0, Teasers.profit(bet, BetResult.LOSS))
        assertEquals(0.0, Teasers.profit(bet, BetResult.PUSH))
        assertEquals(0.0, Teasers.profit(bet, BetResult.PENDING))
    }

    // ---- Fewer than 2 legs ----------------------------------------------------------------------

    @Test fun `notes when fewer than 2 legs qualify and produces no candidates`() {
        val season = Season(2026, listOf(game("g1", Team.SEA, Team.NE, homeSpread = -8.0)))
        val board = Teasers.find(season, UserState(), now)
        assertEquals(1, board.legs.size)
        assertTrue(board.candidates.isEmpty())
        assertNotNull(board.note)
    }

    // ---- JSON round trip ----------------------------------------------------------------------

    @Test fun `UserState with a TeaserBet round-trips through StateCodec`() {
        val bet = TeaserBet(
            "t1", 10L, 1,
            listOf(leg("g1", Team.SEA, true, -2.0), leg("g2", Team.DEN, false, 8.0)),
            -120, 25.0,
        )
        val user = UserState(teaserBets = listOf(bet))
        val season = Season(2026, listOf(game("g1", Team.SEA, Team.NE, homeSpread = -8.0)))
        val text = StateCodec.encode(SavedState(season, user, 9L))
        val back = StateCodec.decode(text)
        assertEquals(user, back.user)
        assertEquals(bet, back.user.teaserBets.single())
    }

    @Test fun `old saved files without teaser fields still decode with defaults`() {
        val minimal = """{"season":{"year":2026,"games":[]},"user":{},"savedAtEpochMs":1}"""
        val back = StateCodec.decode(minimal)
        assertNotNull(back.season)
        assertTrue(back.user.teaserBets.isEmpty())
        assertEquals(-120, back.user.settings.teaserPrice)
        assertEquals(6.0, back.user.settings.teaserPoints)
    }
}

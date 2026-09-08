package com.survivor.engine

import com.survivor.engine.data.EspnParser
import com.survivor.engine.data.OddsApiParser
import com.survivor.engine.data.StateCodec
import com.survivor.engine.data.SavedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BettingTest {
    private fun fixture(name: String) = javaClass.classLoader.getResource(name)!!.readText()

    private val kickoff = EspnParser.parseInstant("2026-09-10T00:20:00Z")

    /** SEA (home) vs NE (away), matching the odds board fixture's teams and kickoff. */
    private fun seaNeGame(
        line: MarketLine? = MarketLine("DraftKings", homeSpread = -3.0, homeMoneyline = -170, awayMoneyline = 142, fetchedAtEpochMs = 1L),
        fpi: FpiProjection? = FpiProjection(0.75, 1L),
        state: GameState = GameState.SCHEDULED,
        homeScore: Int? = null,
        awayScore: Int? = null,
    ) = Game(
        id = "401872656", week = 1, home = Team.SEA, away = Team.NE, kickoffEpochMs = kickoff,
        state = state, homeScore = homeScore, awayScore = awayScore, line = line, fpi = fpi,
    )

    // ---- Board parsing ------------------------------------------------------------------------

    @Test fun `parses a multi-book board with all three markets and attaches by teams`() {
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        assertEquals(1, boards.size)
        val board = boards.single()
        assertEquals(Team.SEA, board.home); assertEquals(Team.NE, board.away)
        assertEquals(kickoff, board.commenceEpochMs)
        assertEquals("abc123", board.gameId)
        assertEquals(17, board.quotes.size) // 3 books x 2 outcomes for h2h/spreads, but BetMGM totals is Over-only
        assertEquals(6, board.quotes.count { it.market == Market.MONEYLINE })
        assertEquals(6, board.quotes.count { it.market == Market.SPREAD })
        assertEquals(5, board.quotes.count { it.market == Market.TOTAL })

        val betMgmSpreadSea = board.quotes.single { it.market == Market.SPREAD && it.book == "BetMGM" && it.side == "SEA" }
        assertEquals(-3.0, betMgmSpreadSea.point); assertEquals(-102, betMgmSpreadSea.price)
        val dkTotalOver = board.quotes.single { it.market == Market.TOTAL && it.book == "DraftKings" && it.side == "OVER" }
        assertEquals(44.5, dkTotalOver.point); assertEquals(-110, dkTotalOver.price)

        val game = seaNeGame()
        val attached = OddsApiParser.attachBoard(listOf(game), boards)
        assertEquals(1, attached.size)
        assertEquals(board, attached[game.id])
    }

    @Test fun `attachBoard does not match games outside the 3-day kickoff window`() {
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        val farGame = seaNeGame().copy(id = "other", kickoffEpochMs = kickoff + 10L * 86_400_000L)
        assertTrue(OddsApiParser.attachBoard(listOf(farGame), boards).isEmpty())
    }

    // ---- Fair price / EV / Kelly arithmetic ----------------------------------------------------

    private fun boardSeason(quotes: List<Quote>, game: Game = seaNeGame()): Season {
        val board = GameBoard(home = game.home, away = game.away, commenceEpochMs = game.kickoffEpochMs, quotes = quotes, fetchedAtEpochMs = 1L)
        return Season(2026, listOf(game), boardFetchedAtEpochMs = 1L).copy(board = mapOf(game.id to board))
    }

    @Test fun `two books at even juice with a slightly better third price fails the edge threshold`() {
        // Two books at -110/-110 -> fair 50%; a third book offers -105 on one side.
        // ev = 0.5 x decimalOdds(-105) - 1 = 0.5 x 1.9524 - 1 ~= -2.4% -> below the 1% edge, no pick.
        val quotes = listOf(
            Quote("Book1", Market.MONEYLINE, "SEA", null, -110), Quote("Book1", Market.MONEYLINE, "NE", null, -110),
            Quote("Book2", Market.MONEYLINE, "SEA", null, -110), Quote("Book2", Market.MONEYLINE, "NE", null, -110),
            Quote("Book3", Market.MONEYLINE, "SEA", null, -105),
        )
        val season = boardSeason(quotes)
        val board = BettingEngine.evaluate(season, UserState(), kickoff - 1000L)
        val lineShopMl = board.picks.filter { it.signal == Signal.LINE_SHOP && it.market == Market.MONEYLINE }
        assertTrue(lineShopMl.isEmpty(), "expected no line-shop moneyline pick: $lineShopMl")
    }

    @Test fun `a clearly mispriced underdog price clears the edge threshold with the hand-derived ev`() {
        val fair = Probability.noVig(140, -160) // both books quote the same NE/SEA prices
        val quotes = listOf(
            Quote("Book1", Market.MONEYLINE, "SEA", null, -160), Quote("Book1", Market.MONEYLINE, "NE", null, 140),
            Quote("Book2", Market.MONEYLINE, "SEA", null, -160), Quote("Book2", Market.MONEYLINE, "NE", null, 140),
            Quote("Book3", Market.MONEYLINE, "NE", null, 180), // best price, outside the paired consensus books
        )
        val season = boardSeason(quotes)
        val board = BettingEngine.evaluate(season, UserState(), kickoff - 1000L)
        val pick = board.picks.single { it.signal == Signal.LINE_SHOP && it.market == Market.MONEYLINE && it.side == "NE" }
        assertEquals(Signal.LINE_SHOP, pick.signal)
        assertEquals("Book3", pick.bestBook); assertEquals(180, pick.bestPrice)
        assertEquals(fair, pick.fairProbability, 1e-9)
        assertEquals(2, pick.consensusBooks) // only the two paired books feed the average
        val expectedEv = fair * BettingEngine.decimalOdds(180) - 1.0
        assertEquals(expectedEv, pick.ev, 1e-9)
        assertTrue(pick.ev > 0.05)
        // Kelly: b = decimal - 1, q = 1 - p.
        val decimal = BettingEngine.decimalOdds(180)
        val expectedKelly = ((decimal - 1.0) * fair - (1.0 - fair)) / (decimal - 1.0)
        assertEquals(expectedKelly, pick.kellyFraction, 1e-9)
        val settings = ModelSettings()
        val expectedStake = minOf(settings.bankroll * expectedKelly * settings.kellyMultiplier, settings.bankroll * settings.maxStakePct)
        assertEquals(Math.round(expectedStake).toDouble(), pick.stake, 1e-9)
    }

    @Test fun `kelly stake is zero for a non-positive edge and capped at maxStakePct otherwise`() {
        assertEquals(0.0, BettingEngine.stakeFor(-0.01, ModelSettings()))
        val negativeKelly = BettingEngine.kellyFraction(0.3, 1.5) // b=0.5, q=0.7 -> (0.5*0.3-0.7)/0.5 = -1.1
        assertTrue(negativeKelly < 0.0)
        assertEquals(0.0, BettingEngine.stakeFor(negativeKelly, ModelSettings()))
        val settings = ModelSettings(bankroll = 1000.0, kellyMultiplier = 1.0, maxStakePct = 0.02)
        // A huge edge would want to stake far more than the cap allows.
        val stake = BettingEngine.stakeFor(0.9, settings)
        assertEquals(20.0, stake, 1e-9) // 1000 * 0.02
    }

    // ---- Line shopping on the full fixture: better-number and totals --------------------------

    @Test fun `full board fixture yields the expected line-shop picks including a better spread number`() {
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        val game = seaNeGame()
        val attached = OddsApiParser.attachBoard(listOf(game), boards)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = 5L).copy(board = attached)
        val betBoard = BettingEngine.evaluate(season, UserState(), kickoff - 1000L)
        val lineShop = betBoard.picks.filter { it.signal == Signal.LINE_SHOP }

        // Moneyline: NE at BetMGM (+165) clears the edge; SEA does not.
        val ml = lineShop.single { it.market == Market.MONEYLINE }
        assertEquals("NE", ml.side); assertEquals("BetMGM", ml.bestBook); assertEquals(165, ml.bestPrice)
        assertEquals("", ml.note)

        // Spread: BetMGM hangs SEA -3 (vs. the DraftKings/FanDuel -3.5 consensus) - a "better number".
        val spread = lineShop.single { it.market == Market.SPREAD }
        assertEquals("SEA", spread.side); assertEquals("better number", spread.note)
        assertEquals(-3.0, spread.point); assertEquals("BetMGM", spread.bestBook); assertEquals(-102, spread.bestPrice)
        assertEquals(2, spread.consensusBooks) // DraftKings + FanDuel at -3.5

        // Total: BetMGM's Over +105 clears the edge against the 44.5 consensus.
        val total = lineShop.single { it.market == Market.TOTAL }
        assertEquals("OVER", total.side); assertEquals("BetMGM", total.bestBook); assertEquals(105, total.bestPrice)
        assertEquals(2, total.consensusBooks) // DraftKings + FanDuel; BetMGM only quotes Over so it isn't paired
        assertTrue(total.ev > 0.01)

        // Line-shop picks sort ahead of model picks, by ev descending within each group.
        val firstModelIndex = betBoard.picks.indexOfFirst { it.signal == Signal.MODEL }
        val lastLineShopIndex = betBoard.picks.indexOfLast { it.signal == Signal.LINE_SHOP }
        assertTrue(lastLineShopIndex < firstModelIndex)
        assertTrue(lineShop.zipWithNext().all { (a, b) -> a.ev >= b.ev })
    }

    @Test fun `better-number spread shift matches shiftByPoints directly (half point worth about 1_8pct at sigma 11)`() {
        val shifted = Probability.shiftByPoints(0.5, 0.5, 11.0)
        assertEquals(0.5181, shifted, 0.001) // Φ(0.5/11) ~= 0.5181
        assertTrue(shifted - 0.5 in 0.017..0.019)
    }

    @Test fun `includeTotals=false drops the total market entirely`() {
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        val game = seaNeGame()
        val attached = OddsApiParser.attachBoard(listOf(game), boards)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = 5L).copy(board = attached)
        val user = UserState(settings = ModelSettings(includeTotals = false))
        val board = BettingEngine.evaluate(season, user, kickoff - 1000L)
        assertTrue(board.picks.none { it.market == Market.TOTAL })
    }

    // ---- Model signal ---------------------------------------------------------------------------

    @Test fun `model signal is labeled MODEL, never LINE_SHOP, and only appears above its own threshold`() {
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        val game = seaNeGame(fpi = FpiProjection(0.75, 1L))
        val attached = OddsApiParser.attachBoard(listOf(game), boards)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = 5L).copy(board = attached)
        val betBoard = BettingEngine.evaluate(season, UserState(), kickoff - 1000L)
        val model = betBoard.picks.filter { it.signal == Signal.MODEL }
        assertTrue(model.isNotEmpty())
        assertTrue(model.all { it.ev >= ModelSettings().minModelEdge })
        assertTrue(model.none { it.signal == Signal.LINE_SHOP })
        // The blended model favors SEA heavily (FPI 75% at 40% weight); the SEA moneyline model pick
        // should show up using the board's best SEA price.
        val mlModel = model.single { it.market == Market.MONEYLINE }
        assertEquals("SEA", mlModel.side)
        assertEquals("FanDuel", mlModel.bestBook); assertEquals(-160, mlModel.bestPrice)
    }

    @Test fun `model signal falls back to the ESPN DraftKings line when the board is empty`() {
        val game = seaNeGame(fpi = FpiProjection(0.75, 1L))
        val season = Season(2026, listOf(game)) // no board attached at all
        val betBoard = BettingEngine.evaluate(season, UserState(), kickoff - 1000L)
        assertTrue(betBoard.picks.all { it.signal == Signal.MODEL })
        val ml = betBoard.picks.first { it.market == Market.MONEYLINE }
        assertEquals("DraftKings (ESPN)", ml.bestBook)
        val spread = betBoard.picks.firstOrNull { it.market == Market.SPREAD }
        if (spread != null) {
            assertEquals("DraftKings (ESPN)", spread.bestBook)
            assertEquals(-110, spread.bestPrice) // standard juice assumed, no board price available
        }
        assertTrue(betBoard.picks.none { it.market == Market.TOTAL }) // ESPN's line carries no total
    }

    @Test fun `no pending model picks for games that have already kicked off`() {
        val game = seaNeGame(fpi = FpiProjection(0.9, 1L))
        val season = Season(2026, listOf(game))
        val betBoard = BettingEngine.evaluate(season, UserState(), kickoff + 1000L) // kickoff already passed
        assertTrue(betBoard.picks.isEmpty())
    }

    // ---- Grading ----------------------------------------------------------------------------

    private fun finalGame(homeScore: Int, awayScore: Int) = seaNeGame(state = GameState.FINAL, homeScore = homeScore, awayScore = awayScore)

    @Test fun `moneyline grades win, loss and push, and stays pending before final`() {
        val betSea = Bet("1", 0L, "401872656", 1, Market.MONEYLINE, "SEA", null, -170, 100.0, "DraftKings")
        assertEquals(BetResult.PENDING, BettingEngine.grade(betSea, seaNeGame()))
        assertEquals(BetResult.WIN, BettingEngine.grade(betSea, finalGame(27, 17)))
        assertEquals(BetResult.LOSS, BettingEngine.grade(betSea, finalGame(17, 27)))
        assertEquals(BetResult.PUSH, BettingEngine.grade(betSea, finalGame(20, 20)))
    }

    @Test fun `spread grades win, loss and push on the exact number`() {
        val betSea = Bet("2", 0L, "401872656", 1, Market.SPREAD, "SEA", -3.5, -110, 100.0, "DraftKings")
        assertEquals(BetResult.WIN, BettingEngine.grade(betSea, finalGame(24, 20))) // 24-3.5=20.5 > 20
        assertEquals(BetResult.LOSS, BettingEngine.grade(betSea, finalGame(20, 24))) // 20-3.5=16.5 < 24
        val pushBet = betSea.copy(point = -3.0)
        assertEquals(BetResult.PUSH, BettingEngine.grade(pushBet, finalGame(23, 20))) // 23-3=20
        assertEquals(BetResult.PENDING, BettingEngine.grade(betSea, seaNeGame()))
    }

    @Test fun `total grades win, loss and push on the exact number`() {
        val over = Bet("3", 0L, "401872656", 1, Market.TOTAL, "OVER", 44.5, -110, 100.0, "DraftKings")
        val under = over.copy(id = "4", side = "UNDER")
        assertEquals(BetResult.WIN, BettingEngine.grade(over, finalGame(24, 21))) // total 45 > 44.5
        assertEquals(BetResult.LOSS, BettingEngine.grade(over, finalGame(20, 21))) // total 41 < 44.5
        assertEquals(BetResult.WIN, BettingEngine.grade(under, finalGame(20, 21))) // total 41 < 44.5
        assertEquals(BetResult.LOSS, BettingEngine.grade(under, finalGame(24, 21))) // total 45 > 44.5
        val pushOver = over.copy(id = "5", point = 45.0)
        assertEquals(BetResult.PUSH, BettingEngine.grade(pushOver, finalGame(24, 21))) // total 45 == 45
        assertEquals(BetResult.PENDING, BettingEngine.grade(over, seaNeGame()))
    }

    @Test fun `profit is stake times decimal minus one on a win, minus stake on a loss, zero otherwise`() {
        val bet = Bet("6", 0L, "401872656", 1, Market.MONEYLINE, "NE", null, 150, 100.0, "BetMGM")
        assertEquals(150.0, BettingEngine.profit(bet, BetResult.WIN), 1e-9)
        assertEquals(-100.0, BettingEngine.profit(bet, BetResult.LOSS), 1e-9)
        assertEquals(0.0, BettingEngine.profit(bet, BetResult.PUSH))
        assertEquals(0.0, BettingEngine.profit(bet, BetResult.PENDING))
    }

    // ---- Ledger -------------------------------------------------------------------------------

    @Test fun `ledger totals, ROI, and splits by signal and market are correct`() {
        val game = finalGame(27, 17) // SEA wins by 10; total 44
        val season = Season(2026, listOf(game))
        val winMl = Bet("w1", 0L, game.id, 1, Market.MONEYLINE, "SEA", null, -170, 100.0, "DraftKings", Signal.LINE_SHOP)
        val lossSpread = Bet("l1", 0L, game.id, 1, Market.SPREAD, "NE", 3.5, -110, 50.0, "FanDuel", Signal.MODEL)
        val pushTotal = Bet("p1", 0L, game.id, 1, Market.TOTAL, "OVER", 44.0, -110, 20.0, "BetMGM", null)
        val user = UserState(bets = listOf(winMl, lossSpread, pushTotal))
        val ledger = BettingEngine.ledger(season, user)

        assertEquals(3, ledger.rows.size)
        val winRow = ledger.rows.single { it.bet.id == "w1" }
        assertEquals(BetResult.WIN, winRow.result)
        assertEquals(100.0 * (BettingEngine.decimalOdds(-170) - 1.0), winRow.profit, 1e-9)
        val lossRow = ledger.rows.single { it.bet.id == "l1" }
        assertEquals(BetResult.LOSS, lossRow.result) // NE +3.5, lost by 10 -> -6.5 -> LOSS
        assertEquals(-50.0, lossRow.profit, 1e-9)
        val pushRow = ledger.rows.single { it.bet.id == "p1" }
        assertEquals(BetResult.PUSH, pushRow.result)
        assertEquals(0.0, pushRow.profit)

        val expectedStaked = 100.0 + 50.0 + 20.0
        val expectedProfit = winRow.profit + lossRow.profit + pushRow.profit
        assertEquals(expectedStaked, ledger.totals.staked, 1e-9)
        assertEquals(expectedProfit, ledger.totals.profit, 1e-9)
        assertEquals(expectedProfit / expectedStaked, ledger.totals.roi!!, 1e-9)
        assertEquals(1, ledger.totals.wins); assertEquals(1, ledger.totals.losses); assertEquals(1, ledger.totals.pushes)
        assertEquals("1-1-1", ledger.totals.record)

        assertEquals(2, ledger.bySignal.size) // pushTotal has no signal, excluded
        assertEquals(winRow.profit, ledger.bySignal.getValue(Signal.LINE_SHOP).profit, 1e-9)
        assertEquals(lossRow.profit, ledger.bySignal.getValue(Signal.MODEL).profit, 1e-9)
        assertEquals(3, ledger.byMarket.size)
        assertEquals(0, ledger.byMarket.getValue(Market.TOTAL).wins)

        // Closing-line value: the closing line here is the game's own line (DraftKings -170/-3.0).
        // Moneyline CLV compares the closing no-vig probability to the bet price's raw (vig-included)
        // implied probability, so it isn't zero even though the bet was made at the closing price itself.
        val expectedMlClv = Probability.noVig(-170, 142) - Probability.impliedFromAmerican(-170)
        assertEquals(expectedMlClv, winRow.closingLineValue!!, 1e-9)
        val expectedSpreadClv = 3.0 - 3.5 // closing NE point (-(-3.0)=3.0) minus the bet's 3.5
        assertEquals(expectedSpreadClv, lossRow.closingLineValue!!, 1e-9)
        assertNull(pushRow.closingLineValue) // no total on ESPN's line
    }

    @Test fun `ledger closing line value is null when the game has no line or is not graded`() {
        val game = seaNeGame(line = null) // pending, no line at all
        val season = Season(2026, listOf(game))
        val bet = Bet("x", 0L, game.id, 1, Market.MONEYLINE, "SEA", null, -170, 100.0, "DraftKings")
        val ledger = BettingEngine.ledger(season, UserState(bets = listOf(bet)))
        val row = ledger.rows.single()
        assertEquals(BetResult.PENDING, row.result)
        assertNull(row.closingLineValue)
    }

    // ---- Serialization --------------------------------------------------------------------------

    @Test fun `Season with a board and UserState with bets round-trip through StateCodec`() {
        val game = seaNeGame()
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        val attached = OddsApiParser.attachBoard(listOf(game), boards)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = 5L).copy(board = attached)
        val bet = Bet("1", 10L, game.id, 1, Market.SPREAD, "SEA", -3.5, -110, 25.0, "DraftKings", Signal.LINE_SHOP, "test")
        val user = UserState(bets = listOf(bet), settings = ModelSettings(bankroll = 500.0, minModelEdge = 0.05))
        val text = StateCodec.encode(SavedState(season, user, 9L))
        val back = StateCodec.decode(text)
        assertEquals(season, back.season)
        assertEquals(user, back.user)
        assertEquals(1, back.season!!.board.size)
        assertEquals(500.0, back.user.settings.bankroll)
        assertEquals(bet, back.user.bets.single())
    }

    @Test fun `old saved files without betting fields still decode with defaults`() {
        val minimal = """{"season":{"year":2026,"games":[]},"user":{},"savedAtEpochMs":1}"""
        val back = StateCodec.decode(minimal)
        assertNotNull(back.season)
        assertTrue(back.season!!.board.isEmpty())
        assertNull(back.season!!.boardFetchedAtEpochMs)
        assertTrue(back.user.bets.isEmpty())
        assertEquals(1000.0, back.user.settings.bankroll)
    }
}

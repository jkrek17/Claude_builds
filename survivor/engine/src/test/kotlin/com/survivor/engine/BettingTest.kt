package com.survivor.engine

import com.survivor.engine.data.EspnParser
import com.survivor.engine.data.OddsApiParser
import com.survivor.engine.data.StateCodec
import com.survivor.engine.data.SavedState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // 3 books x 2 outcomes for h2h/spreads, but BetMGM totals is Over-only, plus Pinnacle's h2h-only
        // entry (the sharp reference), Unibet (SE)'s h2h-only entry (a non-US REFERENCE book) and
        // Matchbook's single, NE-only h2h entry (an EXCLUDED betting exchange) - see "Book roles" below.
        assertEquals(22, board.quotes.size)
        assertEquals(11, board.quotes.count { it.market == Market.MONEYLINE })
        assertEquals(6, board.quotes.count { it.market == Market.SPREAD })
        assertEquals(5, board.quotes.count { it.market == Market.TOTAL })

        val betMgmSpreadSea = board.quotes.single { it.market == Market.SPREAD && it.book == "BetMGM" && it.side == "SEA" }
        assertEquals(-3.0, betMgmSpreadSea.point); assertEquals(-102, betMgmSpreadSea.price)
        val dkTotalOver = board.quotes.single { it.market == Market.TOTAL && it.book == "DraftKings" && it.side == "OVER" }
        assertEquals(44.5, dkTotalOver.point); assertEquals(-110, dkTotalOver.price)
        val pinnacleMlSea = board.quotes.single { it.market == Market.MONEYLINE && it.book == "Pinnacle" && it.side == "SEA" }
        assertEquals(-165, pinnacleMlSea.price); assertEquals("pinnacle", pinnacleMlSea.bookKey)
        // Parsing itself is role-agnostic - it keeps every book's quote as-is; roles only apply at grading time.
        val unibetMlNe = board.quotes.single { it.market == Market.MONEYLINE && it.book == "Unibet (SE)" && it.side == "NE" }
        assertEquals(148, unibetMlNe.price); assertEquals("unibet_se", unibetMlNe.bookKey)
        val matchbookMlNe = board.quotes.single { it.market == Market.MONEYLINE && it.book == "Matchbook" && it.side == "NE" }
        assertEquals(820, matchbookMlNe.price); assertEquals("matchbook", matchbookMlNe.bookKey)
        assertTrue(board.quotes.none { it.book == "Matchbook" && it.side == "SEA" }) // exchange, single-sided in this fixture

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

    @Test fun `boardUrl adds the eu region for Pinnacle by default and drops it when includeSharpRegion is false`() {
        val withSharp = OddsApiParser.boardUrl("KEY")
        assertTrue(withSharp.contains("regions=us,eu"), withSharp)
        val explicitSharp = OddsApiParser.boardUrl("KEY", includeSharpRegion = true)
        assertEquals(withSharp, explicitSharp)
        val usOnly = OddsApiParser.boardUrl("KEY", includeSharpRegion = false)
        assertTrue(usOnly.contains("regions=us&"), usOnly)
        assertTrue(!usOnly.contains("eu"))
        // Both still request the same three markets - see docs/BETTING.md's quota note.
        assertTrue(withSharp.contains("markets=h2h,spreads,totals"))
        assertTrue(usOnly.contains("markets=h2h,spreads,totals"))
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

        // Moneyline: NE clears the edge; SEA does not. `evaluate`'s own consensus (`lineShopMarket`) does not
        // apply book roles (see docs/BETTING.md), so its "best price" is Matchbook's +820 - the fixture's
        // EXCLUDED exchange price - not a bettable book's; this is the legacy, unfixed behavior `evaluate`
        // is kept around for, and is exactly why `BettingEngine.board` (below) restricts best price to
        // BETTABLE-role quotes instead.
        val ml = lineShop.single { it.market == Market.MONEYLINE }
        assertEquals("NE", ml.side); assertEquals("Matchbook", ml.bestBook); assertEquals(820, ml.bestPrice)
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
        val mlModel = model.single { it.market == Market.MONEYLINE && it.side == "SEA" }
        assertEquals("FanDuel", mlModel.bestBook); assertEquals(-160, mlModel.bestPrice)
        // `evaluate`'s `moneylinePrices` does not apply book roles (see docs/BETTING.md), so NE's own
        // model pick now also shows up here, priced against Matchbook's fixture-only +820 (an EXCLUDED
        // exchange price) - a huge, meaningless "edge" that `BettingEngine.board`'s fixed best-price
        // selection can't produce.
        val neMlModel = model.single { it.market == Market.MONEYLINE && it.side == "NE" }
        assertEquals("Matchbook", neMlModel.bestBook); assertEquals(820, neMlModel.bestPrice)
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

    @Test fun `a Quote saved before bookKey existed still decodes, with an empty bookKey`() {
        val oldStyleQuote = """{"book":"DraftKings","market":"MONEYLINE","side":"SEA","price":-170}"""
        val quote = StateCodec.json.decodeFromString(Quote.serializer(), oldStyleQuote)
        assertEquals("", quote.bookKey)
        assertEquals("DraftKings", quote.book)
    }

    @Test fun `ModelSettings saved before sharpBooks-includeSharpRegion existed still decodes with defaults`() {
        val old = """{"season":null,"user":{"settings":{}},"savedAtEpochMs":1}"""
        val back = StateCodec.decode(old)
        assertEquals(listOf("pinnacle"), back.user.settings.sharpBooks)
        assertTrue(back.user.settings.includeSharpRegion)
    }

    // ---- Graded board (Bet Score) -----------------------------------------------------------

    /** 9 books paired at -110/-110 (fair 50%, zero dispersion) plus a 10th, SEA-only book at +103 as
     *  the best price: ev = 0.5 x decimalOdds(103) - 1 = 0.5 x 2.03 - 1 = +1.5% exactly. */
    private fun nineBookMlQuotes(): List<Quote> {
        val paired = (1..9).flatMap { i -> listOf(Quote("B$i", Market.MONEYLINE, "SEA", null, -110), Quote("B$i", Market.MONEYLINE, "NE", null, -110)) }
        return paired + Quote("B10", Market.MONEYLINE, "SEA", null, 103)
    }

    /**
     * Under the OLD, EV-only formula (score = 50 + 12.5 x blendedEvPct) this scenario scored 68.75 (grade
     * B-, tier ACCEPTABLE) - the exact bug this rewrite fixes: a break-even coinflip's +1.5% EV scored the
     * same as the same EV on a heavy favorite, even though its Kelly stake (and therefore its actual
     * expected growth) is tiny. Under the new growth-based formula the same inputs score near 51 (grade D,
     * tier AVOID), which is the correct read - see the favorite comparison test below for the fix in action.
     */
    @Test fun `hand-derived score - a break-even coinflip's plus-1_5pct EV now scores near AVOID, not B-Acceptable`() {
        val game = seaNeGame()
        val now = kickoff - 1000L
        val gboard = GameBoard(home = game.home, away = game.away, commenceEpochMs = game.kickoffEpochMs, quotes = nineBookMlQuotes(), fetchedAtEpochMs = now)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = now).copy(board = mapOf(game.id to gboard))
        val user = UserState(settings = ModelSettings(modelWeight = 0.0)) // isolate the line-shop EV term
        val betBoard = BettingEngine.board(season, user, now)
        val ml = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }
        val sea = ml.sides.single { it.side == "SEA" }
        assertEquals(9, sea.booksQuoting)
        assertEquals(0.015, sea.lineShopEv!!, 1e-9)
        assertEquals(0.0, sea.components.bookConfidencePenalty)
        assertEquals(0.0, sea.components.movementAdjustment)
        assertEquals(0.0, sea.components.staleBoardPenalty)

        // fairProbabilitySe = max(dispersion=0, 0.004) / sqrt(9) = 0.004 / 3.
        assertEquals(0.004 / 3.0, sea.fairProbabilitySe!!, 1e-12)
        // edgeZ = 0.015 / (se x decimal(103)) = 0.015 / ((0.004/3) x 2.03) ~= 5.54 -> HIGH confidence.
        assertEquals(0.015 / ((0.004 / 3.0) * 2.03), sea.edgeZ!!, 1e-9)
        assertEquals(Confidence.HIGH, sea.confidence)
        assertEquals(0.0, sea.components.uncertaintyPenalty) // z >= 2

        // blendedProbability = fairProbability (modelWeight = 0) = 0.5; kelly = (b*p-q)/b, b = 1.03.
        assertEquals(0.5, sea.blendedProbability!!, 1e-9)
        val expectedKelly = (1.03 * 0.5 - 0.5) / 1.03
        assertEquals(expectedKelly, sea.kellyFraction, 1e-9)
        val expectedGrowthBps = expectedKelly * 0.015 * 10_000.0
        assertEquals(expectedGrowthBps, sea.expectedGrowthBps, 1e-6)
        assertTrue(expectedGrowthBps < 3.0, "expected a tiny growth rate for a coinflip, got $expectedGrowthBps bps")
        val expectedGrowthPoints = (expectedGrowthBps / 2.0).coerceIn(-50.0, 50.0)
        assertEquals(expectedGrowthPoints, sea.components.growthPoints, 1e-6)

        val expectedScore = 50.0 + expectedGrowthPoints
        assertEquals(expectedScore, sea.score, 0.01)
        assertTrue(sea.score < 60.0, "expected the coinflip's tiny growth to land in AVOID, scored ${sea.score}")
        assertEquals("D", sea.grade)
        assertEquals(Tier.AVOID, sea.tier)
        assertFalse(sea.goodBet) // score < 68, even though the edge itself is real and confidently priced
        // All five tests actually pass here (real edge, confident, no sharp/history data to disagree, fresh
        // board) - it's the score, not the tests, that correctly keeps a coinflip-sized edge out of AVOID.
        assertTrue(sea.tests.valueVsConsensus); assertTrue(sea.tests.edgeConfident)
        assertNull(sea.tests.sharpAgrees); assertNull(sea.tests.lineNotAgainst); assertTrue(sea.tests.boardFresh)
        assertTrue(sea.tests.passed)
    }

    /**
     * The whole point of the growth-based formula: the SAME +1.5%-ish EV on a confident ~78% favorite (not
     * a coinflip) produces a Kelly stake, and therefore an expected growth rate, several times larger than
     * the coinflip case above - because a favorite's Kelly fraction is much larger at the same edge. Fair
     * probability here comes from 9 identical books at -420/+340 (no-vig ~78%, zero dispersion); the 10th
     * book improves SEA's price to -330 (still negative/favorite, just less negative).
     */
    @Test fun `hand-derived score - the same-ish EV on a confident favorite compounds far faster than on the coinflip above`() {
        val game = seaNeGame()
        val now = kickoff - 1000L
        val paired = (1..9).flatMap { i -> listOf(Quote("B$i", Market.MONEYLINE, "SEA", null, -420), Quote("B$i", Market.MONEYLINE, "NE", null, 340)) }
        val quotes = paired + Quote("B10", Market.MONEYLINE, "SEA", null, -330)
        val fair = Probability.noVig(-420, 340)
        assertEquals(0.78, fair, 0.01) // sanity check: a genuine, confident favorite, not a coinflip

        val gboard = GameBoard(home = game.home, away = game.away, commenceEpochMs = game.kickoffEpochMs, quotes = quotes, fetchedAtEpochMs = now)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = now).copy(board = mapOf(game.id to gboard))
        val user = UserState(settings = ModelSettings(modelWeight = 0.0))
        val betBoard = BettingEngine.board(season, user, now)
        val sea = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }.sides.single { it.side == "SEA" }

        val decimal = BettingEngine.decimalOdds(-330)
        val expectedEv = fair * decimal - 1.0
        assertEquals(expectedEv, sea.lineShopEv!!, 1e-9)
        assertTrue(expectedEv in 0.01..0.03, "expected a modest single-digit-percent edge, got $expectedEv") // similar order to the +1.5% coinflip case

        val expectedKelly = (((decimal - 1.0) * fair) - (1.0 - fair)) / (decimal - 1.0)
        assertEquals(expectedKelly, sea.kellyFraction, 1e-9)
        assertTrue(sea.kellyFraction > expectedKelly * 0.99) // just re-asserting our own math, but the real check is below:

        // Recompute the coinflip case's growth inline so this test doesn't depend on test execution order.
        val coinflipKelly = (1.03 * 0.5 - 0.5) / 1.03
        val coinflipGrowthBps = coinflipKelly * 0.015 * 10_000.0
        assertTrue(
            sea.expectedGrowthBps > coinflipGrowthBps * 2.0,
            "favorite growth (${sea.expectedGrowthBps} bps) should be well above the coinflip's ($coinflipGrowthBps bps) at similar EV",
        )
    }

    /** Same favorite fair price as above, but a bigger price improvement (-300 instead of -330) pushes the
     *  edge, the Kelly stake, and therefore the expected growth rate high enough to cross the good-bet
     *  score threshold (68) - demonstrating goodBet true where the modest-edge favorite above was false. */
    @Test fun `hand-derived score - a confident favorite with a strong edge crosses the good-bet threshold`() {
        val game = seaNeGame()
        val now = kickoff - 1000L
        val paired = (1..9).flatMap { i -> listOf(Quote("B$i", Market.MONEYLINE, "SEA", null, -420), Quote("B$i", Market.MONEYLINE, "NE", null, 340)) }
        val quotes = paired + Quote("B10", Market.MONEYLINE, "SEA", null, -300)
        val gboard = GameBoard(home = game.home, away = game.away, commenceEpochMs = game.kickoffEpochMs, quotes = quotes, fetchedAtEpochMs = now)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = now).copy(board = mapOf(game.id to gboard))
        val user = UserState(settings = ModelSettings(modelWeight = 0.0))
        val betBoard = BettingEngine.board(season, user, now)
        val sea = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }.sides.single { it.side == "SEA" }

        assertEquals(Confidence.HIGH, sea.confidence)
        assertTrue(sea.tests.passed)
        assertTrue(sea.score >= 68.0, "expected the strong-edge favorite to clear the good-bet score band, scored ${sea.score}")
        assertTrue(sea.goodBet)
        assertTrue(betBoard.goodBets().any { it.side == "SEA" })
    }

    @Test fun `single-source ESPN fallback carries a 10-point book-confidence penalty`() {
        val game = seaNeGame(fpi = FpiProjection(0.75, 1L))
        val season = Season(2026, listOf(game)) // no board at all
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        val ml = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }
        ml.sides.forEach {
            assertEquals(1, it.booksQuoting)
            assertEquals(10.0, it.components.bookConfidencePenalty)
            // No board at all -> no dispersion data -> no confidence, and the board-fresh test fails.
            assertNull(it.fairProbabilitySe); assertNull(it.edgeZ)
            assertEquals(Confidence.NONE, it.confidence)
            assertFalse(it.tests.boardFresh)
            assertTrue(it.tests.failedReasons.contains("No multi-book board"))
        }
    }

    @Test fun `dispersion feeds fairProbabilitySe and edgeZ - MEDIUM confidence right at the z=1 boundary`() {
        // Zero-vig books (implied probabilities already sum to 1) at 0.9 and 0.4 fair SEA -> sample
        // stdDev of [0.9, 0.4] = 0.5 / sqrt(2) ~= 0.3536. se = dispersion / sqrt(2) = 0.25 exactly.
        // bestPrice is Book2's SEA +150 (the higher of -900/+150); fair mean = 0.65; decimal(150) = 2.5;
        // ev = 0.65 x 2.5 - 1 = 0.625; z = ev / (se x decimal) = 0.625 / (0.25 x 2.5) = 1.0 exactly.
        val quotes = listOf(
            Quote("Book1", Market.MONEYLINE, "SEA", null, -900), Quote("Book1", Market.MONEYLINE, "NE", null, 900),
            Quote("Book2", Market.MONEYLINE, "SEA", null, 150), Quote("Book2", Market.MONEYLINE, "NE", null, -150),
        )
        val season = boardSeason(quotes)
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        val ml = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }
        val sea = ml.sides.single { it.side == "SEA" }
        assertEquals(2, sea.booksQuoting)
        assertEquals(0.3535533905932738, sea.lineDispersion!!, 1e-6)
        assertEquals(0.25, sea.fairProbabilitySe!!, 1e-6)
        assertEquals(1.0, sea.edgeZ!!, 1e-6)
        assertEquals(Confidence.MEDIUM, sea.confidence)
        assertEquals(4.0, sea.components.uncertaintyPenalty, 1e-9) // 1 <= z < 2
        assertTrue(sea.tests.edgeConfident) // z >= 1 clears the threshold exactly
    }

    @Test fun `edgeZ below 1 is LOW confidence and fails the edge-confident test with a plain-language reason`() {
        // Two paired books at -110/-110 (fair 50%, zero dispersion, floor se = 0.004/sqrt(2)) plus a third,
        // SEA-only book barely better at +101: ev = 0.5 x decimalOdds(101) - 1 = 0.005 exactly.
        val quotes = listOf(
            Quote("Book1", Market.MONEYLINE, "SEA", null, -110), Quote("Book1", Market.MONEYLINE, "NE", null, -110),
            Quote("Book2", Market.MONEYLINE, "SEA", null, -110), Quote("Book2", Market.MONEYLINE, "NE", null, -110),
            Quote("Book3", Market.MONEYLINE, "SEA", null, 101),
        )
        val season = boardSeason(quotes)
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        val sea = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }.sides.single { it.side == "SEA" }
        assertTrue(sea.edgeZ!! < 1.0)
        assertEquals(Confidence.LOW, sea.confidence)
        assertFalse(sea.tests.edgeConfident)
        assertEquals(10.0, sea.components.uncertaintyPenalty) // z < 1
        assertFalse(sea.goodBet)
        assertTrue(
            sea.tests.failedReasons.any { it.contains("fair-price noise") && it.contains("z = ") },
            "expected a plain-language noise reason, got ${sea.tests.failedReasons}",
        )
    }

    @Test fun `sharp reference (Pinnacle) disagrees on SEA and agrees on NE in the full fixture`() {
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        val game = seaNeGame()
        val attached = OddsApiParser.attachBoard(listOf(game), boards)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = 5L).copy(board = attached)
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        val ml = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }
        val sea = ml.sides.single { it.side == "SEA" }
        val ne = ml.sides.single { it.side == "NE" }

        // Best prices come from FanDuel/BetMGM, not Pinnacle itself.
        assertEquals("FanDuel", sea.bestBook); assertFalse(sea.bestBookIsSharp)
        assertEquals("BetMGM", ne.bestBook); assertFalse(ne.bestBookIsSharp)

        // Pinnacle SEA -165 / NE +150 -> its own no-vig SEA probability is ~60.9%, priced against FanDuel's
        // -160 (decimal 1.625) that's a small NEGATIVE sharp EV - Pinnacle thinks FanDuel's price is fair
        // or worse, not a real edge.
        val expectedSharpSea = Probability.noVig(-165, 150)
        assertEquals(expectedSharpSea, sea.sharpProbability!!, 1e-9)
        assertEquals(expectedSharpSea * BettingEngine.decimalOdds(sea.bestPrice) - 1.0, sea.sharpEv!!, 1e-9)
        assertTrue(sea.sharpEv!! < 0.0)
        assertEquals(false, sea.sharpAgrees)
        assertEquals(8.0, sea.components.sharpPenalty)
        assertFalse(sea.goodBet)
        assertTrue(
            sea.tests.failedReasons.any { it.contains("Pinnacle") && it.contains("-EV") },
            "expected a Pinnacle disagreement reason, got ${sea.tests.failedReasons}",
        )

        // Pinnacle's own no-vig NE probability against BetMGM's +165 (decimal 2.65) IS a real positive EV -
        // the sharp book agrees this side is worth taking.
        val expectedSharpNe = Probability.noVig(150, -165)
        assertEquals(expectedSharpNe, ne.sharpProbability!!, 1e-9)
        assertTrue(ne.sharpEv!! > 0.0)
        assertEquals(true, ne.sharpAgrees)
        assertEquals(0.0, ne.components.sharpPenalty)
    }

    // ---- Book roles -----------------------------------------------------------------------------

    @Test fun `roleOf maps sharp, excluded, bettable, unknown and blank keys correctly`() {
        val settings = ModelSettings()
        assertEquals(BookRole.SHARP, settings.roleOf("pinnacle"))
        assertEquals(BookRole.SHARP, settings.roleOf("Pinnacle")) // case-insensitive
        assertEquals(BookRole.EXCLUDED, settings.roleOf("matchbook"))
        assertEquals(BookRole.EXCLUDED, settings.roleOf("betfair_ex_eu"))
        assertEquals(BookRole.EXCLUDED, settings.roleOf("SMARKETS"))
        assertEquals(BookRole.BETTABLE, settings.roleOf("draftkings"))
        assertEquals(BookRole.BETTABLE, settings.roleOf("fanduel"))
        assertEquals(BookRole.BETTABLE, settings.roleOf("BetMGM")) // case-insensitive
        assertEquals(BookRole.REFERENCE, settings.roleOf("unibet_se")) // a legitimate non-US book
        assertEquals(BookRole.REFERENCE, settings.roleOf("some_future_book_the_odds_api_adds"))
        assertEquals(BookRole.BETTABLE, settings.roleOf("")) // blank - old Quote/ESPN fallback, always safe
    }

    @Test fun `board's best price never comes from an excluded exchange or a reference book, but both feed booksQuoting`() {
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        val game = seaNeGame()
        val attached = OddsApiParser.attachBoard(listOf(game), boards)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = 5L).copy(board = attached)
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        val ml = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }
        val sea = ml.sides.single { it.side == "SEA" }
        val ne = ml.sides.single { it.side == "NE" }

        // Matchbook's NE +820 (an EXCLUDED exchange price) is far better than any bettable book's, and
        // Unibet (SE) (a non-US REFERENCE book) never beats FanDuel/BetMGM here either - neither is ever
        // picked as the best price.
        assertEquals("FanDuel", sea.bestBook); assertEquals(-160, sea.bestPrice)
        assertEquals("BetMGM", ne.bestBook); assertEquals(165, ne.bestPrice)
        assertTrue(sea.bestBook != "Unibet (SE)" && sea.bestBook != "Matchbook")
        assertTrue(ne.bestBook != "Unibet (SE)" && ne.bestBook != "Matchbook")

        // Consensus/booksQuoting includes Unibet (SE) alongside DraftKings, FanDuel, BetMGM and Pinnacle
        // (5 total) - Matchbook is dropped before the consensus group is even formed.
        assertEquals(5, sea.booksQuoting); assertEquals(5, ne.booksQuoting)
        // Only the three US-licensed books (DraftKings, FanDuel, BetMGM) are BETTABLE.
        assertEquals(3, sea.bettableBooks); assertEquals(3, ne.bettableBooks)
    }

    @Test fun `ModelSettings without bettableBooks-excludedBooks decodes to the defaults`() {
        val old = """{"season":null,"user":{"settings":{}},"savedAtEpochMs":1}"""
        val back = StateCodec.decode(old)
        assertEquals(ModelSettings().bettableBooks, back.user.settings.bettableBooks)
        assertEquals(ModelSettings().excludedBooks, back.user.settings.excludedBooks)
        assertTrue(back.user.settings.bettableBooks.contains("draftkings"))
        assertTrue(back.user.settings.excludedBooks.contains("matchbook"))
    }

    @Test fun `moneyline line-moved-against reason states the win-probability move in percentage points`() {
        val game = seaNeGame() // ESPN fallback -> isMoneyline = true
        val now = kickoff - 1000L
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = now)
        // Earliest snapshot had SEA at a much higher no-vig probability than the current -170/142 line.
        val history = LineHistory(listOf(LineSnapshot(takenAtEpochMs = 0L, currentWeek = 1, lines = listOf(LineRecord(game.id, 1, homeSpread = null, homeMoneyline = -900, awayMoneyline = 700, fpiHome = null)))))
        val betBoard = BettingEngine.board(season, UserState(), now, history)
        val ml = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }
        val sea = ml.sides.single { it.side == "SEA" }
        assertEquals(false, sea.tests.lineNotAgainst)
        val expectedMove = sea.lineMovePoints!!
        assertTrue(expectedMove < 0.0)
        val expectedReason = String.format(Locale.US, "Price moved against this side (%+.1f pp win probability)", expectedMove)
        assertTrue(
            sea.tests.failedReasons.contains(expectedReason),
            "expected \"$expectedReason\", got ${sea.tests.failedReasons}",
        )
    }

    @Test fun `spread line-moved-against reason states the point move`() {
        val game = seaNeGame(line = MarketLine("DraftKings", homeSpread = 7.0, homeMoneyline = -170, awayMoneyline = 142, fetchedAtEpochMs = 1L))
        val now = kickoff - 1000L
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = now)
        val history = LineHistory(listOf(LineSnapshot(takenAtEpochMs = 0L, currentWeek = 1, lines = listOf(LineRecord(game.id, 1, homeSpread = -3.0, homeMoneyline = null, awayMoneyline = null, fpiHome = null)))))
        val betBoard = BettingEngine.board(season, UserState(), now, history)
        val spread = betBoard.games.single().markets.single { it.market == Market.SPREAD }
        val away = spread.sides.single { it.side == game.away.abbr }
        assertEquals(false, away.tests.lineNotAgainst)
        assertTrue(away.tests.failedReasons.contains("Line moved 10.0 pt against this side"))
    }

    @Test fun `line-moved-against reason and boardFresh reason report the actual numbers`() {
        val game = seaNeGame(line = MarketLine("DraftKings", homeSpread = 7.0, homeMoneyline = -170, awayMoneyline = 142, fetchedAtEpochMs = 1L))
        val now = kickoff - 1000L
        val staleAgeMs = 9L * 3_600_000L
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = now - staleAgeMs) // no board -> ESPN fallback, board 9h old
        val history = LineHistory(listOf(LineSnapshot(takenAtEpochMs = 0L, currentWeek = 1, lines = listOf(LineRecord(game.id, 1, homeSpread = -3.0, homeMoneyline = null, awayMoneyline = null, fpiHome = null)))))
        val betBoard = BettingEngine.board(season, UserState(), now, history)
        val spread = betBoard.games.single().markets.single { it.market == Market.SPREAD }
        val away = spread.sides.single { it.side == game.away.abbr } // moved 10 points against the away side (home went 7 -> -3 was the away number's origin)
        assertEquals(false, away.tests.lineNotAgainst)
        assertTrue(
            away.tests.failedReasons.any { it.contains("moved") && it.contains("against this side") },
            "expected a line-moved-against reason, got ${away.tests.failedReasons}",
        )
        assertFalse(away.tests.boardFresh)
        assertTrue(
            away.tests.failedReasons.any { it.contains("9") && it.contains("h old") },
            "expected a board-age reason mentioning 9h, got ${away.tests.failedReasons}",
        )
    }

    @Test fun `a non-positive edge zeroes kellyFraction and expectedGrowth and lands in AVOID via the uncertainty penalty`() {
        // 9 books at plain -110/-110 with no better price anywhere -> fair 50%, best price -110, ev < 0.
        val paired = (1..9).flatMap { i -> listOf(Quote("B$i", Market.MONEYLINE, "SEA", null, -110), Quote("B$i", Market.MONEYLINE, "NE", null, -110)) }
        val game = seaNeGame()
        val now = kickoff - 1000L
        val gboard = GameBoard(home = game.home, away = game.away, commenceEpochMs = game.kickoffEpochMs, quotes = paired, fetchedAtEpochMs = now)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = now).copy(board = mapOf(game.id to gboard))
        val betBoard = BettingEngine.board(season, UserState(settings = ModelSettings(modelWeight = 0.0)), now)
        val ml = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }
        ml.sides.forEach { side ->
            assertTrue(side.lineShopEv!! < 0.0)
            assertEquals(0.0, side.kellyFraction, 1e-12)
            assertEquals(0.0, side.expectedGrowth, 1e-12) // 0.0 x a negative ev can land on -0.0 in IEEE 754
            assertEquals(0.0, side.components.growthPoints, 1e-12)
            assertEquals(10.0, side.components.uncertaintyPenalty) // edgeZ <= 0 -> null-confidence penalty
            assertFalse(side.tests.valueVsConsensus)
            assertFalse(side.goodBet)
            assertEquals(Tier.AVOID, side.tier)
        }
    }

    @Test fun `spread movement adjustment is clamped to plus-minus-4 points`() {
        val game = seaNeGame(line = MarketLine("DraftKings", homeSpread = 7.0, homeMoneyline = -170, awayMoneyline = 142, fetchedAtEpochMs = 1L))
        val season = Season(2026, listOf(game)) // no board -> ESPN fallback for spread
        val history = LineHistory(listOf(LineSnapshot(takenAtEpochMs = 0L, currentWeek = 1, lines = listOf(LineRecord(game.id, 1, homeSpread = -3.0, homeMoneyline = null, awayMoneyline = null, fpiHome = null)))))
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L, history)
        val spread = betBoard.games.single().markets.single { it.market == Market.SPREAD }
        val home = spread.sides.single { it.side == game.home.abbr }
        val away = spread.sides.single { it.side == game.away.abbr }
        assertEquals(10.0, home.lineMovePoints!!, 1e-9) // 7.0 - (-3.0)
        assertEquals(4.0, home.components.movementAdjustment, 1e-9) // clamp(10, -4, 4)
        assertEquals(-10.0, away.lineMovePoints!!, 1e-9)
        assertEquals(-4.0, away.components.movementAdjustment, 1e-9)
    }

    @Test fun `stale board penalty is 5 past 6 hours and 10 past 24 hours`() {
        val game = seaNeGame()
        val now = kickoff - 1000L
        fun penaltyAtAge(ageMs: Long): Double {
            val season = Season(2026, listOf(game), boardFetchedAtEpochMs = now - ageMs)
            val betBoard = BettingEngine.board(season, UserState(), now)
            return betBoard.games.single().markets.single { it.market == Market.MONEYLINE }.sides.first().components.staleBoardPenalty
        }
        assertEquals(0.0, penaltyAtAge(3L * 3_600_000L))
        assertEquals(5.0, penaltyAtAge(7L * 3_600_000L))
        assertEquals(10.0, penaltyAtAge(25L * 3_600_000L))
    }

    @Test fun `spread rationale never mentions a moneyline win probability`() {
        val game = seaNeGame(fpi = FpiProjection(0.75, 1L))
        val season = Season(2026, listOf(game))
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        val spread = betBoard.games.single().markets.single { it.market == Market.SPREAD }
        spread.sides.forEach { side ->
            assertTrue(side.rationale.contains("covering"), "expected 'covering' in: ${side.rationale}")
            assertTrue(!side.rationale.contains("win", ignoreCase = true), "unexpected 'win' in: ${side.rationale}")
        }
    }

    @Test fun `both sides are always present and best is the higher-scoring side`() {
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        val game = seaNeGame()
        val attached = OddsApiParser.attachBoard(listOf(game), boards)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = 5L).copy(board = attached)
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        val gameAssessment = betBoard.games.single()
        assertTrue(gameAssessment.markets.isNotEmpty())
        gameAssessment.markets.forEach { m ->
            assertEquals(2, m.sides.size)
            assertEquals(m.sides.maxBy { it.score }.side, m.best.side)
            assertTrue(m.sides.contains(m.best))
        }
    }

    @Test fun `ranks are 1 through N and unique within each market across all of the week's games`() {
        val g1 = seaNeGame()
        val g2 = seaNeGame(fpi = FpiProjection(0.6, 1L)).copy(id = "g2", home = Team.KC, away = Team.DEN)
        val g3 = seaNeGame(fpi = FpiProjection(0.4, 1L)).copy(id = "g3", home = Team.BUF, away = Team.MIA)
        val season = Season(2026, listOf(g1, g2, g3))
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        val mlRanks = betBoard.games.flatMap { it.markets.filter { m -> m.market == Market.MONEYLINE } }.map { it.rank }.sorted()
        assertEquals((1..3).toList(), mlRanks)
        val spreadRanks = betBoard.games.flatMap { it.markets.filter { m -> m.market == Market.SPREAD } }.map { it.rank }.sorted()
        assertEquals((1..3).toList(), spreadRanks)
    }

    @Test fun `no-board fallback still yields MONEYLINE and SPREAD assessments priced off the ESPN line`() {
        val game = seaNeGame(fpi = FpiProjection(0.75, 1L))
        val season = Season(2026, listOf(game)) // no board attached at all
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        val gameAssessment = betBoard.games.single()
        val ml = gameAssessment.markets.single { it.market == Market.MONEYLINE }
        assertEquals(2, ml.sides.size)
        assertTrue(ml.sides.all { it.bestBook == "DraftKings (ESPN)" && it.booksQuoting == 1 })
        val spread = gameAssessment.markets.single { it.market == Market.SPREAD }
        assertEquals(2, spread.sides.size)
        assertTrue(spread.sides.all { it.bestBook == "DraftKings (ESPN)" && it.bestPrice == -110 && it.booksQuoting == 1 })
        assertTrue(gameAssessment.markets.none { it.market == Market.TOTAL })
    }

    @Test fun `totals are absent without a board even when includeTotals is on`() {
        val game = seaNeGame()
        val season = Season(2026, listOf(game))
        val betBoard = BettingEngine.board(season, UserState(settings = ModelSettings(includeTotals = true)), kickoff - 1000L)
        assertTrue(betBoard.games.single().markets.none { it.market == Market.TOTAL })
    }

    @Test fun `every scheduled game of the week appears even with no markets priced`() {
        val game = seaNeGame(line = null, fpi = null)
        val season = Season(2026, listOf(game))
        val betBoard = BettingEngine.board(season, UserState(), kickoff - 1000L)
        assertEquals(1, betBoard.games.size)
        assertEquals(game.id, betBoard.games.single().gameId)
        assertTrue(betBoard.games.single().markets.isEmpty())
    }

    @Test fun `suggestedStake is zero for a negative blended EV and positive Kelly-sized otherwise`() {
        val boards = OddsApiParser.parseBoard(fixture("oddsapi_board.json"), 5L)
        val game = seaNeGame()
        val attached = OddsApiParser.attachBoard(listOf(game), boards)
        val season = Season(2026, listOf(game), boardFetchedAtEpochMs = 5L).copy(board = attached)
        val settings = ModelSettings()
        val betBoard = BettingEngine.board(season, UserState(settings = settings), kickoff - 1000L)
        val ml = betBoard.games.single().markets.single { it.market == Market.MONEYLINE }
        ml.sides.forEach { side ->
            val stake = betBoard.suggestedStake(side, settings)
            // suggestedStake is stakeFor(side.kellyFraction, settings) directly now (kellyFraction is
            // already coerced to >= 0 and built from blendedProbability - see docs/BETTING.md).
            if (side.kellyFraction <= 0.0) assertEquals(0.0, stake) else assertTrue(stake >= 0.0)
        }
    }
}

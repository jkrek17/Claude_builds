package com.survivor.app

import com.survivor.app.data.EspnClient
import com.survivor.app.data.HttpFetcher
import com.survivor.app.data.OddsApiClient
import com.survivor.app.data.RefreshStatus
import com.survivor.app.data.StateStore
import com.survivor.app.data.SurvivorRepository
import com.survivor.app.data.YahooClient
import com.survivor.engine.Adjustment
import com.survivor.engine.Bet
import com.survivor.engine.GameState
import com.survivor.engine.Market
import com.survivor.engine.ModelSettings
import com.survivor.engine.Strategy
import com.survivor.engine.Team
import com.survivor.engine.data.EspnEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Serves recorded ESPN, Odds API and Yahoo fixtures for every URL the clients ask for. */
private class FakeHttp(
    private val failPredictor: Boolean = false,
    private val oddsBody: String? = null,
    /** Response for the multi-market board URL (`markets=h2h,spreads,totals`), distinct from [oddsBody]'s
     *  single-market consensus URL so the two fetches can be tested independently. */
    private val boardBody: String? = null,
    private val failYahoo: Boolean = false,
) : HttpFetcher {
    val calls = mutableListOf<String>()
    private fun res(name: String) = javaClass.classLoader.getResource(name)!!.readText()
    override suspend fun get(url: String): String {
        calls += url
        return when {
            url == EspnEndpoints.CURRENT_SCOREBOARD -> res("espn_scoreboard_week1.json")
            url.contains("/scoreboard?") -> if (url.contains("week=18")) res("espn_scoreboard_final.json") else res("espn_scoreboard_week1.json")
            url.contains("/predictor") -> if (failPredictor) throw IOException("boom") else res("espn_predictor.json")
            url.contains("powerindex") -> res("espn_powerindex.json")
            url.contains("markets=h2h,spreads,totals") -> boardBody ?: throw IOException("401")
            url.contains("the-odds-api") -> oddsBody ?: throw IOException("401")
            url == YahooClient.URL -> if (failYahoo) throw IOException("yahoo boom") else res("yahoo_pickdistribution.html")
            else -> throw IOException("unexpected $url")
        }
    }
    // YahooClient fetches with headers; route it through the same logic above.
    override suspend fun get(url: String, headers: Map<String, String>): String = get(url)
}

class RepositoryTest {
    private fun repo(http: FakeHttp): Pair<SurvivorRepository, File> {
        val dir = Files.createTempDirectory("survivor").toFile()
        val file = File(dir, "state.json")
        val r = SurvivorRepository(StateStore(file), EspnClient(http) { 1000L }, OddsApiClient(http) { 1000L }, YahooClient(http), now = { 1000L }, computeDispatcher = Dispatchers.Unconfined)
        return r to file
    }

    @Test fun `full refresh loads schedule, ratings and projections and persists`() = runTest {
        val http = FakeHttp()
        val (r, file) = repo(http)
        assertNull(r.season)
        r.refreshNflData(includeProjections = true)
        assertTrue(r.refresh.value is RefreshStatus.Done, "status=${r.refresh.value}")
        val s = assertNotNull(r.season)
        assertEquals(2026, s.year)
        // 17 weeks × 2 games from the week-1 fixture + 1 final game from the week-18 fixture; ids collide across weeks so merge de-dupes by id.
        assertTrue(s.games.isNotEmpty())
        assertEquals(3, s.ratings.size)
        assertTrue(s.games.filter { it.state != GameState.FINAL }.all { it.fpi != null })
        assertTrue(s.games.first { it.state == GameState.FINAL }.fpi == null)
        assertEquals(1000L, s.oddsFetchedAtEpochMs)
        assertTrue(file.exists())
        // A fresh repository over the same file sees the same season.
        val again = SurvivorRepository(StateStore(file), EspnClient(http), OddsApiClient(http))
        assertEquals(s, again.season)
    }

    @Test fun `predictor failures do not fail the refresh`() = runTest {
        val (r, _) = repo(FakeHttp(failPredictor = true))
        r.refreshNflData(includeProjections = true)
        assertTrue(r.refresh.value is RefreshStatus.Done)
        assertTrue(r.season!!.games.all { it.fpi == null })
    }

    @Test fun `odds api key adds consensus lines and a bad key is reported without losing ESPN data`() = runTest {
        val odds = javaClass.classLoader.getResource("oddsapi_h2h.json")!!.readText()
        val (good, _) = repo(FakeHttp(oddsBody = odds))
        good.setOddsApiKey("abc")
        good.refreshNflData(includeProjections = false)
        assertNotNull(good.season!!.games.first { it.home == Team.SEA }.consensus)
        assertNotNull(good.season!!.consensusFetchedAtEpochMs)

        val (bad, _) = repo(FakeHttp(oddsBody = null))
        bad.setOddsApiKey("abc")
        bad.refreshNflData(includeProjections = false)
        val status = bad.refresh.value as RefreshStatus.Done
        assertTrue(status.message.contains("Odds API failed"))
        assertNotNull(bad.season)
    }

    @Test fun `picks, adjustments, settings and reset round-trip through the store`() = runTest {
        val (r, file) = repo(FakeHttp())
        r.refreshNflData(includeProjections = false)
        r.recordPick(1, Team.SEA)
        r.recordPick(1, Team.NE) // replaces
        r.setAdjustment(Adjustment(2, Team.BUF, injuryPoints = -2.0))
        r.setAdjustment(Adjustment(2, Team.BUF)) // empty → removed
        r.updateSettings(ModelSettings(strategy = Strategy.CONTRARIAN))
        r.setWeekOverride(3)
        val reloaded = SurvivorRepository(StateStore(file), EspnClient(FakeHttp()), OddsApiClient(FakeHttp()))
        assertEquals(listOf(Team.NE), reloaded.user.picks.map { it.team })
        assertTrue(reloaded.user.adjustments.isEmpty())
        assertEquals(Strategy.CONTRARIAN, reloaded.user.settings.strategy)
        assertEquals(3, reloaded.user.weekOverride)
        val eval = assertNotNull(reloaded.evaluate())
        assertEquals(3, eval.currentWeek)
        assertTrue(Team.NE in eval.usedTeams)

        r.reset(includeData = false)
        assertTrue(r.user.picks.isEmpty()); assertNotNull(r.season)
        r.reset(includeData = true)
        assertNull(r.season)
        assertNull(r.evaluate())
    }

    @Test fun `a refresh records one line snapshot and an unchanged reload adds none`() = runTest {
        val (r, file) = repo(FakeHttp())
        assertTrue(r.lineHistory.snapshots.isEmpty())

        r.refreshNflData(includeProjections = false)
        assertEquals(1, r.lineHistory.snapshots.size)
        val snap = r.lineHistory.snapshots.single()
        assertEquals(1000L, snap.takenAtEpochMs)
        assertTrue(snap.lines.isNotEmpty())

        // now() is fixed at 1000L for this repository, so a second refresh with identical lines is
        // both inside the minimum interval and unchanged, and should not add a snapshot.
        r.refreshNflData(includeProjections = false)
        assertEquals(1, r.lineHistory.snapshots.size)

        // Line history survives a reload from disk.
        val reloaded = SurvivorRepository(StateStore(file), EspnClient(FakeHttp()), OddsApiClient(FakeHttp()))
        assertEquals(r.lineHistory, reloaded.lineHistory)
        assertEquals(1, reloaded.lineHistory.snapshots.size)
    }

    @Test fun `a refresh stores Yahoo pick shares for the current week with their source and fetch time`() = runTest {
        val (r, _) = repo(FakeHttp())
        r.refreshNflData(includeProjections = false)
        val status = r.refresh.value as RefreshStatus.Done
        assertFalse(status.message.contains("unavailable"), "message=${status.message}")
        val season = assertNotNull(r.season)
        val week1Shares = assertNotNull(season.pickShares[1])
        assertTrue(week1Shares.isNotEmpty())
        assertEquals(Team.LAC, week1Shares.entries.maxByOrNull { it.value }?.key)
        assertEquals("Yahoo Survival Football (all Yahoo entries)", season.pickSharesSource)
        assertEquals(1000L, season.pickSharesFetchedAtEpochMs)
    }

    @Test fun `a Yahoo failure does not fail the refresh and leaves shares empty`() = runTest {
        val (r, _) = repo(FakeHttp(failYahoo = true))
        r.refreshNflData(includeProjections = false)
        val status = r.refresh.value as RefreshStatus.Done
        assertTrue(status.message.contains("Yahoo pick shares unavailable"), "message=${status.message}")
        val season = assertNotNull(r.season)
        assertTrue(season.pickShares.isEmpty())
        // ESPN data still updated despite the Yahoo failure.
        assertTrue(season.games.isNotEmpty())
    }

    @Test fun `refreshOdds fetches Yahoo pick shares too, after merging ESPN data`() = runTest {
        val (r, _) = repo(FakeHttp())
        r.refreshNflData(includeProjections = false)
        r.refreshOdds()
        assertTrue(r.refresh.value is RefreshStatus.Done)
        assertTrue(r.season!!.pickShares[1]?.isNotEmpty() == true)
    }

    @Test fun `a refresh with a key fetches and attaches the multi-book odds board`() = runTest {
        val boardJson = javaClass.classLoader.getResource("oddsapi_board.json")!!.readText()
        val (r, _) = repo(FakeHttp(boardBody = boardJson))
        r.setOddsApiKey("abc")
        r.refreshNflData(includeProjections = false)
        val status = r.refresh.value as RefreshStatus.Done
        assertFalse(status.message.contains("Odds board unavailable"), "message=${status.message}")
        val season = assertNotNull(r.season)
        assertTrue(season.board.isNotEmpty())
        val game = season.games.first { it.home == Team.SEA && it.away == Team.NE }
        val board = assertNotNull(season.board[game.id])
        assertTrue(board.quotes.isNotEmpty())
        assertEquals(1000L, season.boardFetchedAtEpochMs)
    }

    @Test fun `the odds board is throttled to once every 3 hours unless forced`() = runTest {
        val boardJson = javaClass.classLoader.getResource("oddsapi_board.json")!!.readText()
        val http = FakeHttp(boardBody = boardJson)
        val (r, _) = repo(http)
        r.setOddsApiKey("abc")

        r.refreshNflData(includeProjections = false)
        assertEquals(1, http.calls.count { it.contains("markets=h2h,spreads,totals") })

        // now() is fixed at 1000L for this repository, so the board is always "0 ms old" - well inside the
        // 3-hour throttle - and a plain refresh should not fetch it again.
        r.refreshOdds()
        assertEquals(1, http.calls.count { it.contains("markets=h2h,spreads,totals") }, "a refresh inside the 3-hour window should not refetch the board")

        // The standalone "Refresh odds board" action forces a fetch regardless of age.
        r.refreshOddsBoard(force = true)
        assertEquals(2, http.calls.count { it.contains("markets=h2h,spreads,totals") })
    }

    @Test fun `recordBet and deleteBet persist and reload`() = runTest {
        val (r, file) = repo(FakeHttp())
        r.refreshNflData(includeProjections = false)
        val game = r.season!!.games.first()
        val bet = Bet(
            id = "test-1", placedAtEpochMs = 1000L, gameId = game.id, week = game.week, market = Market.MONEYLINE,
            side = game.home.abbr, price = -150, stake = 25.0, book = "DraftKings",
        )
        r.recordBet(bet)
        assertEquals(listOf(bet), r.user.bets)

        val reloaded = SurvivorRepository(StateStore(file), EspnClient(FakeHttp()), OddsApiClient(FakeHttp()))
        assertEquals(listOf(bet), reloaded.user.bets)

        r.deleteBet(bet.id)
        assertTrue(r.user.bets.isEmpty())
        val reloadedAfterDelete = SurvivorRepository(StateStore(file), EspnClient(FakeHttp()), OddsApiClient(FakeHttp()))
        assertTrue(reloadedAfterDelete.user.bets.isEmpty())
    }
}

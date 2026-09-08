package com.survivor.app

import com.survivor.app.data.EspnClient
import com.survivor.app.data.HttpFetcher
import com.survivor.app.data.OddsApiClient
import com.survivor.app.data.RefreshStatus
import com.survivor.app.data.StateStore
import com.survivor.app.data.SurvivorRepository
import com.survivor.engine.Adjustment
import com.survivor.engine.GameState
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Serves recorded ESPN fixtures for every URL the clients ask for. */
private class FakeHttp(private val failPredictor: Boolean = false, private val oddsBody: String? = null) : HttpFetcher {
    val calls = mutableListOf<String>()
    private fun res(name: String) = javaClass.classLoader.getResource(name)!!.readText()
    override suspend fun get(url: String): String {
        calls += url
        return when {
            url == EspnEndpoints.CURRENT_SCOREBOARD -> res("espn_scoreboard_week1.json")
            url.contains("/scoreboard?") -> if (url.contains("week=18")) res("espn_scoreboard_final.json") else res("espn_scoreboard_week1.json")
            url.contains("/predictor") -> if (failPredictor) throw IOException("boom") else res("espn_predictor.json")
            url.contains("powerindex") -> res("espn_powerindex.json")
            url.contains("the-odds-api") -> oddsBody ?: throw IOException("401")
            else -> throw IOException("unexpected $url")
        }
    }
}

class RepositoryTest {
    private fun repo(http: FakeHttp): Pair<SurvivorRepository, File> {
        val dir = Files.createTempDirectory("survivor").toFile()
        val file = File(dir, "state.json")
        val r = SurvivorRepository(StateStore(file), EspnClient(http) { 1000L }, OddsApiClient(http) { 1000L }, now = { 1000L }, computeDispatcher = Dispatchers.Unconfined)
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
}

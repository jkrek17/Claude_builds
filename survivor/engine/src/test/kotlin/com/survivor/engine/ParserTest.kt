package com.survivor.engine

import com.survivor.engine.data.EspnParser
import com.survivor.engine.data.OddsApiParser
import com.survivor.engine.data.SavedState
import com.survivor.engine.data.SeasonMerge
import com.survivor.engine.data.StateCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {
    private fun fixture(name: String) = javaClass.classLoader.getResource(name)!!.readText()

    @Test fun `parses a scheduled week with DraftKings spread and moneyline`() {
        val page = EspnParser.parseScoreboard(fixture("espn_scoreboard_week1.json"), 99L)
        assertEquals(2026, page.seasonYear); assertEquals(1, page.week)
        assertEquals(2, page.games.size)
        val g = page.games.first { it.home == Team.SEA }
        assertEquals(Team.NE, g.away)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.homeScore)
        assertEquals(EspnParser.parseInstant("2026-09-10T00:20Z"), g.kickoffEpochMs)
        val line = assertNotNull(g.line)
        assertEquals("DraftKings", line.provider)
        assertEquals(-3.0, line.homeSpread)
        assertEquals(-170, line.homeMoneyline); assertEquals(142, line.awayMoneyline); assertEquals(99L, line.fetchedAtEpochMs)
    }

    @Test fun `parses a final game with scores`() {
        val page = EspnParser.parseScoreboard(fixture("espn_scoreboard_final.json"), 1L)
        val g = page.games.single()
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Team.TB, g.home); assertEquals(Team.CAR, g.away)
        assertEquals(16, g.homeScore); assertEquals(14, g.awayScore)
        assertEquals(Team.TB, g.winner)
    }

    @Test fun `parses the FPI predictor and power index`() {
        val fpi = assertNotNull(EspnParser.parsePredictor(fixture("espn_predictor.json"), 5L))
        assertEquals(0.61112, fpi.homeWinProbability, 1e-6)
        val ratings = EspnParser.parsePowerIndex(fixture("espn_powerindex.json"))
        assertEquals(3, ratings.size)
        assertEquals(5.854, ratings.first { it.team == Team.LAR }.fpi, 1e-9)
        assertEquals(1, ratings.first { it.team == Team.LAR }.rank)
    }

    @Test fun `instant parsing accepts ESPN's minute-precision timestamps and full ISO`() {
        assertEquals(EspnParser.parseInstant("2026-09-10T00:20:00Z"), EspnParser.parseInstant("2026-09-10T00:20Z"))
        assertEquals(100, EspnParser.parseAmerican("EVEN")); assertEquals(142, EspnParser.parseAmerican("+142")); assertEquals(-170, EspnParser.parseAmerican("-170"))
    }

    @Test fun `odds api consensus averages the no-vig probabilities and attaches to the ESPN game`() {
        val lines = OddsApiParser.parse(fixture("oddsapi_h2h.json"), 7L)
        assertEquals(1, lines.size)
        val l = lines.single()
        assertEquals(2, l.books)
        val expected = (Probability.noVig(-170, 142) + Probability.noVig(-160, 136)) / 2
        val stored = Probability.noVig(l.line.homeMoneyline!!, l.line.awayMoneyline!!)
        assertEquals(expected, stored, 0.01)
        val page = EspnParser.parseScoreboard(fixture("espn_scoreboard_week1.json"), 1L)
        val attached = OddsApiParser.attach(page.games, lines)
        assertNotNull(attached.first { it.home == Team.SEA }.consensus)
        assertNull(attached.first { it.home != Team.SEA }.consensus)
    }

    @Test fun `state round-trips through JSON and merge keeps FPI when the refresh lacks it`() {
        val season = TestSeason.build(2, finalsThroughWeek = 1)
        val user = UserState(picks = listOf(Pick(1, Team.KC)), adjustments = listOf(Adjustment(2, Team.BUF, injuryPoints = -1.5, estimatedPickShare = 0.3)), settings = ModelSettings(strategy = Strategy.BALANCED))
        val text = StateCodec.encode(SavedState(season, user, 5L))
        val back = StateCodec.decode(text)
        assertEquals(season, back.season); assertEquals(user, back.user)
        val stripped = season.games.map { it.copy(fpi = null, line = null) }
        val merged = SeasonMerge.mergeGames(season.games, stripped)
        assertTrue(merged.all { it.fpi != null && it.line != null })
        assertEquals(season.games.size, merged.size)
    }

    @Test fun `every team resolves from ESPN abbreviations and Odds API full names`() {
        for (t in Team.entries) {
            assertEquals(t, Team.fromAbbr(t.abbr))
            assertEquals(t, Team.fromFullName(t.fullName))
        }
        assertEquals(Team.WSH, Team.fromAbbr("WAS"))
        assertEquals(32, Team.entries.size)
        assertEquals(8, Team.entries.map { it.division }.toSet().size)
        assertTrue(Division.entries.all { d -> Team.entries.count { it.division == d } == 4 })
    }
}

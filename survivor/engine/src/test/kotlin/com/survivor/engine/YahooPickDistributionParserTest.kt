package com.survivor.engine

import com.survivor.engine.data.YahooPickDistributionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YahooPickDistributionParserTest {
    private fun fixture(name: String) = javaClass.classLoader.getResource(name)!!.readText()

    @Test fun `parses the real pick-distribution table fixture`() {
        val shares = YahooPickDistributionParser.parse(fixture("yahoo_pickdistribution.html"))
        assertTrue(shares.isNotEmpty())
        // Top team at fixture-recording time was LA Chargers at ~33.46%.
        val top = shares.entries.maxByOrNull { it.value }
        assertNotNull(top)
        assertEquals(Team.LAC, top.key)
        assertEquals(0.3346, shares.getValue(Team.LAC), 1e-3)
        // Rows are a real (trimmed) subset of the table, so the shares must never exceed the whole pool.
        assertTrue(shares.values.sum() <= 1.0 + 1e-9)
        assertTrue(shares.values.all { it in 0.0..1.0 })
    }

    @Test fun `parses the embedded current week`() {
        assertEquals(1, YahooPickDistributionParser.parseWeek(fixture("yahoo_pickdistribution.html")))
    }

    @Test fun `returns no shares and no week when the table and marker are absent`() {
        assertEquals(emptyMap(), YahooPickDistributionParser.parse("<html><body>nothing here</body></html>"))
        assertEquals(null, YahooPickDistributionParser.parseWeek("<html><body>nothing here</body></html>"))
    }

    @Test fun `every one of the 32 real Yahoo display names maps to a team`() {
        // The exact 32 display names recorded from the live page (city-style names; the two-team cities are
        // abbreviated "LA ..." / "NY ...").
        val yahooNames = listOf(
            "Buffalo" to Team.BUF, "Miami" to Team.MIA, "New England" to Team.NE, "NY Jets" to Team.NYJ,
            "Baltimore" to Team.BAL, "Cincinnati" to Team.CIN, "Cleveland" to Team.CLE, "Pittsburgh" to Team.PIT,
            "Houston" to Team.HOU, "Indianapolis" to Team.IND, "Jacksonville" to Team.JAX, "Tennessee" to Team.TEN,
            "Denver" to Team.DEN, "Kansas City" to Team.KC, "Las Vegas" to Team.LV, "LA Chargers" to Team.LAC,
            "Dallas" to Team.DAL, "NY Giants" to Team.NYG, "Philadelphia" to Team.PHI, "Washington" to Team.WSH,
            "Chicago" to Team.CHI, "Detroit" to Team.DET, "Green Bay" to Team.GB, "Minnesota" to Team.MIN,
            "Atlanta" to Team.ATL, "Carolina" to Team.CAR, "New Orleans" to Team.NO, "Tampa Bay" to Team.TB,
            "Arizona" to Team.ARI, "LA Rams" to Team.LAR, "San Francisco" to Team.SF, "Seattle" to Team.SEA,
        )
        assertEquals(32, yahooNames.size)
        assertEquals(Team.entries.toSet(), yahooNames.map { it.second }.toSet())
        for ((name, team) in yahooNames) {
            assertEquals(team, YahooPickDistributionParser.teamFromYahooName(name), "expected \"$name\" to map to $team")
        }
    }
}

package com.survivor.app

import com.survivor.app.ui.components.Fmt
import kotlin.test.Test
import kotlin.test.assertEquals

class FmtTest {
    @Test fun `formats spreads, moneylines and percentages the way the dashboard shows them`() {
        assertEquals("-7.5", Fmt.spread(-7.5)); assertEquals("+3", Fmt.spread(3.0)); assertEquals("PK", Fmt.spread(0.0)); assertEquals("—", Fmt.spread(null))
        assertEquals("-170", Fmt.ml(-170)); assertEquals("+142", Fmt.ml(142)); assertEquals("—", Fmt.ml(null))
        assertEquals("79%", Fmt.pct(0.791)); assertEquals("79.1%", Fmt.pct1(0.791))
        assertEquals("vs CLE", Fmt.matchup("CLE", true)); assertEquals("@ BUF", Fmt.matchup("BUF", false)); assertEquals("vs KC (N)", Fmt.matchup("KC", false, neutral = true))
        assertEquals("never", Fmt.age(null)); assertEquals("5 min ago", Fmt.age(1000L, 1000L + 5 * 60_000L)); assertEquals("2 d ago", Fmt.age(1000L, 1000L + 48 * 3_600_000L))
        assertEquals("+5.9", Fmt.rating(5.854)); assertEquals("-0.4", Fmt.rating(-0.36))
    }
}

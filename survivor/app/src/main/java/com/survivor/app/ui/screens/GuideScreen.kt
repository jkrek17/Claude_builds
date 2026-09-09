package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.theme.Spacing

@Composable
fun GuideScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionCard("Every week, in three steps") {
            Text(
                "1. Open the app and check Home. It already shows the recommended pick - nothing to refresh manually unless you want the very latest lines.\n" +
                    "2. Read \"Why this pick\" and the alternatives. If you know something the market doesn't (a late QB scratch), add it in More → Weekly Inputs.\n" +
                    "3. Tap \"Record\" on Home or in Picks. The whole rest-of-season plan re-optimizes instantly around that pick.",
            )
        }
        SectionCard("After the games finish") {
            Text("Refresh once games are final (pull the refresh icon in the top bar, or More → Survivor Tools → Refresh Odds). Your pick's result and strike count are read from the final score automatically, the team is marked used, and every remaining week is re-optimized. Nothing to type.")
        }
        SectionCard("Automatic updates") {
            Text(
                "On by default: the app checks for new lines and scores in the background once a day (3, 6 or 12 hours selectable) (More → Model Settings → Automatic updates sets how often) and notifies you when the recommended pick changes, a recorded pick's result comes in, or the weekend arrives with no pick recorded. Manual refresh still works exactly the same and is never required - it's just a way to check sooner.",
            )
        }
        SectionCard("What changes with pool size") {
            Text(
                "A 10-entry pool is usually decided within a few weeks - most entries take a bad beat early, so the model leans on protecting your strongest teams for the near term. A 250+-entry pool often runs the full 18 weeks, so it leans on surviving deep into the season instead. Set your pool size once in More → Model Settings → Your pool, and the recommendation, the season outlook, and the \"P(win the pool)\" number on Home all adjust automatically.",
            )
        }
        SectionCard("What \"Decision robustness\" means") {
            Text(
                "Lines move before kickoff, especially for teams several weeks out. The robustness row on Home reruns the season plan against many plausible versions of those future lines and reports how often today's recommended team is still the pick - \"chosen in 80% of scenarios\" means the recommendation is solid even if the lines shift; a low share (marked \"Toss-up\") means the pick is close and could flip on a small line move, so treat the alternatives as real options rather than a formality.",
            )
        }
        SectionCard("Where the numbers come from") {
            Text("Current week: DraftKings moneyline from ESPN with the vig removed (or a multi-book consensus if you add a free key from the-odds-api.com in Weekly Inputs). Future weeks: DraftKings lookahead spread blended with ESPN's FPI projection. A manual override in Weekly Inputs always wins. Every number on screen shows its source.")
        }
        SectionCard("Safety Score and grade") {
            Text("Win probability minus matchup risk (road, divisional, short rest, travel, QB flag), plus market confidence, minus the future value this pick costs you (how much the best remaining plan loses by burning this team now) and scarcity of its remaining premium spots. After a strike, future value counts far less, since this week's safety is what matters most. Tap any ranked team for the full breakdown.")
        }
        SectionCard("Season path and simulation") {
            Text("The optimizer finds the single best assignment of teams to remaining weeks - no team used twice - for whichever goal you've set in Model Settings → Strategy (winning the pool by default, or maximizing season survival, or expected weeks alive). Simulation has two views: a quick Monte Carlo comparison of fixed routes, and a closed-loop policy comparison that re-decides every week the way a real season plays out.")
        }
        SectionCard("Changing assumptions") {
            Text("Model Settings groups everything into Your pool, Strategy, and Advanced (every weight, collapsed by default, each with a description and a Restore defaults button). More → Survivor Tools → Reset Model clears picks and settings; \"Reset everything\" also clears downloaded data.")
        }
        SectionCard("Bets") {
            Text(
                "The Bets tab is entirely separate from your survivor pick and never changes it. Every scheduled game of the current week is graded on all three markets - moneyline, spread, total - both sides of each, whether or not either side is a good bet. Every side gets a 0-100 Bet Score, a letter grade, and a rank against every other game's same market this week; most sides of most markets score below break-even by design (that's the book's built-in vig), so a low score and an F/Avoid tier are the normal, correct result, not a bug.\n\n" +
                    "The score blends two signals that are never confused with each other: line shopping (comparing the best book's price to a no-vig consensus built from the rest of the market - a real, structural edge) and this engine's own model-vs-market view (the same FPI/market blend used for future survivor weeks) - a much noisier, speculative signal that only ever contributes a small, clearly-labeled share of the score (set in More → Model Settings → Betting → Model weight, 0 to ignore it entirely). Tap any row for the full breakdown: every component of the score, fair vs. best price, the model's probability and its break-even, how much the books disagree, and how far the line has moved since it was first tracked.\n\n" +
                    "Suggested stakes use fractional Kelly (quarter-Kelly by default) on your bankroll, capped at a hard percentage of it - both set in More → Model Settings → Betting. Recorded bets are graded automatically from final scores after a refresh - no separate step - and the Ledger shows your record, profit and ROI overall and split by market.\n\n" +
                    "Multiple books sharpen every fair price and unlock the total market and line-dispersion/movement components - add a free key from the-odds-api.com in More → Weekly Inputs. Without one, every game is still graded off the single ESPN/DraftKings line. Refreshing the multi-book board costs 3 requests against the API's monthly quota, so it's throttled to once every 3 hours automatically; a manual \"Refresh odds board\" on the Bets tab can force an earlier one.",
            )
        }
    }
}

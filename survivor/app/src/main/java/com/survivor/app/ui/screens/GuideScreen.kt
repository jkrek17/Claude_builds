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
import androidx.compose.ui.unit.dp
import com.survivor.app.ui.components.SectionCard

@Composable
fun GuideScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Before each week") {
            Text("1. Tap the refresh icon (or Survivor Tools → Refresh Odds). Lines, scores and strikes update.\n2. Check the Dashboard: recommended pick, why it is safe, what you give up, and 3 alternatives.\n3. If you know something the line does not (late QB scratch), add it in Weekly Inputs.\n4. Record your pick from the Dashboard or Picks screen. The route re-optimizes with that team locked.")
        }
        SectionCard("After the week") {
            Text("Refresh once games are final. Your pick's result and strike count are read from the score automatically, the team is marked used, and every remaining week is re-optimized. Nothing to type.")
        }
        SectionCard("Where the numbers come from") {
            Text("Current week: DraftKings moneyline from ESPN with the vig removed (or a multi-book consensus if you add a The Odds API key). Future weeks: DraftKings lookahead spread converted with a normal-margin model (σ = 13.45 points) blended 60/40 with ESPN's FPI game projection, then discounted 3% per week ahead inside the optimizer. Manual override beats everything. Every row shows its source.")
        }
        SectionCard("Safety Score (0–100)") {
            Text("Win % × 100, minus matchup risk (road, divisional, short rest, travel, QB flag), plus market confidence, minus future opportunity cost (how much the best remaining path loses if this team is burned now) and scarcity of premium future spots. After a strike, future value counts 40% as much and low-probability picks are penalised harder. Tap any ranking row to see the full breakdown.")
        }
        SectionCard("Season path") {
            Text("A Hungarian assignment on −ln(win %) finds the exact zero-loss-maximizing path (one team per week, each team once), then a local search climbs the true double-elimination objective P(0 losses) + P(exactly 1 loss). Locked picks are fixed; used teams are removed.")
        }
        SectionCard("Changing assumptions") {
            Text("Model Settings has every weight with a description. Strategy presets set the ownership leverage weight. Restore defaults at any time. Reset Model in the Survivor Tools menu clears picks and settings; \"Reset everything\" also clears downloaded data.")
        }
        SectionCard("Ownership / pool equity") {
            Text("Enter an estimated pick share per team in Weekly Inputs. Expected pool equity = your win % ÷ share of the pool that survives with you. Balanced and Contrarian add part of that leverage to the Safety Score; Max Pool Equity ranks by equity outright when shares exist.")
        }
    }
}

package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.data.RefreshStatus
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.BetFormat
import com.survivor.app.ui.EvTier
import com.survivor.app.ui.Routes
import com.survivor.app.ui.components.ChipRow
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.Stat
import com.survivor.app.ui.components.TeamLogo
import com.survivor.app.ui.components.TierBadge
import com.survivor.app.ui.theme.Spacing
import com.survivor.app.ui.theme.tierColor
import com.survivor.engine.Bet
import com.survivor.engine.BetPick
import com.survivor.engine.BetResult
import com.survivor.engine.BettingBoard
import com.survivor.engine.Game
import com.survivor.engine.Ledger
import com.survivor.engine.LedgerRow
import com.survivor.engine.LedgerTotals
import com.survivor.engine.Market
import com.survivor.engine.Signal
import com.survivor.engine.Tier
import com.survivor.engine.data.SavedState

/** ML/Spread/Total filter for the Bets board. [market] null means "no filter". */
private enum class MarketFilter(val label: String, val market: Market?) {
    ALL("All", null), ML("ML", Market.MONEYLINE), SPREAD("Spread", Market.SPREAD), TOTAL("Total", Market.TOTAL),
}

private val MIN_EV_CHOICES = listOf(0.01, 0.02, 0.03)

private fun tierFor(ev: Double): Tier = when (BetFormat.evTier(ev)) {
    EvTier.STRONG -> Tier.STRONG
    EvTier.MODERATE -> Tier.ACCEPTABLE
    EvTier.LOW -> Tier.RISKY
}

/**
 * Betting is entirely separate from the survivor recommendation: it never reads or changes [Pick][com.survivor.engine.Pick]
 * or the route optimizer, and nothing here feeds back into Home's hero card beyond the read-only teaser.
 */
@Composable
fun BetsScreen(vm: AppViewModel, onNavigate: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val board by vm.bettingBoard.collectAsStateWithLifecycle()
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    var marketFilter by remember { mutableStateOf(MarketFilter.ALL) }
    var minEv by remember { mutableDoubleStateOf(0.01) }
    var recordingPick by remember { mutableStateOf<BetPick?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    val season = state.season
    val allPicks = board?.picks.orEmpty()
    val filtered = allPicks.filter { (marketFilter.market == null || it.market == marketFilter.market) && it.ev >= minEv }
    val lineShop = filtered.filter { it.signal == Signal.LINE_SHOP }.sortedByDescending { it.ev }
    val model = filtered.filter { it.signal == Signal.MODEL }.sortedByDescending { it.ev }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        BetsHeaderCard(
            state = state, board = board, refreshing = refresh is RefreshStatus.Running,
            onNavigateInputs = { onNavigate(Routes.INPUTS) },
            onRefreshBoard = { vm.refreshOddsBoard(force = true) },
        )

        FilterRow(marketFilter, { marketFilter = it }, minEv, { minEv = it })

        SectionHeader("Line-shopping edges", "Best book's price vs. the rest of the market's own no-vig consensus - a real, structural edge.")
        if (lineShop.isEmpty()) EmptyState("No edges above your thresholds this week.")
        else lineShop.forEach { pick -> PickCard(pick, season?.games?.firstOrNull { it.id == pick.gameId }, speculative = false) { recordingPick = pick } }

        SectionHeader("Model disagreements", "This engine's own win-probability estimate vs. the market. Speculative - depends on FPI and the blended model, not on a price difference across books.")
        if (model.isEmpty()) EmptyState("No edges above your thresholds this week.")
        else model.forEach { pick -> PickCard(pick, season?.games?.firstOrNull { it.id == pick.gameId }, speculative = true) { recordingPick = pick } }

        LedgerSection(ledger) { pendingDeleteId = it }
    }

    recordingPick?.let { pick ->
        RecordBetDialog(pick, onDismiss = { recordingPick = null }) { bet -> vm.recordBet(bet); recordingPick = null }
    }
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete bet?") },
            text = { Text("This removes it from your ledger. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteBet(id); pendingDeleteId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BetsHeaderCard(
    state: SavedState,
    board: BettingBoard?,
    refreshing: Boolean,
    onNavigateInputs: () -> Unit,
    onRefreshBoard: () -> Unit,
) {
    val settings = state.user.settings
    val hasKey = state.user.oddsApiKey.isNotBlank()
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Week ${board?.week ?: state.season?.inferCurrentWeek(System.currentTimeMillis()) ?: 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (!hasKey || board == null || board.booksSeen == 0) {
            Text("Add a The Odds API key in Weekly Inputs to enable line shopping across books.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onNavigateInputs, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Weekly Inputs") }
        } else {
            Text("Books: ${board.booksSeen} · board updated ${Fmt.durationAgo(board.boardAgeMs)}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onRefreshBoard, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Refresh odds board") }
        }
        HorizontalDivider()
        Text(
            "Bankroll $${Fmt.num(settings.bankroll)} · ${Fmt.num(settings.kellyMultiplier * 100)}% Kelly · up to $${Fmt.num(settings.bankroll * settings.maxStakePct)} per bet",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Edges here are small and the model signal in particular is speculative - the market is usually right. Bet only what you can afford to lose.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FilterRow(market: MarketFilter, onMarket: (MarketFilter) -> Unit, minEv: Double, onMinEv: (Double) -> Unit) {
    SectionCard {
        Text("Market", style = MaterialTheme.typography.labelLarge)
        ChipRow {
            MarketFilter.entries.forEach { m -> FilterChip(selected = market == m, onClick = { onMarket(m) }, label = { Text(m.label) }) }
        }
        Text("Minimum EV", style = MaterialTheme.typography.labelLarge)
        ChipRow {
            MIN_EV_CHOICES.forEach { v -> FilterChip(selected = minEv == v, onClick = { onMinEv(v) }, label = { Text("${Fmt.num(v * 100)}%+") }) }
        }
    }
}

@Composable
private fun PickCard(pick: BetPick, game: Game?, speculative: Boolean, onRecord: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val sideTeam = pick.sideTeam
            when {
                sideTeam != null -> TeamLogo(sideTeam, 32.dp)
                game != null -> Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { TeamLogo(game.away, 24.dp); TeamLogo(game.home, 24.dp) }
            }
            Column(Modifier.weight(1f)) {
                Text(game?.let { "${it.away.abbr} @ ${it.home.abbr} · Wk ${pick.week}" } ?: "Week ${pick.week}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(BetFormat.marketLabel(pick.market, pick.side, pick.point), style = MaterialTheme.typography.titleMedium, fontWeight = if (speculative) FontWeight.Medium else FontWeight.Bold)
            }
            TierBadge(Fmt.evPct(pick.ev), tierFor(pick.ev))
        }
        if (speculative) AssistChip(onClick = {}, label = { Text("Speculative") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Best price", "${pick.bestBook}\n${Fmt.ml(pick.bestPrice)}")
            Stat("Fair price", "${Fmt.ml(pick.fairPrice)}\n${pick.consensusBooks} bk${if (pick.consensusBooks == 1) "" else "s"}")
            Stat("Stake", "$${Fmt.num(pick.stake)}")
        }
        Text(pick.rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) { Text("Record bet") }
    }
}

@Composable
private fun RecordBetDialog(pick: BetPick, onDismiss: () -> Unit, onSave: (Bet) -> Unit) {
    var book by remember { mutableStateOf(pick.bestBook) }
    var price by remember { mutableStateOf(pick.bestPrice.toString()) }
    var point by remember { mutableStateOf(pick.point?.let { Fmt.num(it) } ?: "") }
    var stake by remember { mutableStateOf(Fmt.num(pick.stake)) }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record bet") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("${pick.sideTeam?.abbr ?: pick.side} · ${BetFormat.marketLabel(pick.market, pick.side, pick.point)} · Week ${pick.week}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(book, { book = it }, label = { Text("Book") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(price, { price = it }, label = { Text("Price (American odds)") }, singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
                if (pick.market != Market.MONEYLINE) {
                    OutlinedTextField(point, { point = it }, label = { Text("Point") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(stake, { stake = it }, label = { Text("Stake (\$)") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Bet(
                        id = "${System.currentTimeMillis()}-${pick.gameId}-${pick.market}-${pick.side}",
                        placedAtEpochMs = System.currentTimeMillis(), gameId = pick.gameId, week = pick.week, market = pick.market,
                        side = pick.side, point = if (pick.market == Market.MONEYLINE) null else point.toDoubleOrNull(),
                        price = price.toIntOrNull() ?: pick.bestPrice, stake = stake.toDoubleOrNull() ?: pick.stake,
                        book = book.trim().ifBlank { pick.bestBook }, signal = pick.signal, note = pick.note,
                    ),
                )
            }) { Text("Record bet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LedgerSection(ledger: Ledger?, onDelete: (String) -> Unit) {
    SectionCard("Ledger") {
        if (ledger == null || ledger.rows.isEmpty()) {
            Text("No bets recorded yet. Record one from an edge above to start tracking it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val t = ledger.totals
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Staked", "$${Fmt.num(t.staked)}")
            Stat("Profit", "${if (t.profit >= 0) "+" else ""}$${Fmt.num(t.profit)}", color = tierColor(if (t.profit >= 0) Tier.STRONG else Tier.AVOID))
            Stat("ROI", t.roi?.let { Fmt.evPct(it) } ?: "—")
            Stat("Record", t.record)
        }
        HorizontalDivider()
        val signalLabel = mapOf(Signal.LINE_SHOP to "Line shop", Signal.MODEL to "Model")
        BreakdownTable("By signal", ledger.bySignal.mapKeys { signalLabel[it.key] ?: it.key.label })
        BreakdownTable("By market", ledger.byMarket.mapKeys { it.key.label })
        HorizontalDivider()
        Text("Bets", style = MaterialTheme.typography.titleSmall)
        ledger.rows.sortedByDescending { it.bet.placedAtEpochMs }.forEach { row -> BetRow(row) { onDelete(row.bet.id) } }
    }
}

@Composable
private fun BreakdownTable(title: String, byLabel: Map<String, LedgerTotals>) {
    if (byLabel.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    byLabel.forEach { (label, t) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(t.record, style = MaterialTheme.typography.bodySmall)
            Text("$${Fmt.num(t.staked)} staked", style = MaterialTheme.typography.bodySmall)
            Text("${if (t.profit >= 0) "+" else ""}$${Fmt.num(t.profit)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BetRow(row: LedgerRow, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Wk ${row.bet.week} · ${BetFormat.marketLabel(row.bet.market, row.bet.side, row.bet.point)} · ${row.bet.book} ${Fmt.ml(row.bet.price)}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Stake $${Fmt.num(row.bet.stake)}" + (row.closingLineValue?.let { " · CLV ${Fmt.signed(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                ResultChip(row.result)
                Text("${if (row.profit >= 0) "+" else ""}$${Fmt.num(row.profit)}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Delete, contentDescription = "Delete bet") }
        }
    }
    HorizontalDivider(thickness = 0.5.dp)
}

@Composable
private fun ResultChip(result: BetResult) {
    val (label, color) = when (result) {
        BetResult.WIN -> "Win" to tierColor(Tier.STRONG)
        BetResult.LOSS -> "Loss" to tierColor(Tier.AVOID)
        BetResult.PUSH -> "Push" to tierColor(Tier.ACCEPTABLE)
        BetResult.PENDING -> "Pending" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
}

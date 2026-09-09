package com.survivor.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.data.RefreshStatus
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.BetFormat
import com.survivor.app.ui.Routes
import com.survivor.app.ui.components.Col
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Expandable
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.HTable
import com.survivor.app.ui.components.KeyValue
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.Stat
import com.survivor.app.ui.components.TeamLogo
import com.survivor.app.ui.components.TierBadge
import com.survivor.app.ui.theme.Spacing
import com.survivor.app.ui.theme.tierColor
import com.survivor.app.ui.theme.tierContainer
import com.survivor.engine.Bet
import com.survivor.engine.BetBoard
import com.survivor.engine.BetResult
import com.survivor.engine.GameAssessment
import com.survivor.engine.Ledger
import com.survivor.engine.LedgerRow
import com.survivor.engine.LedgerTotals
import com.survivor.engine.Market
import com.survivor.engine.MarketAssessment
import com.survivor.engine.ModelSettings
import com.survivor.engine.Probability
import com.survivor.engine.SideAssessment
import com.survivor.engine.Tier
import com.survivor.engine.data.SavedState
import java.util.Locale

private val E = TextAlign.End

/** Market tab across the top of the Bets board. [market] null means "All" (top plays + every game). */
private enum class MarketTab(val label: String, val market: Market?) {
    ALL("All", null), MONEYLINE("Moneyline", Market.MONEYLINE), SPREAD("Spread", Market.SPREAD), TOTAL("Total", Market.TOTAL),
}

/** Everything needed to build a [Bet] from a tapped [SideAssessment], carried from the detail sheet to
 *  the record dialog since a bare [SideAssessment] doesn't know its own game or market. */
private data class RecordingContext(val game: GameAssessment, val market: Market, val side: SideAssessment, val week: Int, val suggestedStake: Double)

/** A market row's blended EV, recovered from the exact terms [BetBoard.suggestedStake] and the Bet
 *  Score itself are built from - see [com.survivor.engine.BetScoreComponents]. */
private fun SideAssessment.blendedEv(): Double = (components.lineShopEvPct + components.modelEvPct) / 100.0

/**
 * Betting is entirely separate from the survivor recommendation: it never reads or changes [Pick][com.survivor.engine.Pick]
 * or the route optimizer, and nothing here feeds back into Home's hero card beyond the read-only teaser.
 * Every SCHEDULED game of the current week is graded here - a Bet Score (0-100), letter grade and
 * per-market rank for both sides of moneyline, spread and total - not just the sides that clear an edge
 * threshold, so a weak or -EV side reads as exactly that (an F, Avoid tier) rather than being hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BetsScreen(vm: AppViewModel, onNavigate: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val board by vm.betBoard.collectAsStateWithLifecycle()
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(MarketTab.ALL) }
    var tableView by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<GameAssessment, MarketAssessment>?>(null) }
    var recording by remember { mutableStateOf<RecordingContext?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val settings = state.user.settings

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        BetsHeaderCard(
            state = state, board = board, refreshing = refresh is RefreshStatus.Running,
            onNavigateInputs = { onNavigate(Routes.INPUTS) },
            onRefreshBoard = { vm.refreshOddsBoard(force = true) },
        )

        SingleChoiceSegmentedButtonRow {
            MarketTab.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = tab == t, onClick = { tab = t },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = MarketTab.entries.size),
                    label = { Text(t.label) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Week ${board?.week ?: state.season?.inferCurrentWeek(System.currentTimeMillis()) ?: 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !tableView, onClick = { tableView = false }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(active = !tableView) { Icon(Icons.Filled.ViewAgenda, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize)) } },
                    label = { Text("Cards") },
                )
                SegmentedButton(
                    selected = tableView, onClick = { tableView = true }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(active = tableView) { Icon(Icons.Filled.TableRows, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize)) } },
                    label = { Text("Table") },
                )
            }
        }

        val b = board
        if (b == null || b.games.isEmpty()) {
            EmptyState("No scheduled games for the current week yet. Download NFL data from the Dashboard.")
        } else if (tab == MarketTab.ALL) {
            val top = b.topPicks(8)
            Text("Top plays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (top.isEmpty()) EmptyState("No priced markets for this week yet.")
            else if (tableView) TopPlaysTable(b, top) { g, m -> detail = g to m }
            else SectionCard { top.forEachIndexed { i, side -> TopPlayRow(i + 1, b, side) { g, m -> detail = g to m }; if (i < top.lastIndex) HorizontalDivider() } }
            Text("All games", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            b.games.sortedBy { it.kickoffEpochMs }.forEach { g -> GameSummaryCard(g) { m -> detail = g to m } }
        } else {
            val market = tab.market!!
            val rows = b.games.mapNotNull { g -> g.markets.firstOrNull { it.market == market }?.let { g to it } }.sortedBy { it.second.rank }
            if (rows.isEmpty()) EmptyState("No ${tab.label.lowercase(Locale.US)} lines for this week yet.")
            else if (tableView) MarketFullTable(rows) { g, m -> detail = g to m }
            else SectionCard { rows.forEachIndexed { i, (g, m) -> MarketRow(g, m) { detail = g to m }; if (i < rows.lastIndex) HorizontalDivider() } }
        }

        LedgerSection(ledger) { pendingDeleteId = it }
    }

    val currentBoard = board
    if (currentBoard != null) {
        detail?.let { (game, market) ->
            ModalBottomSheet(onDismissRequest = { detail = null }) {
                MarketDetailSheet(game, market, currentBoard, settings) { side ->
                    recording = RecordingContext(game, market.market, side, currentBoard.week, currentBoard.suggestedStake(side, settings))
                    detail = null
                }
            }
        }
    }
    recording?.let { ctx ->
        RecordBetDialog(ctx, onDismiss = { recording = null }) { bet -> vm.recordBet(bet); recording = null }
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
private fun BetsHeaderCard(state: SavedState, board: BetBoard?, refreshing: Boolean, onNavigateInputs: () -> Unit, onRefreshBoard: () -> Unit) {
    val settings = state.user.settings
    val hasKey = state.user.oddsApiKey.isNotBlank()
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Bets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (!hasKey || board == null || board.booksSeen == 0) {
            Text("Add a The Odds API key in Weekly Inputs for multi-book fair prices and dispersion - every game is still graded off the ESPN/DraftKings line without one.", style = MaterialTheme.typography.bodyMedium)
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
            "Every side of every market is graded, most are below break-even by design (the book's vig). The model share of the score is small and speculative - the market is usually right. Bet only what you can afford to lose.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GameHeader(game: GameAssessment) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TeamLogo(game.away, 20.dp); TeamLogo(game.home, 20.dp)
    }
}

@Composable
private fun TopPlayRow(rank: Int, board: BetBoard, side: SideAssessment, onSelect: (GameAssessment, MarketAssessment) -> Unit) {
    val (game, market) = board.locate(side) ?: return
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(game, market) }.padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("$rank", Modifier.padding(end = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        side.sideTeam?.let { TeamLogo(it, 32.dp) } ?: GameHeader(game)
        Column(Modifier.weight(1f)) {
            Text(BetFormat.marketLabel(market.market, side.side, side.point), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text("${game.away.abbr} @ ${game.home.abbr} · ${market.market.label} · ${side.bestBook} ${Fmt.ml(side.bestPrice)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            TierBadge(side.grade, side.tier)
            Text(Fmt.evPct(side.blendedEv()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MarketRow(game: GameAssessment, market: MarketAssessment, onSelect: () -> Unit) {
    val side = market.best
    Column(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("#${market.rank}", Modifier.padding(end = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            side.sideTeam?.let { TeamLogo(it, 32.dp) } ?: GameHeader(game)
            Column(Modifier.weight(1f)) {
                Text(BetFormat.marketLabel(market.market, side.side, side.point), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text("${game.away.abbr} @ ${game.home.abbr} · ${Fmt.dateTime(game.kickoffEpochMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                TierBadge(side.grade, side.tier)
                Text(Fmt.evPct(side.blendedEv()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "${side.bestBook} ${Fmt.ml(side.bestPrice)} · fair ${Fmt.ml(side.fairPrice)} · ${side.booksQuoting} book${if (side.booksQuoting == 1) "" else "s"} · ${BetFormat.movedLabel(market.market, side.lineMovePoints)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GameSummaryCard(game: GameAssessment, onSelectMarket: (MarketAssessment) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            GameHeader(game)
            Column(Modifier.weight(1f)) {
                Text("${game.away.abbr} @ ${game.home.abbr}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(Fmt.dateTime(game.kickoffEpochMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (game.markets.isEmpty()) {
            Text("No priced markets for this game yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            game.markets.sortedBy { it.market.ordinal }.forEach { m ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { onSelectMarket(m) }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.market.label, style = MaterialTheme.typography.labelLarge)
                        Text(BetFormat.marketLabel(m.market, m.best.side, m.best.point), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("#${m.rank}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TierBadge(m.best.grade, m.best.tier)
                }
            }
        }
    }
}

@Composable
private fun TopPlaysTable(board: BetBoard, top: List<SideAssessment>, onSelect: (GameAssessment, MarketAssessment) -> Unit) {
    val located = top.map { s -> board.locate(s) to s }.filter { it.first != null }
    HTable(
        columns = listOf(Col("#", 26.dp), Col("Side", 90.dp), Col("Game", 90.dp), Col("Market", 62.dp), Col("Book", 90.dp), Col("Price", 52.dp, E), Col("Fair", 52.dp, E), Col("EV%", 52.dp, E), Col("Score", 48.dp, E), Col("Grade", 44.dp)),
        rows = located.mapIndexed { i, (gm, side) ->
            val (g, m) = gm!!
            listOf("${i + 1}", BetFormat.marketLabel(m.market, side.side, side.point), "${g.away.abbr}@${g.home.abbr}", m.market.label, side.bestBook, Fmt.ml(side.bestPrice), Fmt.ml(side.fairPrice), Fmt.evPct(side.blendedEv()), Fmt.score(side.score), side.grade)
        },
        rowColor = { i -> tierContainer(located[i].second.tier).copy(alpha = 0.55f) },
        onRowClick = { i -> located[i].first?.let { (g, m) -> onSelect(g, m) } },
    )
}

@Composable
private fun MarketFullTable(rows: List<Pair<GameAssessment, MarketAssessment>>, onSelect: (GameAssessment, MarketAssessment) -> Unit) {
    val expanded = rows.flatMap { (g, m) -> m.sides.map { s -> Triple(g, m, s) } }
    HTable(
        columns = listOf(
            Col("#", 26.dp), Col("Side", 90.dp), Col("Game", 90.dp), Col("Book", 90.dp), Col("Price", 52.dp, E), Col("Fair", 52.dp, E),
            Col("Books", 48.dp, E), Col("EV%", 52.dp, E), Col("Model%", 56.dp, E), Col("Moved", 60.dp, E), Col("Score", 48.dp, E), Col("Grade", 44.dp),
        ),
        rows = expanded.map { (g, m, s) ->
            listOf(
                "${m.rank}", BetFormat.marketLabel(m.market, s.side, s.point), "${g.away.abbr}@${g.home.abbr}", s.bestBook, Fmt.ml(s.bestPrice),
                Fmt.ml(s.fairPrice), "${s.booksQuoting}", Fmt.evPct(s.blendedEv()), s.modelProbability?.let { Fmt.pct(it) } ?: "—",
                BetFormat.movedLabel(m.market, s.lineMovePoints), Fmt.score(s.score), s.grade,
            )
        },
        rowColor = { i -> tierContainer(expanded[i].third.tier).copy(alpha = 0.55f) },
        onRowClick = { i -> val (g, m, _) = expanded[i]; onSelect(g, m) },
    )
}

@Composable
private fun MarketDetailSheet(game: GameAssessment, market: MarketAssessment, board: BetBoard, settings: ModelSettings, onRecord: (SideAssessment) -> Unit) {
    Column(Modifier.padding(Spacing.md).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TeamLogo(game.away, 36.dp); TeamLogo(game.home, 36.dp)
            Column {
                Text("${game.away.fullName} @ ${game.home.fullName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${market.market.label} · ${Fmt.dateTime(game.kickoffEpochMs)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            market.sides.forEach { side -> SideSummary(side, Modifier.weight(1f)) }
        }
        HorizontalDivider()
        market.sides.forEach { side ->
            SideBreakdown(market.market, side, board.suggestedStake(side, settings), startExpanded = side == market.best) { onRecord(side) }
        }
    }
}

@Composable
private fun SideSummary(side: SideAssessment, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        side.sideTeam?.let { TeamLogo(it, 32.dp) }
        Text(side.side, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text("${side.bestBook}\n${Fmt.ml(side.bestPrice)}", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TierBadge(side.grade, side.tier)
        Text(Fmt.score(side.score), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SideBreakdown(market: Market, side: SideAssessment, suggestedStake: Double, startExpanded: Boolean, onRecord: () -> Unit) {
    val c = side.components
    Expandable(title = BetFormat.marketLabel(market, side.side, side.point), subtitle = "Bet Score ${Fmt.score(side.score)} (${side.grade})", startExpanded = startExpanded) {
        Text("Bet Score breakdown", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        KeyValue("Base (blended EV x 12.5 + 50)", Fmt.num(c.base))
        KeyValue("  Line-shop EV", Fmt.evPct(c.lineShopEvPct / 100.0))
        KeyValue("  Model EV (weighted, speculative)", Fmt.evPct(c.modelEvPct / 100.0))
        KeyValue("Book confidence penalty", "-${Fmt.num(c.bookConfidencePenalty)}")
        KeyValue("Line dispersion penalty", "-${Fmt.num(c.dispersionPenalty)}")
        KeyValue("Line movement", Fmt.signed(c.movementAdjustment))
        KeyValue("Stale board penalty", "-${Fmt.num(c.staleBoardPenalty)}")
        HorizontalDivider()
        KeyValue("Score", Fmt.score(side.score))
        HorizontalDivider()
        Text("Pricing", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        KeyValue("Best price", "${side.bestBook} ${Fmt.ml(side.bestPrice)}")
        KeyValue("Fair price", "${Fmt.ml(side.fairPrice)} · ${side.booksQuoting} book${if (side.booksQuoting == 1) "" else "s"}")
        KeyValue("Break-even", Fmt.pct(Probability.impliedFromAmerican(side.bestPrice)))
        side.modelProbability?.let { mp ->
            KeyValue(if (market == Market.SPREAD) "Model cover %" else "Model win %", Fmt.pct(mp))
        }
        KeyValue("Line dispersion (sd of no-vig %)", side.lineDispersion?.let { Fmt.pct1(it) } ?: "—")
        KeyValue("Line move", BetFormat.movedLabel(market, side.lineMovePoints))
        HorizontalDivider()
        Text(side.rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) { Text("Record bet (suggested $${Fmt.num(suggestedStake)})") }
    }
}

@Composable
private fun RecordBetDialog(ctx: RecordingContext, onDismiss: () -> Unit, onSave: (Bet) -> Unit) {
    var book by remember { mutableStateOf(ctx.side.bestBook) }
    var price by remember { mutableStateOf(ctx.side.bestPrice.toString()) }
    var point by remember { mutableStateOf(ctx.side.point?.let { Fmt.num(it) } ?: "") }
    var stake by remember { mutableStateOf(Fmt.num(ctx.suggestedStake)) }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record bet") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("${BetFormat.marketLabel(ctx.market, ctx.side.side, ctx.side.point)} · Week ${ctx.week}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(book, { book = it }, label = { Text("Book") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(price, { price = it }, label = { Text("Price (American odds)") }, singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
                if (ctx.market != Market.MONEYLINE) {
                    OutlinedTextField(point, { point = it }, label = { Text("Point") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(stake, { stake = it }, label = { Text("Stake (\$)") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Bet(
                        id = "${System.currentTimeMillis()}-${ctx.game.gameId}-${ctx.market}-${ctx.side.side}",
                        placedAtEpochMs = System.currentTimeMillis(), gameId = ctx.game.gameId, week = ctx.week, market = ctx.market,
                        side = ctx.side.side, point = if (ctx.market == Market.MONEYLINE) null else point.toDoubleOrNull(),
                        price = price.toIntOrNull() ?: ctx.side.bestPrice, stake = stake.toDoubleOrNull() ?: ctx.suggestedStake,
                        book = book.trim().ifBlank { ctx.side.bestBook }, signal = null,
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
            Text("No bets recorded yet. Record one from a game above to start tracking it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Finds the [GameAssessment]/[MarketAssessment] a [side] (e.g. from [BetBoard.topPicks]) came from,
 *  by reference - [side] must be a value actually contained in this same [BetBoard] instance. */
private fun BetBoard.locate(side: SideAssessment): Pair<GameAssessment, MarketAssessment>? {
    for (g in games) for (m in g.markets) if (m.sides.any { it === side }) return g to m
    return null
}

package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.TeaserFormat
import com.survivor.app.ui.components.EmptyState
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.KeyValue
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.Stat
import com.survivor.app.ui.components.TeamLogo
import com.survivor.app.ui.components.TierBadge
import com.survivor.app.ui.theme.Spacing
import com.survivor.app.ui.theme.tierColor
import com.survivor.engine.BetResult
import com.survivor.engine.TeaserBet
import com.survivor.engine.TeaserCandidate
import com.survivor.engine.TeaserLeg
import com.survivor.engine.TeaserLedger
import com.survivor.engine.Teasers
import com.survivor.engine.Tier

/**
 * Wong teasers: a separate opportunity set from the Bets screen's straight-bet board, built around the
 * empirically strong 6-point windows (favorites -7.5..-8.5, underdogs +1.5..+2.5 - see [Teasers] and
 * docs/TEASERS.md) rather than the symmetric margin model. Reached from a "Teasers" button on Bets.
 */
@Composable
fun TeasersScreen(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val board by vm.teaserBoard.collectAsStateWithLifecycle()
    val ledger by vm.teaserLedger.collectAsStateWithLifecycle()
    val settings = state.user.settings
    var recording by remember { mutableStateOf<Pair<TeaserCandidate, Int>?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        TeasersHeaderCard(board?.legs?.size ?: 0, settings.teaserPrice) { price -> vm.updateSettings(settings.copy(teaserPrice = price)) }

        val b = board
        if (b == null) {
            EmptyState("No scheduled games for the current week yet. Download NFL data from the Dashboard.")
        } else {
            val recommended = b.candidates.filter { it.ev > 0.0 }
            val others = b.candidates.filter { it.ev <= 0.0 }
            Text("Recommended teasers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (recommended.isEmpty()) {
                Text(
                    when {
                        b.candidates.isEmpty() -> b.note ?: "No two-game pairings this week."
                        else -> "No teaser recommended this week: the best pairing is ${String.format(java.util.Locale.US, "%+.1f", b.candidates.first().ev * 100)}% EV at ${b.candidates.first().price}. " +
                            "Favorite-only pairings need about -110 to clear break-even; the edge lives mostly in the underdog window."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SectionCard {
                    recommended.take(10).forEachIndexed { i, c ->
                        CandidateRow(c) { recording = c to b.week }
                        if (i < minOf(recommended.size, 10) - 1) HorizontalDivider()
                    }
                }
            }
            if (others.isNotEmpty()) {
                Text("Pairings below break-even (not recommended)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionCard {
                    others.take(10).forEachIndexed { i, c ->
                        CandidateRow(c) { recording = c to b.week }
                        if (i < minOf(others.size, 10) - 1) HorizontalDivider()
                    }
                }
            }

            Text("All qualifying legs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (b.legs.isEmpty()) {
                Text("No legs in the favorite (-7.5..-8.5) or underdog (+1.5..+2.5) windows this week.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SectionCard {
                    b.legs.forEachIndexed { i, leg -> LegRow(leg); if (i < b.legs.lastIndex) HorizontalDivider() }
                }
            }
        }

        TeaserLedgerSection(ledger) { pendingDeleteId = it }
    }

    recording?.let { (c, week) ->
        RecordTeaserDialog(c, week, onDismiss = { recording = null }) { bet -> vm.recordTeaser(bet); recording = null }
    }
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete teaser?") },
            text = { Text("This removes it from your ledger. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteTeaser(id); pendingDeleteId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TeasersHeaderCard(qualifyingLegs: Int, teaserPrice: Int, onPriceChange: (Int) -> Unit) {
    var priceText by remember(teaserPrice) { mutableStateOf(teaserPrice.toString()) }
    SectionCard {
        Text("Teasers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "$qualifyingLegs qualifying leg${if (qualifyingLegs == 1) "" else "s"} this week - 6-point favorite (-7.5..-8.5) and underdog (+1.5..+2.5) windows only. Priced from their own empirical win rate, not the margin model - see the Teasers guide.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = priceText, onValueChange = { priceText = it }, label = { Text("Your teaser price") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            )
            Button(onClick = { priceText.toIntOrNull()?.let(onPriceChange) }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Save") }
        }
        val breakEven = Teasers.breakEvenLegRate(teaserPrice)
        KeyValue("Break-even per leg at ${Fmt.ml(teaserPrice)}", Fmt.pct1(breakEven))
    }
}

@Composable
private fun CandidateRow(c: TeaserCandidate, onSelect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            c.legs.forEach { leg -> TeamLogo(leg.team, 28.dp) }
            Column(Modifier.weight(1f)) {
                Text(c.legs.joinToString(" + ") { TeaserFormat.legLine(it) }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${Fmt.pct(c.winProbability)} win · ${Fmt.evPct(c.ev)} EV · ${Fmt.ml(c.price)} · stake $${Fmt.num(c.stake)}" +
                        if (c.legs.any { it.highTotal }) " · high total" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TierBadge(c.grade, c.tier)
        }
        Button(onClick = onSelect, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)) { Text("Record this teaser") }
    }
}

@Composable
private fun LegRow(leg: TeaserLeg) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TeamLogo(leg.team, 28.dp)
        Column(Modifier.weight(1f)) {
            Text("${TeaserFormat.legLine(leg)} (${TeaserFormat.windowLabel(leg.window)})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(TeaserFormat.legRateLine(leg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecordTeaserDialog(candidate: TeaserCandidate, week: Int, onDismiss: () -> Unit, onSave: (TeaserBet) -> Unit) {
    var price by remember { mutableStateOf(candidate.price.toString()) }
    var stake by remember { mutableStateOf(Fmt.num(candidate.stake)) }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record teaser") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(candidate.legs.joinToString(" + ") { TeaserFormat.legLine(it) }, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(price, { price = it }, label = { Text("Price (American odds)") }, singleLine = true, keyboardOptions = numeric, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(stake, { stake = it }, label = { Text("Stake (\$)") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    TeaserBet(
                        id = "${System.currentTimeMillis()}-${candidate.legs.joinToString("-") { it.gameId }}",
                        placedAtEpochMs = System.currentTimeMillis(), week = week, legs = candidate.legs,
                        price = price.toIntOrNull() ?: candidate.price, stake = stake.toDoubleOrNull() ?: candidate.stake,
                    ),
                )
            }) { Text("Record teaser") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TeaserLedgerSection(ledger: TeaserLedger?, onDelete: (String) -> Unit) {
    SectionCard("Teaser ledger") {
        if (ledger == null || ledger.rows.isEmpty()) {
            Text("No teasers recorded yet. Record one from a recommendation above to start tracking it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        ledger.rows.sortedByDescending { it.bet.placedAtEpochMs }.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(Modifier.weight(1f)) {
                    Text(row.bet.legs.joinToString(" + ") { TeaserFormat.legLine(it) }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("Stake $${Fmt.num(row.bet.stake)} · ${Fmt.ml(row.bet.price)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    TeaserResultChip(row.result)
                    Text("${if (row.profit >= 0) "+" else ""}$${Fmt.num(row.profit)}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { onDelete(row.bet.id) }, modifier = Modifier.padding(start = 4.dp)) { Icon(Icons.Filled.Delete, contentDescription = "Delete teaser") }
            }
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
private fun TeaserResultChip(result: BetResult) {
    val (label, color) = when (result) {
        BetResult.WIN -> "Win" to tierColor(Tier.STRONG)
        BetResult.LOSS -> "Loss" to tierColor(Tier.AVOID)
        BetResult.PUSH -> "Push" to tierColor(Tier.ACCEPTABLE)
        BetResult.PENDING -> "Pending" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
}


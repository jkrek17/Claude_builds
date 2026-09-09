package com.survivor.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.survivor.app.ui.theme.CardRadius
import com.survivor.app.ui.theme.Spacing
import com.survivor.app.ui.theme.tierColor
import com.survivor.app.ui.theme.tierContainer
import com.survivor.engine.Evaluation
import com.survivor.engine.REGULAR_SEASON_WEEKS
import com.survivor.engine.Tier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object Fmt {
    fun pct(p: Double?): String = p?.let { "${(it * 100).roundToInt()}%" } ?: "—"
    fun pct1(p: Double?): String = p?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "—"
    fun spread(s: Double?): String = when {
        s == null -> "—"
        s == 0.0 -> "PK"
        else -> (if (s > 0) "+" else "") + num(s)
    }
    fun num(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)
    fun ml(ml: Int?): String = ml?.let { if (it > 0) "+$it" else "$it" } ?: "—"
    fun score(v: Double): String = v.roundToInt().toString()
    fun signed(v: Double): String = (if (v > 0) "+" else "") + num(v)
    fun matchup(opponent: String, isHome: Boolean, neutral: Boolean = false) = when {
        neutral -> "vs $opponent (N)"
        isHome -> "vs $opponent"
        else -> "@ $opponent"
    }
    fun age(epochMs: Long?, now: Long = System.currentTimeMillis()): String {
        if (epochMs == null || epochMs <= 0) return "never"
        val mins = (now - epochMs) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60} h ago"
            else -> "${mins / (60 * 24)} d ago"
        }
    }
    fun dateTime(epochMs: Long?): String = epochMs?.let { SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date(it)) } ?: "—"
    fun rating(v: Double?): String = v?.let { signed((it * 10).roundToInt() / 10.0) } ?: "—"
    /** Signed percentage for a bet's EV/CLV, e.g. "+4.2%" or "-1.0%". */
    fun evPct(v: Double): String = String.format(Locale.US, "%+.1f%%", v * 100)
    /** Like [age], but from a duration already in milliseconds rather than an epoch timestamp - for
     *  [com.survivor.engine.BettingBoard.boardAgeMs], which is pre-computed against the evaluation time. */
    fun durationAgo(ms: Long?): String {
        if (ms == null || ms < 0) return "never"
        val mins = ms / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60} h ago"
            else -> "${mins / (60 * 24)} d ago"
        }
    }
}

@Composable
fun TierBadge(text: String, tier: Tier, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(tierContainer(tier), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tierColor(tier))
    }
}

/** A rounded 16 dp card with a subtle elevation - the standard content container across the app. */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A tier-colored vertical bar plus text/grade - a low-key alternative to a full tinted chip, used where a
 *  tier needs to register next to a row without a loud background fill. */
@Composable
fun TierBar(tier: Tier, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 4.dp, height = 28.dp).background(tierColor(tier), RoundedCornerShape(2.dp)))
}

/** Grade chip: letter grade on a soft tinted container, colored by tier. Always paired with the letter
 *  grade text, never color alone. */
@Composable
fun GradeChip(grade: String, tier: Tier, modifier: Modifier = Modifier) = TierBadge(grade, tier, modifier)

/** Two strike pips (filled once a strike is used) plus the numeric label, so the strike count is never
 *  conveyed by color/fill alone. */
@Composable
fun StrikePips(strikesUsed: Int, allowed: Int = 2, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(allowed) { i ->
            val used = i < strikesUsed
            Box(
                Modifier.size(10.dp).background(
                    if (used) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            )
        }
        Text("$strikesUsed/$allowed strikes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A titled section that starts collapsed and expands on tap, for long-form content (Why this pick,
 *  alternatives, advanced settings). */
@Composable
fun Expandable(title: String, modifier: Modifier = Modifier, startExpanded: Boolean = false, subtitle: String? = null, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(startExpanded) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
        }
    }
}

/** A horizontally-scrolling row of chips (the route strip, e.g.), spaced consistently. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    Row(modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor, textAlign = TextAlign.End)
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

data class Col(val title: String, val width: Dp, val align: TextAlign = TextAlign.Start)

/** A frozen-header table that scrolls horizontally as one block; rows are supplied as cell strings. */
@Composable
fun HTable(
    columns: List<Col>,
    rows: List<List<String>>,
    rowColor: @Composable (Int) -> Color = { Color.Unspecified },
    cellColor: @Composable (Int, Int) -> Color = { _, _ -> Color.Unspecified },
    onRowClick: ((Int) -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 6.dp)) {
            columns.forEach { c ->
                Text(c.title, Modifier.width(c.width).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = c.align, maxLines = 2)
            }
        }
        rows.forEachIndexed { i, row ->
            Row(
                Modifier.background(rowColor(i)).padding(vertical = 6.dp)
                    .let { m -> if (onRowClick != null) m.then(Modifier.clickableRow { onRowClick(i) }) else m },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEachIndexed { j, cell ->
                    val c = columns[j]
                    Text(cell, Modifier.width(c.width).padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, textAlign = c.align, color = cellColor(i, j), maxLines = 2)
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
fun WeekSelector(selected: Int, onSelect: (Int) -> Unit, label: (Int) -> String = { "Wk $it" }) {
    val scroll = rememberScrollState()
    Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..REGULAR_SEASON_WEEKS).forEach { w ->
            FilterChip(selected = w == selected, onClick = { onSelect(w) }, label = { Text(label(w)) })
        }
    }
}

@Composable
fun EmptyState(message: String, buttonText: String? = null, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (buttonText != null && onClick != null) Button(onClick = onClick) { Text(buttonText) }
    }
}

/** Data freshness line with a warning once lines are older than a day. */
@Composable
fun FreshnessLine(evaluation: Evaluation, now: Long = System.currentTimeMillis()) {
    val s = evaluation.season
    val oddsAge = s.oddsFetchedAtEpochMs?.let { now - it } ?: Long.MAX_VALUE
    val stale = oddsAge > 24L * 3_600_000L
    val text = buildString {
        append("Lines: ${Fmt.age(s.oddsFetchedAtEpochMs, now)}")
        append(" · FPI: ${Fmt.age(s.fpiFetchedAtEpochMs, now)}")
        if (s.consensusFetchedAtEpochMs != null) append(" · Consensus: ${Fmt.age(s.consensusFetchedAtEpochMs, now)}")
    }
    Text(
        if (stale) "⚠ Odds are stale ($text). Refresh before picking." else text,
        style = MaterialTheme.typography.bodySmall,
        color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

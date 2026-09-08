package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.PoolGuidance
import com.survivor.app.ui.components.Expandable
import com.survivor.app.ui.components.Fmt
import com.survivor.app.ui.components.SectionCard
import com.survivor.app.ui.components.WeekSelector
import com.survivor.app.ui.theme.Spacing
import com.survivor.engine.ModelSettings
import com.survivor.engine.RouteObjective
import com.survivor.engine.Strategy

private data class Param(val key: String, val label: String, val get: (ModelSettings) -> Double, val set: (ModelSettings, Double) -> ModelSettings)

/** Every advanced parameter except the ones promoted into "Your pool" (poolEntries, poolPayoutSplit,
 *  fieldAverageWinProbability) and "Strategy" (routeObjective, horizonWeight, strategy). */
private val advancedParams = listOf(
    Param("marginSigma", "Margin sigma (points)", { it.marginSigma }, { s, v -> s.copy(marginSigma = v.coerceIn(8.0, 20.0)) }),
    Param("homeFieldPoints", "Home field (points)", { it.homeFieldPoints }, { s, v -> s.copy(homeFieldPoints = v.coerceIn(0.0, 5.0)) }),
    Param("futureMarketWeight", "Future market weight (0–1)", { it.futureMarketWeight }, { s, v -> s.copy(futureMarketWeight = v.coerceIn(0.0, 1.0)) }),
    Param("futureDiscountPerWeek", "Future discount per week (0–0.2)", { it.futureDiscountPerWeek }, { s, v -> s.copy(futureDiscountPerWeek = v.coerceIn(0.0, 0.2)) }),
    Param("futureValueWeight", "Future value weight", { it.futureValueWeight }, { s, v -> s.copy(futureValueWeight = v.coerceIn(0.0, 3.0)) }),
    Param("futureScarcityWeight", "Future scarcity weight", { it.futureScarcityWeight }, { s, v -> s.copy(futureScarcityWeight = v.coerceIn(0.0, 10.0)) }),
    Param("pathLossWeight", "Season path loss weight", { it.pathLossWeight }, { s, v -> s.copy(pathLossWeight = v.coerceIn(0.0, 5.0)) }),
    Param("roadPenalty", "Road penalty", { it.roadPenalty }, { s, v -> s.copy(roadPenalty = v.coerceIn(0.0, 10.0)) }),
    Param("divisionalPenalty", "Divisional penalty", { it.divisionalPenalty }, { s, v -> s.copy(divisionalPenalty = v.coerceIn(0.0, 10.0)) }),
    Param("shortRestPenaltyPerDay", "Short-rest penalty per day", { it.shortRestPenaltyPerDay }, { s, v -> s.copy(shortRestPenaltyPerDay = v.coerceIn(0.0, 5.0)) }),
    Param("travelPenaltyPerTimeZone", "Travel penalty per time zone", { it.travelPenaltyPerTimeZone }, { s, v -> s.copy(travelPenaltyPerTimeZone = v.coerceIn(0.0, 5.0)) }),
    Param("qbUncertaintyPenalty", "QB uncertainty penalty", { it.qbUncertaintyPenalty }, { s, v -> s.copy(qbUncertaintyPenalty = v.coerceIn(0.0, 20.0)) }),
    Param("injuryWeight", "Injury adjustment weight", { it.injuryWeight }, { s, v -> s.copy(injuryWeight = v.coerceIn(0.0, 3.0)) }),
    Param("weatherPenalty", "Weather adjustment weight", { it.weatherPenalty }, { s, v -> s.copy(weatherPenalty = v.coerceIn(0.0, 3.0)) }),
    Param("marketConfidenceBonus", "Market confidence bonus", { it.marketConfidenceBonus }, { s, v -> s.copy(marketConfidenceBonus = v.coerceIn(0.0, 10.0)) }),
    Param("projectionOnlyPenalty", "Projection-only penalty", { it.projectionOnlyPenalty }, { s, v -> s.copy(projectionOnlyPenalty = v.coerceIn(0.0, 15.0)) }),
    Param("minimumAcceptableWinProbability", "Minimum acceptable win probability (0–1)", { it.minimumAcceptableWinProbability }, { s, v -> s.copy(minimumAcceptableWinProbability = v.coerceIn(0.5, 0.9)) }),
    Param("strikeFutureWeightMultiplier", "Future weight multiplier after a strike (0–1)", { it.strikeFutureWeightMultiplier }, { s, v -> s.copy(strikeFutureWeightMultiplier = v.coerceIn(0.0, 1.0)) }),
    Param("monteCarloIterations", "Monte Carlo iterations", { it.monteCarloIterations.toDouble() }, { s, v -> s.copy(monteCarloIterations = v.toInt().coerceIn(1000, 200_000)) }),
)

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val settings = state.user.settings
    val descriptions = remember { ModelSettings.descriptions.toMap() }
    var entriesText by remember(settings.poolEntries) { mutableStateOf(settings.poolEntries.toString()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionCard("Your pool") {
            Text("Number of entries", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PoolGuidance.presets.forEach { p ->
                    FilterChip(
                        selected = PoolGuidance.selectedPreset(settings.poolEntries) == p,
                        onClick = { entriesText = p.toString(); vm.updateSettings(settings.copy(poolEntries = p)) },
                        label = { Text(PoolGuidance.presetLabel(p)) },
                    )
                }
            }
            OutlinedTextField(
                value = entriesText,
                onValueChange = { entriesText = it; it.toIntOrNull()?.let { n -> vm.updateSettings(settings.copy(poolEntries = n.coerceIn(2, 100_000))) } },
                label = { Text("Exact number of entries") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            Text(PoolGuidance.text(settings.poolEntries, eval?.expectedPoolEndWeek), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Split the pot evenly", style = MaterialTheme.typography.bodyMedium)
                    Text("Off assumes a tiebreaker decides among survivors instead. Explanation only - doesn't change the math.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.poolPayoutSplit, onCheckedChange = { vm.updateSettings(settings.copy(poolPayoutSplit = it)) })
            }
            ParamField(
                Param("fieldAverageWinProbability", "Field average win probability (0–1)", { it.fieldAverageWinProbability }, { s, v -> s.copy(fieldAverageWinProbability = v.coerceIn(0.5, 0.95)) }),
                settings, descriptions["fieldAverageWinProbability"] ?: "",
            ) { vm.updateSettings(it) }
        }

        SectionCard("Strategy") {
            Text("Route objective", style = MaterialTheme.typography.titleSmall)
            RouteObjective.entries.forEach { obj ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.routeObjective == obj, onClick = { vm.updateSettings(settings.copy(routeObjective = obj)) })
                    Column {
                        Text(obj.label + if (obj == RouteObjective.POOL_WIN) " (recommended for most pools)" else "", style = MaterialTheme.typography.bodyMedium)
                        Text(obj.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (settings.routeObjective == RouteObjective.BLENDED) {
                ParamField(
                    Param("horizonWeight", "Horizon weight (0=survive season, 1=expected weeks)", { it.horizonWeight }, { s, v -> s.copy(horizonWeight = v.coerceIn(0.0, 1.0)) }),
                    settings, descriptions["horizonWeight"] ?: "",
                ) { vm.updateSettings(it) }
            }
            HorizontalDivider()
            Text("Ownership strategy", style = MaterialTheme.typography.titleSmall)
            Strategy.entries.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.strategy == s, onClick = { vm.updateSettings(settings.forStrategy(s)) })
                    Column { Text(s.label, style = MaterialTheme.typography.bodyMedium); Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text("Ownership leverage weight is set by the strategy (currently ${Fmt.num(settings.ownershipLeverageWeight)}). It only matters for teams where you entered a pick share in Weekly Inputs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("Current week", style = MaterialTheme.typography.titleSmall)
            Text("Detected automatically from the schedule (first week with an unfinished game). Override only if your pool's week differs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WeekSelector(state.user.weekOverride ?: 0, { w -> vm.setWeekOverride(if (w == state.user.weekOverride) null else w) })
            if (state.user.weekOverride != null) OutlinedButton(onClick = { vm.setWeekOverride(null) }) { Text("Back to automatic week") }
        }

        Expandable("Advanced model parameters", subtitle = "Every weight and penalty behind the Safety Score") {
            advancedParams.forEach { p -> ParamField(p, settings, descriptions[p.key] ?: "") { vm.updateSettings(it) } }
            Button(onClick = {
                vm.updateSettings(
                    ModelSettings().copy(
                        strategy = settings.strategy, ownershipLeverageWeight = settings.ownershipLeverageWeight,
                        poolEntries = settings.poolEntries, poolPayoutSplit = settings.poolPayoutSplit,
                        fieldAverageWinProbability = settings.fieldAverageWinProbability,
                        routeObjective = settings.routeObjective, horizonWeight = settings.horizonWeight,
                    ),
                )
            }) { Text("Restore defaults") }
        }
    }
}

@Composable
private fun ParamField(p: Param, settings: ModelSettings, description: String, onChange: (ModelSettings) -> Unit) {
    val current = p.get(settings)
    var text by remember(current) { mutableStateOf(Fmt.num(current)) }
    Column {
        OutlinedTextField(
            value = text, onValueChange = { text = it; it.toDoubleOrNull()?.let { v -> onChange(p.set(settings, v)) } },
            label = { Text(p.label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

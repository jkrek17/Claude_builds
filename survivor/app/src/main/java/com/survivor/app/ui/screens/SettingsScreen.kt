package com.survivor.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.survivor.app.background.AUTO_REFRESH_INTERVAL_HOURS_CHOICES
import com.survivor.app.background.AutoRefreshPrefs
import com.survivor.app.background.AutoRefreshScheduler
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
    Param("totalSigma", "Total sigma (points)", { it.totalSigma }, { s, v -> s.copy(totalSigma = v.coerceIn(5.0, 20.0)) }),
)

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val eval by vm.evaluation.collectAsStateWithLifecycle()
    val settings = state.user.settings
    val descriptions = remember { ModelSettings.descriptions.toMap() }
    var entriesText by remember(settings.poolEntries) { mutableStateOf(settings.poolEntries.toString()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        AutoUpdatesCard()

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

        SectionCard("Betting") {
            Text("Powers the Bets tab: line-shopping and model-vs-market edges, and how big a bet it suggests. Never affects the survivor recommendation.", style = MaterialTheme.typography.bodySmall)
            var bankrollText by remember(settings.bankroll) { mutableStateOf(Fmt.num(settings.bankroll)) }
            OutlinedTextField(
                value = bankrollText,
                onValueChange = { bankrollText = it; it.toDoubleOrNull()?.let { v -> vm.updateSettings(settings.copy(bankroll = v.coerceIn(0.0, 1_000_000.0))) } },
                label = { Text("Bankroll (\$)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            Text(descriptions["bankroll"] ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Kelly multiplier", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(0.1, 0.25, 0.5).forEach { m ->
                    FilterChip(selected = settings.kellyMultiplier == m, onClick = { vm.updateSettings(settings.copy(kellyMultiplier = m)) }, label = { Text("${Fmt.num(m * 100)}%") })
                }
            }
            Text(descriptions["kellyMultiplier"] ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PercentField("Max stake (%)", settings.maxStakePct, descriptions["maxStakePct"] ?: "") { vm.updateSettings(settings.copy(maxStakePct = it)) }
            PercentField("Min line-shop edge (%)", settings.minLineShopEdge, descriptions["minLineShopEdge"] ?: "") { vm.updateSettings(settings.copy(minLineShopEdge = it)) }
            PercentField("Min model edge (%)", settings.minModelEdge, descriptions["minModelEdge"] ?: "") { vm.updateSettings(settings.copy(minModelEdge = it)) }
            Text("Model weight (Bet Score)", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(0.0, 0.25, 0.5).forEach { w ->
                    FilterChip(selected = settings.modelWeight == w, onClick = { vm.updateSettings(settings.copy(modelWeight = w)) }, label = { Text(Fmt.num(w)) })
                }
            }
            Text(descriptions["modelWeight"] ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include totals", style = MaterialTheme.typography.bodyMedium)
                    Text(descriptions["includeTotals"] ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.includeTotals, onCheckedChange = { vm.updateSettings(settings.copy(includeTotals = it)) })
            }
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
                        bankroll = settings.bankroll, kellyMultiplier = settings.kellyMultiplier, maxStakePct = settings.maxStakePct,
                        minLineShopEdge = settings.minLineShopEdge, minModelEdge = settings.minModelEdge, includeTotals = settings.includeTotals,
                        modelWeight = settings.modelWeight,
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

/** Like [ParamField] but a fraction (0..1) shown and edited as a whole-number percentage, e.g. 0.02 as "2". */
@Composable
private fun PercentField(label: String, current: Double, description: String, onChange: (Double) -> Unit) {
    var text by remember(current) { mutableStateOf(Fmt.num(current * 100)) }
    Column {
        OutlinedTextField(
            value = text, onValueChange = { text = it; it.toDoubleOrNull()?.let { v -> onChange((v / 100.0).coerceIn(0.0, 1.0)) } },
            label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Background refresh: enable/interval/notification toggles, last-run status and a manual "Run now". Reads
 *  and writes [AutoRefreshPrefs] directly (app-side settings, kept out of the engine's [ModelSettings]). */
@Composable
private fun AutoUpdatesCard() {
    val context = LocalContext.current
    val store = remember { AutoRefreshPrefs(context) }
    var prefs by remember { mutableStateOf(store.snapshot()) }
    fun reload() { prefs = store.snapshot() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Whether or not notifications were granted, background refresh itself doesn't need the permission -
        // only posting a notification does, and that call is failure-safe. So turn the feature on either way.
        store.enabled = true
        AutoRefreshScheduler.schedule(context, store)
        reload()
    }

    fun setEnabled(turnOn: Boolean) {
        if (turnOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        store.enabled = turnOn
        AutoRefreshScheduler.schedule(context, store)
        reload()
    }

    SectionCard("Automatic updates") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatic background refresh", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Periodically refreshes lines and scores in the background and notifies you about pick changes, results and reminders.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = prefs.enabled, onCheckedChange = { setEnabled(it) })
        }
        if (prefs.enabled) {
            HorizontalDivider()
            Text("Check every", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AUTO_REFRESH_INTERVAL_HOURS_CHOICES.forEach { hours ->
                    FilterChip(
                        selected = prefs.intervalHours == hours,
                        onClick = { store.intervalHours = hours; AutoRefreshScheduler.schedule(context, store); reload() },
                        label = { Text("${hours}h") },
                    )
                }
            }
            HorizontalDivider()
            NotifyToggleRow("Recommended pick changed", prefs.notifyPickChanged) { store.notifyPickChanged = it; reload() }
            NotifyToggleRow("A pick's result comes in", prefs.notifyResultRecorded) { store.notifyResultRecorded = it; reload() }
            NotifyToggleRow("No pick recorded by the weekend", prefs.notifyNoPickByWeekend) { store.notifyNoPickByWeekend = it; reload() }
        }
        HorizontalDivider()
        val outcome = prefs.lastRunOutcome.takeIf { it.isNotBlank() } ?: "—"
        Text(
            "Last automatic refresh: ${Fmt.age(prefs.lastRunEpochMs.takeIf { it > 0 })} · $outcome",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { AutoRefreshScheduler.runNow(context) }) { Text("Run now") }
    }
}

@Composable
private fun NotifyToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

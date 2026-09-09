package com.survivor.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.survivor.app.data.RefreshStatus
import com.survivor.app.data.SurvivorRepository
import com.survivor.engine.Adjustment
import com.survivor.engine.Bet
import com.survivor.engine.BetBoard
import com.survivor.engine.BettingEngine
import com.survivor.engine.Evaluation
import com.survivor.engine.Ledger
import com.survivor.engine.ModelSettings
import com.survivor.engine.Policy
import com.survivor.engine.PolicyComparison
import com.survivor.engine.PolicySimulator
import com.survivor.engine.RobustPlanner
import com.survivor.engine.SimulationResult
import com.survivor.engine.StabilityReport
import com.survivor.engine.Team
import com.survivor.engine.data.SavedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(private val repo: SurvivorRepository) : ViewModel() {
    val state: StateFlow<SavedState> = repo.state
    val refresh: StateFlow<RefreshStatus> = repo.refresh

    private val _evaluation = MutableStateFlow<Evaluation?>(null)
    val evaluation: StateFlow<Evaluation?> = _evaluation

    private val _evaluating = MutableStateFlow(false)
    val evaluating: StateFlow<Boolean> = _evaluating

    private val _simulation = MutableStateFlow<List<SimulationResult>>(emptyList())
    val simulation: StateFlow<List<SimulationResult>> = _simulation
    private val _simulating = MutableStateFlow(false)
    val simulating: StateFlow<Boolean> = _simulating

    /** Scenario-based stability of the recommended route (see [RobustPlanner]); recomputed after every
     *  evaluation on a background dispatcher and cached here. Null while nothing has finished yet. */
    private val _robustPlan = MutableStateFlow<StabilityReport?>(null)
    val robustPlan: StateFlow<StabilityReport?> = _robustPlan
    private val _robustPlanning = MutableStateFlow(false)
    val robustPlanning: StateFlow<Boolean> = _robustPlanning

    private val _policyComparison = MutableStateFlow<PolicyComparison?>(null)
    val policyComparison: StateFlow<PolicyComparison?> = _policyComparison
    private val _policyComparing = MutableStateFlow(false)
    val policyComparing: StateFlow<Boolean> = _policyComparing

    /** The graded Bet Score board for the Betting tab - every market of every current-week game, scored
     *  and ranked; never influences the survivor recommendation above. Recomputed on a background
     *  dispatcher whenever state changes - cheap enough to redo on every refresh, pick, or setting change. */
    private val _betBoard = MutableStateFlow<BetBoard?>(null)
    val betBoard: StateFlow<BetBoard?> = _betBoard

    /** Every logged bet, graded from final scores, with totals split by signal and market. */
    private val _ledger = MutableStateFlow<Ledger?>(null)
    val ledger: StateFlow<Ledger?> = _ledger

    init {
        viewModelScope.launch {
            repo.state.collectLatest { s ->
                _evaluating.value = true
                _evaluation.value = runCatching { repo.evaluate(s) }.getOrNull()
                _evaluating.value = false
            }
        }
        // Independent collector so a slow robustness pass never blocks the fast evaluation above; collectLatest
        // cancels any in-flight scenario run the moment newer state arrives.
        viewModelScope.launch {
            repo.state.collectLatest { s ->
                val season = s.season
                if (season == null || season.games.isEmpty()) {
                    _robustPlan.value = null
                    return@collectLatest
                }
                _robustPlanning.value = true
                _robustPlan.value = withContext(Dispatchers.Default) {
                    runCatching {
                        RobustPlanner.plan(season, s.user, System.currentTimeMillis(), scenarios = 150, lockedValueScenarios = 60)
                    }.getOrNull()
                }
                _robustPlanning.value = false
            }
        }
        // Betting board and ledger: independent of the survivor evaluation above, and cheap enough to
        // recompute on Dispatchers.Default every time state changes.
        viewModelScope.launch {
            repo.state.collectLatest { s ->
                val season = s.season
                if (season == null || season.games.isEmpty()) {
                    _betBoard.value = null
                    _ledger.value = null
                    return@collectLatest
                }
                val now = System.currentTimeMillis()
                withContext(Dispatchers.Default) {
                    _betBoard.value = runCatching { BettingEngine.board(season, s.user, now, s.lineHistory) }.getOrNull()
                    _ledger.value = runCatching { BettingEngine.ledger(season, s.user) }.getOrNull()
                }
            }
        }
    }

    fun refreshNflData() = viewModelScope.launch { repo.refreshNflData(includeProjections = true) }
    fun refreshOdds() = viewModelScope.launch { repo.refreshOdds() }
    fun refreshOddsBoard(force: Boolean = true) = viewModelScope.launch { repo.refreshOddsBoard(force) }
    fun dismissRefresh() = repo.dismissRefreshStatus()
    fun recomputeNow() = viewModelScope.launch { _evaluation.value = repo.evaluate() }

    fun recordPick(week: Int, team: Team) = repo.recordPick(week, team)
    fun clearPick(week: Int) = repo.clearPick(week)
    fun setAdjustment(a: Adjustment) = repo.setAdjustment(a)
    fun updateSettings(s: ModelSettings) = repo.updateSettings(s)
    fun setOddsApiKey(k: String) = repo.setOddsApiKey(k)
    fun setWeekOverride(w: Int?) = repo.setWeekOverride(w)
    fun recordBet(bet: Bet) = repo.recordBet(bet)
    fun deleteBet(id: String) = repo.deleteBet(id)
    fun reset(includeData: Boolean) {
        repo.reset(includeData)
        _simulation.value = emptyList()
        _policyComparison.value = null
    }

    fun runMonteCarlo() = viewModelScope.launch {
        _simulating.value = true
        _simulation.value = runCatching { repo.compareStrategies() }.getOrDefault(emptyList())
        _simulating.value = false
    }

    /** Closed-loop comparison of the five headline policies over [seasons] re-decided seasons (200-2000). */
    fun runPolicyComparison(seasons: Int = 500) = viewModelScope.launch {
        val season = state.value.season ?: return@launch
        val user = state.value.user
        _policyComparing.value = true
        _policyComparison.value = withContext(Dispatchers.Default) {
            runCatching {
                val settings = user.settings
                val policies = listOf(
                    Policy.Greedy,
                    Policy.Optimized(settings.futureDiscountPerWeek),
                    Policy.PoolWin(settings.futureDiscountPerWeek, settings.poolEntries, settings.fieldAverageWinProbability),
                    Policy.ZeroLoss,
                    Policy.Threshold(settings.minimumAcceptableWinProbability, settings.futureDiscountPerWeek),
                )
                PolicySimulator.simulate(season, user, System.currentTimeMillis(), policies, seasons.coerceIn(200, 2000))
            }.getOrNull()
        }
        _policyComparing.value = false
    }

    class Factory(private val repo: SurvivorRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repo) as T
    }
}

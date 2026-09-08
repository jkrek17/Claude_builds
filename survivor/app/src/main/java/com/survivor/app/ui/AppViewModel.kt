package com.survivor.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.survivor.app.data.RefreshStatus
import com.survivor.app.data.SurvivorRepository
import com.survivor.engine.Adjustment
import com.survivor.engine.Evaluation
import com.survivor.engine.ModelSettings
import com.survivor.engine.SimulationResult
import com.survivor.engine.Team
import com.survivor.engine.data.SavedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

    init {
        viewModelScope.launch {
            repo.state.collectLatest { s ->
                _evaluating.value = true
                _evaluation.value = runCatching { repo.evaluate(s) }.getOrNull()
                _evaluating.value = false
            }
        }
    }

    fun refreshNflData() = viewModelScope.launch { repo.refreshNflData(includeProjections = true) }
    fun refreshOdds() = viewModelScope.launch { repo.refreshOdds() }
    fun dismissRefresh() = repo.dismissRefreshStatus()
    fun recomputeNow() = viewModelScope.launch { _evaluation.value = repo.evaluate() }

    fun recordPick(week: Int, team: Team) = repo.recordPick(week, team)
    fun clearPick(week: Int) = repo.clearPick(week)
    fun setAdjustment(a: Adjustment) = repo.setAdjustment(a)
    fun updateSettings(s: ModelSettings) = repo.updateSettings(s)
    fun setOddsApiKey(k: String) = repo.setOddsApiKey(k)
    fun setWeekOverride(w: Int?) = repo.setWeekOverride(w)
    fun reset(includeData: Boolean) { repo.reset(includeData); _simulation.value = emptyList() }

    fun runMonteCarlo() = viewModelScope.launch {
        _simulating.value = true
        _simulation.value = runCatching { repo.compareStrategies() }.getOrDefault(emptyList())
        _simulating.value = false
    }

    class Factory(private val repo: SurvivorRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repo) as T
    }
}

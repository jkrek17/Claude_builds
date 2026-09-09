package com.survivor.app.data

import com.survivor.engine.Adjustment
import com.survivor.engine.Bet
import com.survivor.engine.Evaluation
import com.survivor.engine.Evaluator
import com.survivor.engine.LineHistory
import com.survivor.engine.ModelSettings
import com.survivor.engine.Pick
import com.survivor.engine.Season
import com.survivor.engine.SimulationResult
import com.survivor.engine.Strategies
import com.survivor.engine.Team
import com.survivor.engine.UserState
import com.survivor.engine.data.OddsApiParser
import com.survivor.engine.data.SavedState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

sealed class RefreshStatus {
    data object Idle : RefreshStatus()
    data class Running(val message: String) : RefreshStatus()
    data class Failed(val message: String) : RefreshStatus()
    data class Done(val message: String) : RefreshStatus()
}

/** Single source of truth: persisted state in, evaluated model out. All mutations save immediately. */
class SurvivorRepository(
    private val store: StateStore,
    private val espn: EspnClient,
    private val oddsApi: OddsApiClient,
    /** Yahoo Survival Football's public pick distribution (real ownership data). Defaulted so existing
     *  callers that only wire up [espn] and [oddsApi] keep compiling. */
    private val yahoo: YahooClient = YahooClient(OkHttpFetcher()),
    private val now: () -> Long = System::currentTimeMillis,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<SavedState> = _state

    private val _refresh = MutableStateFlow<RefreshStatus>(RefreshStatus.Idle)
    val refresh: StateFlow<RefreshStatus> = _refresh

    val season: Season? get() = _state.value.season
    val user: UserState get() = _state.value.user
    val lineHistory: LineHistory get() = _state.value.lineHistory

    suspend fun evaluate(state: SavedState = _state.value): Evaluation? {
        val s = state.season ?: return null
        if (s.games.isEmpty()) return null
        return withContext(computeDispatcher) { Evaluator.evaluate(s, state.user, now()) }
    }

    suspend fun compareStrategies(iterations: Int? = null): List<SimulationResult> {
        val s = season ?: return emptyList()
        return withContext(computeDispatcher) { Strategies.compare(s, user, now(), iterations) }
    }

    private fun mutate(block: (SavedState) -> SavedState) {
        _state.update { block(it).copy(savedAtEpochMs = now()) }
        store.save(_state.value)
    }

    private fun mutateUser(block: (UserState) -> UserState) = mutate { it.copy(user = block(it.user)) }

    /** Appends a line snapshot for the current state's season, if any, using its inferred (or overridden) current week. */
    private fun recordLineSnapshot() {
        val season = _state.value.season ?: return
        val currentWeek = _state.value.user.weekOverride ?: season.inferCurrentWeek(now())
        mutate { it.copy(lineHistory = it.lineHistory.append(season, currentWeek, now())) }
    }

    suspend fun refreshNflData(includeProjections: Boolean = true) {
        if (_refresh.value is RefreshStatus.Running) return
        _refresh.value = RefreshStatus.Running("Starting")
        try {
            val updated = espn.refresh(season, includeProjections) { msg -> _refresh.value = RefreshStatus.Running(msg) }
            mutate { it.copy(season = updated) }
            val key = user.oddsApiKey
            var note = "Schedule, lines and FPI updated"
            if (key.isNotBlank()) {
                _refresh.value = RefreshStatus.Running("Consensus moneylines (Odds API)")
                runCatching { oddsApi.refresh(updated, key) }
                    .onSuccess { s -> mutate { it.copy(season = s) } }
                    .onFailure { e -> note = "ESPN data updated; Odds API failed: ${e.message}" }
            }
            val missing = EspnClient.teamsMissing(updated)
            if (missing.isNotEmpty()) note += ". No games found for ${missing.joinToString { t -> t.abbr }}"
            note += refreshPickShares()
            note += refreshBoard(force = false)
            recordLineSnapshot()
            _refresh.value = RefreshStatus.Done(note)
        } catch (e: Exception) {
            _refresh.value = RefreshStatus.Failed(e.message ?: e.toString())
        }
    }

    /** Lines only (fast): re-pulls the 18 scoreboard pages and, if configured, the Odds API. */
    suspend fun refreshOdds() {
        if (_refresh.value is RefreshStatus.Running) return
        val s = season
        if (s == null || s.games.isEmpty()) { refreshNflData(); return }
        _refresh.value = RefreshStatus.Running("Refreshing lines")
        try {
            val updated = espn.refresh(s, includeProjections = false) { msg -> _refresh.value = RefreshStatus.Running(msg) }
            mutate { it.copy(season = updated) }
            var note = "Lines and scores updated"
            val key = user.oddsApiKey
            if (key.isNotBlank()) {
                _refresh.value = RefreshStatus.Running("Consensus moneylines (Odds API)")
                runCatching { oddsApi.refresh(updated, key) }
                    .onSuccess { r -> mutate { it.copy(season = r) } }
                    .onFailure { e -> note = "Lines updated; Odds API failed: ${e.message}" }
            }
            note += refreshPickShares()
            note += refreshBoard(force = false)
            recordLineSnapshot()
            _refresh.value = RefreshStatus.Done(note)
        } catch (e: Exception) {
            _refresh.value = RefreshStatus.Failed(e.message ?: e.toString())
        }
    }

    /** Multi-book odds board (moneyline/spread/total) for the Betting tab, from The Odds API - 3 requests
     *  per call, so it is throttled to once every [BOARD_REFRESH_INTERVAL_MS] unless [force]. Skipped
     *  silently when no key is saved or no season is loaded yet; non-fatal on failure (the existing board,
     *  if any, is kept and a short note is appended to the caller's refresh message). */
    private suspend fun refreshBoard(force: Boolean): String {
        val key = user.oddsApiKey
        if (key.isBlank()) return ""
        val currentSeason = season ?: return ""
        val age = currentSeason.boardFetchedAtEpochMs?.let { now() - it }
        if (!force && age != null && age < BOARD_REFRESH_INTERVAL_MS) return ""
        _refresh.value = RefreshStatus.Running("Odds board (line shopping)")
        return try {
            val boards = oddsApi.fetchBoard(key)
            val fetchedAt = now()
            mutate { s ->
                val sn = s.season ?: return@mutate s
                s.copy(season = sn.copy(board = OddsApiParser.attachBoard(sn.games, boards), boardFetchedAtEpochMs = fetchedAt))
            }
            ""
        } catch (e: Exception) {
            ". Odds board unavailable: ${e.message}"
        }
    }

    /** Standalone "Refresh odds board" action for the Bets screen; [force] bypasses the 3-hour throttle
     *  (default true, since a user tapping the button explicitly wants a fresh board). */
    suspend fun refreshOddsBoard(force: Boolean = true) {
        if (_refresh.value is RefreshStatus.Running) return
        if (user.oddsApiKey.isBlank()) { _refresh.value = RefreshStatus.Failed("Add a The Odds API key in Weekly Inputs first"); return }
        if (season == null) { _refresh.value = RefreshStatus.Failed("Download NFL data first"); return }
        val note = refreshBoard(force)
        _refresh.value = if (note.isEmpty()) RefreshStatus.Done("Odds board updated") else RefreshStatus.Failed(note.removePrefix(". "))
    }

    /**
     * Pulls Yahoo Survival Football's public pick distribution and merges it into the current state's season
     * for whichever week the page reported (falling back to the inferred current week if the page didn't say),
     * keeping every other week's previously-fetched shares. Non-fatal: on any failure, or an empty
     * distribution (a week the field hasn't started picking yet), the season's existing shares are left
     * untouched and a short status note is returned to append to the caller's refresh message.
     */
    private suspend fun refreshPickShares(): String {
        val before = _state.value.season ?: return ""
        _refresh.value = RefreshStatus.Running("Yahoo pick shares")
        return try {
            val (week, shares) = yahoo.fetchPickDistribution()
            if (shares.isEmpty()) return ""
            val targetWeek = week ?: (_state.value.user.weekOverride ?: before.inferCurrentWeek(now()))
            mutate {
                it.copy(
                    season = before.copy(
                        pickShares = before.pickShares + (targetWeek to shares),
                        pickSharesFetchedAtEpochMs = now(),
                        pickSharesSource = "Yahoo Survival Football (all Yahoo entries)",
                    ),
                )
            }
            ""
        } catch (e: Exception) {
            ". Yahoo pick shares unavailable"
        }
    }

    fun dismissRefreshStatus() { if (_refresh.value !is RefreshStatus.Running) _refresh.value = RefreshStatus.Idle }

    fun recordPick(week: Int, team: Team) = mutateUser { u ->
        u.copy(picks = u.picks.filter { it.week != week } + Pick(week, team))
    }

    fun clearPick(week: Int) = mutateUser { u -> u.copy(picks = u.picks.filter { it.week != week }) }

    fun setAdjustment(adjustment: Adjustment) = mutateUser { u ->
        val others = u.adjustments.filter { !(it.week == adjustment.week && it.team == adjustment.team) }
        val isEmpty = adjustment.overrideWinProbability == null && adjustment.injuryPoints == 0.0 && adjustment.qbPoints == 0.0 &&
            adjustment.weatherPoints == 0.0 && adjustment.estimatedPickShare == null && adjustment.note.isBlank()
        u.copy(adjustments = if (isEmpty) others else others + adjustment)
    }

    fun updateSettings(settings: ModelSettings) = mutateUser { it.copy(settings = settings) }

    fun setOddsApiKey(key: String) = mutateUser { it.copy(oddsApiKey = key.trim()) }

    fun setWeekOverride(week: Int?) = mutateUser { it.copy(weekOverride = week) }

    fun recordBet(bet: Bet) = mutateUser { it.copy(bets = it.bets + bet) }

    fun deleteBet(id: String) = mutateUser { it.copy(bets = it.bets.filter { b -> b.id != id }) }

    /** Resets picks, adjustments and settings; keeps downloaded data and line history unless [includeData]. */
    fun reset(includeData: Boolean) = mutate { s ->
        SavedState(
            season = if (includeData) null else s.season,
            user = UserState(oddsApiKey = s.user.oddsApiKey),
            lineHistory = if (includeData) LineHistory() else s.lineHistory,
        )
    }

    companion object {
        /** Minimum gap between automatic odds-board fetches - see docs/BETTING.md's quota note. */
        private const val BOARD_REFRESH_INTERVAL_MS = 3L * 3_600_000L
    }
}

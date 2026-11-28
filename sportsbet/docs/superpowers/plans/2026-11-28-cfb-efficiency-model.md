# College Football Efficiency Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `cfbmodel`, a play-by-play efficiency model that prices every FBS game's spread and total, and score it once, honestly, against real closing and opening lines.

**Architecture:** Per-game team efficiency (EPA per play, success rate, explosiveness, finishing drives, pace) from sportsdataverse play-by-play, opponent-adjusted each week by a ridge regression that uses only earlier games and is shrunk toward a preseason prior (last season, returning production, talent, recruiting, portal, coaching change). Ratings map to points via drives times points per drive; a college-fitted key-number distribution turns a predicted margin or total into cover chances. A walk-forward backtest tunes on 2014-2019 and 2021-2022 and scores once on 2023-2025 under a rulebook committed first.

**Tech Stack:** Python 3.10+, numpy, pandas, pyarrow (new), pytest. Reuses `nflmodel.scoredist` (same project folder).

**Spec:** Claude Doc "College football model: design", https://claude.ai/artifact/C3WXRkTjK2PcoeJifUNSwV (approved by Jason 2026-11-28).

## Global Constraints

- All code lives in `sportsbet/` (repo `CLAUDE.md`: one project per folder). New package `sportsbet/src/cfbmodel/`, command `cfbmodel`.
- The CollegeFootballData key is read from the `CFBD_API_KEY` environment variable only. Never write it to disk, logs, test fixtures, commits or reports.
- Data cache: `~/.cache/cfbmodel/` (override with `--data-dir`). Raw downloads never go into git.
- Free CFBD tier: 1,000 calls a month. A full 2013-2026 backfill must stay under 400 calls; every call is cached forever except the current season.
- Never use published SP+, FPI or any end-of-season rating as an input. Every rating is rebuilt as of the game date.
- Games: FBS vs FBS, regular season, for betting and scoring. FCS games feed ratings through one pooled FCS team. Bowls and playoff games are never bet or scored.
- Seasons: 2013 seed only; tune 2014-2019 and 2021-2022; 2020 updates ratings but is never tuned or scored; score once on 2023-2025; 2026 is paper only.
- Lines: FanDuel close where present, else consensus close, at the real price; -110 when no price exists, flagged.
- Tuning budget: at most 24 configurations (2 markets x 3 edge thresholds x 4 segments: all, Power 4 both teams, any Group of 5, weeks 0-4). All 24 are logged.
- Pass bar (scored seasons, every line must hold): win rate > 52.38%; ROI > 0; line value positive with clustered t >= 2; no season below 50%; tune seasons same direction; >= 400 bets per market.
- Sportsbooks in reports and output: FanDuel and Fanatics only.

## Review Focus

1. **Teams moving FCS to FBS** (Jacksonville State, Sam Houston, Kennesaw State, Delaware, Missouri State): must be FBS from their first FBS season and pooled FCS before; test in Task 3.
2. **Team naming across sources** (play-by-play vs CFBD lines vs talent tables, "Miami" vs "Miami (OH)"): join on CFBD team id, never on name; test in Task 3.
3. **A game with several line providers or none**: pick FanDuel, else consensus, else median; a game with no line is excluded from betting but still updates ratings; test in Task 3.
4. **Week 0 and week 1 with no current-season plays**: ratings equal the prior exactly, no crash, no NaN; test in Task 5.
5. **Missing or wrong CFBD key, or HTTP 429**: clear one-line error naming `CFBD_API_KEY` without echoing the key; back off and retry 429 up to 4 times; test in Task 1.

---

### Task 1: Package, CFBD client and cache

**Files:**
- Create: `src/cfbmodel/__init__.py`, `src/cfbmodel/cfbd.py`, `src/cfbmodel/cli.py`
- Modify: `pyproject.toml` (add `cfbmodel = "cfbmodel.cli:main"`; add `"pyarrow>=14"` to `model` and `dev` extras)
- Modify: `sportsbet/.gitignore` (nothing new needed if cache is outside repo; confirm)
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Produces: `cfbd.get(endpoint: str, params: dict, data_dir: Path, *, live: bool = False) -> list[dict]` (cached JSON at `data_dir/cfbd/<endpoint>/<sorted params>.json`; `live=True` refetches if older than 6 hours); `cfbd.MissingKey(Exception)`; `cfbd.calls_made() -> int`.
- Produces: `cli.main(argv: list[str] | None = None) -> int` with subcommand `fetch --seasons 2013-2026`.

- [ ] **Step 1: Write failing tests** (monkeypatch `urllib.request.urlopen`):
  - `test_get_caches_and_second_call_does_not_hit_network`: two `get("games", {"year": 2023})` calls, fake urlopen called once, results equal.
  - `test_missing_key_raises_clear_error`: no env var -> `MissingKey`, message contains `CFBD_API_KEY`.
  - `test_key_never_written_to_cache`: env `CFBD_API_KEY=secret123`; after a call, no file under `data_dir` contains `secret123`.
  - `test_429_retries_then_succeeds`: fake returns 429 twice then 200 -> result returned, 3 calls, sleep monkeypatched.
- [ ] **Step 2:** `pytest tests/test_cfbmodel.py -v` -> FAIL (no module).
- [ ] **Step 3:** Implement with `urllib.request`, header `Authorization: Bearer <key>`, base `https://api.collegefootballdata.com`, retry 429/5xx with 2, 4, 8, 16 s.
- [ ] **Step 4:** Tests PASS; `pip install -e .[dev]` and `cfbmodel --help` exits 0.
- [ ] **Step 5:** Commit `cfbmodel: CFBD client with cache and key from environment`.

### Task 2: Play-by-play loader and garbage-time filter

**Files:**
- Create: `src/cfbmodel/pbp.py`
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Produces: `pbp.PBP_URL = "https://github.com/sportsdataverse/sportsdataverse-data/releases/download/cfbfastR_cfb_pbp/play_by_play_{season}.parquet"`
- Produces: `pbp.load(season: int, data_dir: Path) -> pd.DataFrame` — downloads once (current season refetched if older than 6 h), keeps only `KEEP_COLS` and writes `data_dir/pbp/slim_{season}.parquet` (~10 MB), deletes the full file.
- Produces: `pbp.KEEP_COLS` = game_id, season, week, season_type, start_date, pos_team, def_pos_team, home, away, period, TimeSecsRem, pos_score_diff_start, down, distance, yards_to_goal, play_type, rush, pass, EPA, success, scoring_opp, drive_id, drive_pts, drive_start_yards_to_goal, neutral_site, home_team_id, away_team_id, home_team_division, away_team_division, home_team_conference, away_team_conference.
- Produces: `pbp.is_garbage(df) -> pd.Series[bool]` — true when |pos_score_diff_start| > 38 in period 2, > 28 in period 3, > 22 in period 4.
- Produces: `pbp.scrimmage(df) -> pd.DataFrame` — rush or pass plays, EPA not null, not garbage.

- [ ] **Step 1: Failing tests:** `test_is_garbage_thresholds` (rows at diff 39/q2 True, 38/q2 False, 29/q3 True, 23/q4 True, 50/q1 False); `test_scrimmage_drops_kicks_and_null_epa`; `test_load_reads_slim_cache_without_network` (pre-write a slim parquet, urlopen raises if called).
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** Implement; stream download to `.part`, then rename.
- [ ] **Step 4:** PASS. Manual check: `python -c "from cfbmodel import pbp; d=pbp.load(2023, Path('~/.cache/cfbmodel').expanduser()); print(len(d))"` prints about 254,000.
- [ ] **Step 5:** Commit `cfbmodel: play-by-play loader with garbage-time filter`.

### Task 3: Games, teams and lines table

**Files:**
- Create: `src/cfbmodel/games.py`
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Consumes: `cfbd.get` (endpoints `games` with `seasonType=both`, `lines`, `teams/fbs?year=`).
- Produces: `games.load(seasons: range, data_dir: Path) -> pd.DataFrame`, one row per game: game_id, season, week, start_date (UTC), season_type, neutral_site, home_id, away_id, home_team, away_team, home_fbs, away_fbs, home_conf, away_conf, home_points, away_points, spread_close, total_close, spread_open, total_open, line_provider, spread_price_home, spread_price_away (NaN when absent).
- Produces: `games.pick_line(rows: list[dict]) -> dict` — provider order FanDuel, consensus, then median of others.
- Produces: `games.POWER4` conference set per season (SEC, Big Ten, Big 12, ACC, plus Pac-12 before 2024; Notre Dame counts as Power 4).
- Spread sign: home perspective, negative = home favored (CFBD convention).

- [ ] **Step 1: Failing tests:** `test_pick_line_prefers_fanduel_then_consensus_then_median`; `test_team_fbs_flag_follows_season` (Sam Houston FBS 2023, not 2022, from a fake `teams/fbs` response); `test_join_by_id_not_name` (two teams both named "Miami" variants with different ids stay separate); `test_game_without_line_kept_with_nan`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS; manual: `games.load(range(2023, 2024), ...)` has about 880 FBS-involved regular-season games, and > 90% have `spread_close`.
- [ ] **Step 5:** Commit `cfbmodel: game and line table keyed by team id`.

### Task 4: Per-game team efficiency

**Files:**
- Create: `src/cfbmodel/efficiency.py`
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Consumes: `pbp.scrimmage`, full pbp for drives.
- Produces: `efficiency.team_games(plays: pd.DataFrame) -> pd.DataFrame`, one row per (game_id, team) for the offense: opp, home (1/0/neutral 0), epa_rush, epa_pass, success, explosive (mean EPA of successful plays), finish (points per scoring opportunity, drive reaching opponent 40), plays, drives, sec_per_play, points.
- `METRICS = ["epa_rush", "epa_pass", "success", "explosive", "finish", "sec_per_play"]`.

- [ ] **Step 1: Failing test** `test_team_games_on_tiny_game`: hand-built 2-team, 8-play game with known EPA, success and one scoring drive; assert each column to 1e-9, two rows returned, `finish` = 7.0 for the team with one 7-point opportunity.
- [ ] **Step 2-4:** FAIL, implement with groupby, PASS. Manual: 2023 season mean `success` between 0.38 and 0.46.
- [ ] **Step 5:** Commit `cfbmodel: per-game team efficiency`.

### Task 5: Opponent-adjusted ratings, as of a date

**Files:**
- Create: `src/cfbmodel/ratings.py`
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Consumes: `efficiency.team_games`, `efficiency.METRICS`.
- Produces: `ratings.adjust(tg: pd.DataFrame, metric: str, prior: pd.DataFrame, prior_games: float, decay: float) -> pd.DataFrame` — columns team, off, def; solves `value = mean + off[team] + def[opp] + hfa*home` by weighted ridge, each team's off/def shrunk toward `prior[off_<metric>]`/`prior[def_<metric>]` with strength `prior_games` pseudo-games; game weights `decay ** weeks_ago`. FCS teams all mapped to team `"FCS"`.
- Produces: `ratings.as_of(tg_all: pd.DataFrame, cutoff: pd.Timestamp, prior: pd.DataFrame, params: dict) -> pd.DataFrame` — wide frame team x {off_,def_}{METRICS}; uses only rows with start_date < cutoff and same season.
- `params` keys: `prior_games` (default 4.0), `decay` (default 0.97); tuned in Task 9.

- [ ] **Step 1: Failing tests:** `test_adjust_recovers_known_strengths` (12 synthetic teams, round robin, value = true_off - true_def + noise 0.01 -> correlation with truth > 0.99); `test_as_of_ignores_future_games` (adding a game after cutoff does not change output); `test_no_games_returns_prior_exactly` (week 0: output equals prior, no NaN); `test_fcs_pooled` (two FCS opponents produce one "FCS" row).
- [ ] **Step 2-4:** FAIL, implement (numpy least squares on augmented system), PASS.
- [ ] **Step 5:** Commit `cfbmodel: opponent-adjusted ratings as of a date`.

### Task 6: Preseason prior

**Files:**
- Create: `src/cfbmodel/prior.py`
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Consumes: `cfbd.get` (`talent`, `player/returning`, `recruiting/teams`, `player/portal`, `coaches`), `ratings.as_of` (last season's end-of-regular-season rebuilt ratings).
- Produces: `prior.inputs(season: int, data_dir: Path) -> pd.DataFrame` — team, last_<m> for each metric (as_of the last regular-season game of season-1), ret_off, ret_def (PPA share), ret_qb (1 if top passer returns), talent (3-year weighted 0.5/0.3/0.2), recruit (mean rating classes season-1..season-4), portal_net, new_coach (0/1).
- Produces: `prior.fit(train_seasons: list[int], data_dir) -> dict` (ridge weights per metric, target = that season's end-of-regular-season rebuilt rating); `prior.build(season, weights, data_dir) -> pd.DataFrame` (team, off_<m>, def_<m>, sd_mult; sd_mult 1.3 for new_coach else 1.0). Weights for season S use only seasons < S.
- Teams new to FBS: last_* = FCS pooled value.

- [ ] **Step 1: Failing tests:** `test_fit_uses_only_earlier_seasons` (fit for 2018 never reads 2018+ target, enforced with a spy on `ratings.as_of`); `test_new_coach_widens_not_shifts` (identical inputs except new_coach -> same off/def, sd_mult 1.3 vs 1.0); `test_new_fbs_team_gets_fcs_baseline`.
- [ ] **Step 2-4:** FAIL, implement, PASS. Manual: correlation of 2022 prior with 2022 end-of-season rebuilt rating > 0.6 for `success`.
- [ ] **Step 5:** Commit `cfbmodel: preseason prior from returning production, talent, recruiting, portal`.

### Task 7: Points, margin and total

**Files:**
- Create: `src/cfbmodel/predict.py`
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Consumes: `ratings.as_of`, `prior.build`.
- Produces: `predict.fit_points(history: pd.DataFrame) -> dict` — linear model of team points on own off ratings and opponent def ratings (all METRICS) plus home and neutral terms, and of drives on both teams' sec_per_play; history = earlier seasons only; post-2023 clock rule handled by a `season >= 2023` term on drives.
- Produces: `predict.game(home: pd.Series, away: pd.Series, neutral: bool, coef: dict) -> dict` with keys margin (home minus away), total, home_pts, away_pts.
- Produces: `predict.season(season: int, data_dir: Path, params: dict) -> pd.DataFrame` — walk-forward: each week's games predicted from `as_of(week start)`; columns game_id plus margin, total.

- [ ] **Step 1: Failing tests:** `test_neutral_site_has_no_home_edge` (same teams, neutral margin = 0 when ratings equal); `test_symmetry` (swap home/away at neutral site -> margin flips sign, total unchanged); `test_season_prediction_uses_week_start_cutoff` (spy asserts cutoff <= each game's start_date).
- [ ] **Step 2-4:** FAIL, implement, PASS. Manual: 2019 margin MAE vs result < 13.5 and closing-line MAE printed for comparison.
- [ ] **Step 5:** Commit `cfbmodel: points, margin and total predictions`.

### Task 8: College margin and total distributions

**Files:**
- Create: `src/cfbmodel/dist.py`
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Consumes: `nflmodel.scoredist.key_factors`, `keyed_pmf`, `line_probs`.
- Produces: `dist.fit(history: pd.DataFrame) -> dict` — margin range (-90, 90), total range (0, 150); `sd_margin(spread) = a + b*|spread|` and `sd_total(total) = c + d*total` fitted on residuals vs closing line; key factors from closing lines (symmetric for margin).
- Produces: `dist.cover_prob(pred_margin: float, line: float, fit: dict) -> tuple[float, float, float]` (home covers, push, away covers) and `dist.over_prob(pred_total, line, fit)` (over, push, under).

- [ ] **Step 1: Failing tests:** `test_probs_sum_to_one`; `test_half_point_line_has_no_push`; `test_wider_sd_for_big_spreads` (fit on synthetic data with sd growing in |spread| -> b > 0); `test_key_three_weaker_than_nfl` (fit on synthetic CFB margins where 3 occurs 9% -> factor at 3 below the NFL default from `nflmodel`).
- [ ] **Step 2-4:** FAIL, implement, PASS.
- [ ] **Step 5:** Commit `cfbmodel: college key-number distributions`.

### Task 9: Backtest harness, metrics and tuning

**Files:**
- Create: `src/cfbmodel/backtest.py`, `research/cfb/tune.py`
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Consumes: `predict.season`, `dist`, `games.load`.
- Produces: `backtest.bets(preds: pd.DataFrame, games: pd.DataFrame, market: str, edge_min: float, segment: str) -> pd.DataFrame` — one row per bet: game_id, season, week, side, line, price, prob, edge (prob x decimal price - 1), result (1 win, 0 loss, NaN push), clv_points (line moved toward our side from open to close, NaN without opener). Segments: `all`, `p4` (both Power 4), `g5` (any Group of 5), `early` (weeks 0-4). One bet per game per market.
- Produces: `backtest.summary(bets) -> dict` — n, wins, losses, pushes, win_rate, roi (at real price), roi_110, clv_mean, clv_t (SE clustered by season-week), by_season dict.
- Produces: `backtest.opener_staleness(games) -> float` — t-stat of (close - open) on the previous game's result-minus-close surprise; |t| > 3 means openers predate last week and opener results are dropped.
- `research/cfb/tune.py`: runs the 24 configurations on tune seasons, plus `params` grid prior_games {2,4,8} x decay {0.95,0.97,1.0} chosen by margin RMSE on tune seasons first (not by ROI); writes `research/cfb/tune_results.csv`.

- [ ] **Step 1: Failing tests:** `test_summary_counts_and_roi` (3 wins 1 loss at -110 -> roi = (3*0.9091-1)/4 to 1e-4); `test_clustered_se_larger_than_naive_when_week_correlated`; `test_push_excluded_from_win_rate`; `test_one_bet_per_game`; `test_staleness_detects_planted_artifact` (synthetic openers built from the previous week's line -> |t| > 3).
- [ ] **Step 2-4:** FAIL, implement, PASS. Run `python research/cfb/tune.py` (long run; see project memory for where long runs go) and save output.
- [ ] **Step 5:** Commit `cfbmodel: backtest harness and tune-season grid`.

### Task 10: Rulebook, one scoring run, report

**Files:**
- Create: `research/cfb/RULEBOOK.md` (committed before scoring), `research/cfb/score.py`, `research/cfb/REPORT.md`
- Modify: `sportsbet/README.md` (add `cfbmodel` to the tool list)

**Interfaces:**
- Consumes: everything above; `tune_results.csv`.

- [ ] **Step 1:** Write RULEBOOK.md: chosen params, one configuration per market, caps (one bet per game, at most 10 a week, $5 flat), exclusions, the pass bar copied from Global Constraints, data snapshot hash (sha256 of the slim parquet and games table) and git commit of the model.
- [ ] **Step 2:** Commit `cfb: rulebook frozen before scoring` and push. Do not run `score.py` before this commit exists.
- [ ] **Step 3:** Run `python research/cfb/score.py` once on 2023-2025; it refuses to run if RULEBOOK.md has uncommitted changes. Output: per-market summary, per-season table, Holm-adjusted p for 2 tests, opener results only if staleness |t| <= 3.
- [ ] **Step 4:** Leakage audit before writing: any market above 58% win rate -> re-run with ratings shifted one week earlier and with priors only; document.
- [ ] **Step 5:** Write REPORT.md (honest figures, pass or fail against each bar line), update README, commit `cfb: scored once on 2023-2025`, push, open draft PR.

### Task 11: Weekly card command

**Files:**
- Modify: `src/cfbmodel/cli.py`
- Test: `tests/test_cfbmodel.py`

**Interfaces:**
- Produces: `cfbmodel predict --season 2026 --week N [--json]` — prints each FBS game: model margin and total, line, cover/over chance, and a BET flag only for games meeting the frozen RULEBOOK configuration and only if Task 10 passed (otherwise the header says "Research only: did not pass the backtest").

- [ ] **Step 1: Failing test** `test_predict_prints_research_only_when_not_passed` (fixture rulebook with `passed: false`).
- [ ] **Step 2-4:** FAIL, implement, PASS; run on the current week and eyeball 5 games against FanDuel.
- [ ] **Step 5:** Commit `cfbmodel: weekly card`.

## Deferred (phase 2, needs Jason's OK)

- Game weather for totals (CFBD $1/month tier).
- FanDuel and Fanatics lag vs sharp books on Sunday nights (about 2,000 Odds API credits).
- ESPN per-book FanDuel closes for 2017-2023 (about 7,000 keyless calls) if CFBD's provider list lacks FanDuel.
- College tab on the website, only if Task 10 passes.

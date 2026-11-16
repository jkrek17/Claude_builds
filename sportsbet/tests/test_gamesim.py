import numpy as np
import pandas as pd
import pytest

from nflmodel import bayes_ratings as br, gamesim as gs, sim_cli
from test_nflmodel import TEAMS, synthetic

SHAPE = {"drives": 10, "p7": 0.9, "p8": 0.03, "fg_intercept": 1.2, "fg_slope": 0.02, "safety": 0.03,
         "cov": 0.0, "game_var": 0.0, "resid_sd": 9.4, "ot": {"tie": 0.05, "three": 0.62, "six": 0.3}}


def scoring_from(games):
    rows = []
    for g in games[games.home_score.notna()].itertuples():
        for team, pts in ((g.home_team, int(g.home_score)), (g.away_team, int(g.away_score))):
            td, rest = divmod(pts, 7)
            rows.append(dict(game_id=g.game_id, team=team, td=td, fg=rest // 3, pat=td, two=0, safety=0))
    return pd.DataFrame(rows)


def test_qb_depth_numbers_starters_in_order_and_remembers_a_returning_one():
    games = pd.DataFrame([
        dict(game_id=f"g{i}", season=2026, gameday=f"2026-09-{7 + 7 * i:02d}", home_team="AAA", away_team="BBB",
             home_score=20, away_score=17, home_qb_id=qb, away_qb_id="b1")
        for i, qb in enumerate(["a1", "a2", "a3", "a1"])])
    games.loc[3, ["home_score", "away_score"]] = np.nan  # an unplayed game is still labelled
    d = br.qb_depth(games)
    assert list(d.home_qb_string) == [1, 2, 3, 1]
    assert list(d.home_qb_changed) == [0, 1, 1, 1]
    assert list(d.away_qb_string) == [1, 1, 1, 1] and not d.away_qb_changed.any()


def test_ratings_follow_strength_narrow_with_games_and_widen_for_a_new_quarterback():
    games, stats, _ = synthetic(seasons=range(1999, 2004))
    state = {}
    f = br.run_filter(games, stats, state=state)
    pts = state["pts"]
    played = games[games.home_score.notna()]
    scored = pd.concat([played.groupby("home_team").home_score.mean(), played.groupby("away_team").away_score.mean()],
                       axis=1).mean(axis=1)
    assert np.corrcoef(pts["offense"], scored.reindex(pts["teams"]))[0, 1] > 0.8
    last = f[f.season == 2003]
    assert last.pts_home_var.iloc[-1] < last.pts_home_var.iloc[0]  # more games, narrower band
    assert (f.pts_home_sd_obs > np.sqrt(f.pts_home_var)).all()

    swapped = games.copy()
    target = swapped[(swapped.season == 2003) & (swapped.week == 10)].index[0]
    swapped.loc[target, "home_qb_id"] = "emergency"
    f2 = br.run_filter(swapped, stats)
    gid = swapped.loc[target, "game_id"]
    assert f2.set_index("game_id").pts_home_var[gid] > f.set_index("game_id").pts_home_var[gid]
    assert f2.set_index("game_id").pts_home[gid] == pytest.approx(f.set_index("game_id").pts_home[gid])


def test_ratings_ignore_the_future():
    games, stats, _ = synthetic(seasons=range(1999, 2003))
    full = br.run_filter(games, stats).set_index("game_id")
    cut = games[games.gameday <= "2001-10-20"]
    part = br.run_filter(cut, stats[stats.game_id.isin(cut.game_id)]).set_index("game_id")
    cols = ["pts_home", "pts_away_var", "pass_home", "rush_away"]
    assert np.allclose(full.loc[part.index, cols], part[cols], atol=1e-9)


def test_simulation_matches_its_inputs_and_piles_up_on_key_numbers():
    home, away = gs.simulate(27.0, 20.0, 3.0, 3.0, SHAPE, n=60_000, seed=7)
    assert home.mean() == pytest.approx(27.0, abs=0.3) and away.mean() == pytest.approx(20.0, abs=0.3)
    again = gs.simulate(27.0, 20.0, 3.0, 3.0, SHAPE, n=60_000, seed=7)
    assert (home == again[0]).all() and (away == again[1]).all()
    margin, total = gs.pmfs(home, away)
    assert margin.sum() == pytest.approx(1) and total.sum() == pytest.approx(1)
    at = lambda k: margin[k - gs.MARGIN_LO]
    assert at(3) > at(2) and at(3) > at(5) and at(7) > at(8) and at(7) > at(5)
    assert at(0) < 0.004  # overtime settles nearly every tie
    over, push, under = gs.line_probs(margin, 7.0, gs.MARGIN_LO)
    assert push == pytest.approx(at(7)) and over + push + under == pytest.approx(1)
    wide = gs.simulate(27.0, 20.0, 30.0, 30.0, SHAPE, n=60_000, seed=7)
    narrow = gs.simulate(27.0, 20.0, 30.0, 30.0, SHAPE, n=60_000, seed=7, uncertainty=False)
    assert (wide[0] - wide[1]).std() > (narrow[0] - narrow[1]).std() + 0.5


def test_calibration_factors_fix_a_number_the_simulation_undercounts():
    k = np.arange(gs.MARGIN_LO, gs.MARGIN_HI + 1)
    pmf = np.exp(-0.5 * (k / 13.0) ** 2)
    pmf /= pmf.sum()
    rng = np.random.default_rng(0)
    outcomes = rng.choice(k, size=4000, p=pmf)
    outcomes[:400] = 3 * rng.choice([-1, 1], size=400)  # threes come up far more often than simulated
    factors = gs.calibration_factors(np.tile(pmf, (4000, 1)), outcomes, gs.MARGIN_LO, True)
    assert factors[3 - gs.MARGIN_LO] > 2 and factors[3 - gs.MARGIN_LO] == pytest.approx(factors[-3 - gs.MARGIN_LO])
    fixed = gs.apply_factors(pmf, factors)
    assert fixed.sum() == pytest.approx(1) and fixed[3 - gs.MARGIN_LO] > 2 * pmf[3 - gs.MARGIN_LO]


def test_whole_pipeline_on_a_made_up_league(tmp_path, monkeypatch):
    games, stats, players = synthetic(seasons=range(1999, 2013))
    scoring = scoring_from(games)
    rows = gs.fit_means(gs.team_rows(games, stats, players, stats, scoring))
    table = gs.game_table(rows)
    assert len(table) == len(games) and len(rows) == 2 * len(games)
    late = table[table.season >= gs.FIRST_MEAN_SEASON]
    assert late.mu_home.notna().all() and (late.var_home > 0).all()
    done = late[late.home_points.notna()]
    assert np.corrcoef(done.mu_home - done.mu_away, done.home_points - done.away_points)[0, 1] > 0.2
    shape = gs.fit_shape(rows, games, 2012)
    assert 7 <= shape["drives"] <= 40 and shape["ot"]["three"] > shape["ot"]["six"]
    sim = gs.simulate_table(table, rows, games, n=500, seasons={2012})
    assert sim["margin"].shape == (len(table[table.season == 2012]), gs.MARGIN_HI - gs.MARGIN_LO + 1)
    assert np.allclose(sim["margin"].sum(1), 1)

    week = sim_cli.simulate_week(games, _write(tmp_path, stats, players), 2012, n=500)
    assert len(week) == len(TEAMS) // 2
    g = week[0]
    assert 0 < g["home_win"] < 1 and g["home_cover"] + g["spread_push"] <= 1 and 0 < g["over"] < 1
    assert sim_cli.fair_american(0.6) == "-150" and sim_cli.fair_american(0.25) == "+300"


def _write(tmp_path, stats, players):
    """Lay the made-up stats out the way the data module caches nflverse files."""
    for c in ("passing_tds", "rushing_tds", "special_teams_tds", "def_tds", "fumble_recovery_tds", "fg_made",
              "pat_made", "passing_2pt_conversions", "rushing_2pt_conversions", "def_safeties"):
        stats = stats.assign(**{c: 1})
    stats.to_csv(tmp_path / "stats_team_week_2012.csv", index=False)
    players.to_csv(tmp_path / "stats_player_week_2012.csv", index=False)
    return str(tmp_path)

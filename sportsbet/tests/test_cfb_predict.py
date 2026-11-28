import itertools

import numpy as np
import pandas as pd
import pytest

from cfbmodel import pbp, predict, ratings
from cfbmodel.efficiency import METRICS

TEAMS = [f"T{i:02d}" for i in range(10)]


def make_tg_all(seasons, teams=TEAMS, fcs=("F1",), seed=0, neutral_every=7):
    """Synthetic team_games_all output: a home-and-home round robin per season, plus FCS visits.

    Truth: points = 28 + 60 * (off - def) + 3 * home; metrics = off - def + noise."""
    rng = np.random.default_rng(seed)
    strength = {t: (rng.normal(0, 0.1), rng.normal(0, 0.1)) for t in list(teams) + list(fcs)}
    rows = []
    gid = 1000
    for season in seasons:
        start = pd.Timestamp(f"{season}-09-01 17:00", tz="UTC")
        pairs = list(itertools.permutations(teams, 2))
        pairs += [(t, f) for t in teams[:3] for f in fcs]
        rng.shuffle(pairs)
        for i, (h, a) in enumerate(pairs):
            gid += 1
            week = 1 + i // 9
            date = start + pd.Timedelta(days=7 * (week - 1), hours=3 * (i % 9))
            neutral = i % neutral_every == 0
            score = {}
            for team, opp, side in ((h, a, 1), (a, h, 0)):
                off, _ = strength[team]
                _, dfn = strength[opp]
                score[team] = 28 + 60 * (off - dfn) + 3 * (side and not neutral) + rng.normal(0, 3)
            for team, opp, side in ((h, a, 1), (a, h, 0)):
                off, _ = strength[team]
                _, dfn = strength[opp]
                r = dict(game_id=gid, season=season, week=week, season_type="regular", start_date=date,
                         team=team, team_id=hash(team) % 10000, opp=opp, opp_id=hash(opp) % 10000,
                         home=int(side and not neutral), is_home=side, neutral=neutral,
                         team_fbs=team not in fcs, opp_fbs=opp not in fcs, plays=70,
                         drives=12, points=score[team], opps=5,
                         team_score=round(score[team]), opp_score=round(score[opp]),
                         spread=-7.0 if side else np.nan, over_under=55.0)
                for m in METRICS:
                    r[m] = off - dfn + 0.01 * rng.standard_normal()
                r["sec_per_play"] = 25 + 10 * (off - dfn)
                rows.append(r)
    out = pd.DataFrame(rows)
    out["spread"] = out.groupby("game_id")["spread"].transform("first")
    return out


def ratings_row(**over):
    r = pd.Series({f"{s}_{m}": 0.0 for m in METRICS for s in ("off", "def")})
    for k, v in over.items():
        r[k] = v
    return r


@pytest.fixture(scope="module")
def coef():
    tg = make_tg_all([2016, 2017])
    hist = pd.concat([predict.walk(s, tg, predict.carryover_prior(s, tg), {}) for s in (2016, 2017)])
    return predict.fit_points(hist)


def test_neutral_site_has_no_home_edge(coef):
    r = ratings_row(off_success=0.05, def_epa_pass=-0.02)
    neutral = predict.game(r, r.copy(), True, coef)
    assert neutral["margin"] == pytest.approx(0.0, abs=1e-9)
    assert neutral["home_pts"] == pytest.approx(neutral["away_pts"])
    home = predict.game(r, r.copy(), False, coef)
    assert home["margin"] > 1.0  # the synthetic data carries a 3-point home edge
    assert set(neutral) >= {"margin", "total", "home_pts", "away_pts"}


def test_symmetry(coef):
    a = ratings_row(off_success=0.08, off_epa_pass=0.1, def_success=-0.03, off_sec_per_play=-2.0)
    b = ratings_row(off_success=-0.02, def_epa_rush=0.05, def_sec_per_play=1.0)
    ab = predict.game(a, b, True, coef)
    ba = predict.game(b, a, True, coef)
    assert ab["margin"] == pytest.approx(-ba["margin"], abs=1e-9)
    assert ab["total"] == pytest.approx(ba["total"], abs=1e-9)
    assert ab["margin"] == pytest.approx(ab["home_pts"] - ab["away_pts"])
    assert ab["total"] == pytest.approx(ab["home_pts"] + ab["away_pts"])
    assert ab["margin"] > 0  # a is the better team


@pytest.mark.parametrize("kind", ["direct", "drives"])
def test_fit_points_learns_strength_and_home_edge(kind):
    tg = make_tg_all([2016, 2017], seed=3)
    hist = pd.concat([predict.walk(s, tg, predict.carryover_prior(s, tg), {}) for s in (2016, 2017)])
    c = predict.fit_points(hist, kind=kind)
    assert c["kind"] == kind
    pred = predict.points(hist, c)
    ok = hist["points"].notna()
    assert np.corrcoef(pred[ok], hist.loc[ok, "points"])[0, 1] > 0.8


def test_season_prediction_uses_week_start_cutoff(tmp_path, monkeypatch):
    tg = make_tg_all([2016, 2017, 2018], seed=1)
    monkeypatch.setattr(predict, "team_games_all", lambda seasons, data_dir: tg[tg["season"].isin(seasons)])
    calls = []
    real = ratings.as_of

    def spy(tg_all, cutoff, prior, params):
        calls.append((pd.Timestamp(cutoff), (params or {}).get("season")))
        return real(tg_all, cutoff, prior, params)

    monkeypatch.setattr(predict.ratings, "as_of", spy)
    out = predict.season(2018, tmp_path, {"train_seasons": [2016, 2017]})

    games = tg[(tg["season"] == 2018) & (tg["is_home"] == 1)]
    assert sorted(out["game_id"]) == sorted(games["game_id"])
    used = {c for c, s in calls if s == 2018}
    assert set(out["cutoff"]) <= used
    assert (out["cutoff"] <= out["start_date"]).all()
    # the cutoff is the first kickoff of that week
    first = games.groupby("week")["start_date"].min()
    assert (out["cutoff"].to_numpy() == out["week"].map(first).to_numpy()).all()
    cols = {"game_id", "season", "week", "start_date", "home", "away", "home_id", "away_id", "neutral",
            "home_fbs", "away_fbs", "margin", "total", "home_pts", "away_pts", "home_points",
            "away_points", "spread", "over_under"}
    assert cols <= set(out.columns)
    assert np.isfinite(out[["margin", "total"]].to_numpy()).all()
    # predictions track the truth on synthetic data
    actual = out["home_points"] - out["away_points"]
    assert np.corrcoef(out["margin"], actual)[0, 1] > 0.7


def test_season_prior_fn_is_used(tmp_path, monkeypatch):
    tg = make_tg_all([2016, 2017], seed=2)
    monkeypatch.setattr(predict, "team_games_all", lambda seasons, data_dir: tg[tg["season"].isin(seasons)])
    seen = []

    def prior_fn(season):
        seen.append(season)
        return predict.carryover_prior(season, tg)

    predict.season(2017, tmp_path, {"train_seasons": [2016]}, prior_fn=prior_fn)
    assert 2017 in seen and 2016 in seen


def test_carryover_prior_regresses_and_seeds_new_fbs_teams():
    tg = make_tg_all([2016, 2017], fcs=("F1", "F2"))
    # F2 moves up to FBS in 2017
    moved = (tg["season"] == 2017)
    tg.loc[moved & (tg["team"] == "F2"), "team_fbs"] = True
    tg.loc[moved & (tg["opp"] == "F2"), "opp_fbs"] = True
    cut = tg.loc[tg["season"] == 2016, "start_date"].max() + pd.Timedelta(seconds=1)
    end = ratings.as_of(tg, cut, predict.zero_prior(), {"season": 2016})
    prior = predict.carryover_prior(2017, tg, regress=0.25)
    assert prior.loc["T03", "off_success"] == pytest.approx(0.75 * end.loc["T03", "off_success"])
    assert prior.loc["F2", "def_epa_pass"] == pytest.approx(0.75 * end.loc["FCS", "def_epa_pass"])
    assert {f"off_{m}" for m in METRICS} <= set(prior.columns)
    first = predict.carryover_prior(2016, tg)
    assert (first.fillna(0).to_numpy() == 0).all()


def scored_game(gid, season, home="A", away="B", div=("fbs", "fbs")):
    """Plays whose home margin walks 0, 7 (home TD), 0 (away TD), 3 (home FG), with a one-row glitch.

    Final score: home 10, away 7."""
    hm = [0, 0, 0, 7, 7, 7, 0, 0, 0, 3, 3, -3, 3, 3, 3, 3]  # row 11 is a sign glitch in the source
    teams = [home, away] * 8
    diffs = [(t, h if t == home else -h) for t, h in zip(teams, hm)]
    rows = []
    for i, (team, d) in enumerate(diffs):
        rows.append(dict(game_id=gid, season=season, week=1, season_type="regular",
                         start_date=f"{season}-09-0{1 + gid % 5}T19:00:00.000Z", pos_team=team,
                         def_pos_team=away if team == home else home, home=home, away=away,
                         period=1 + i // 4, TimeSecsRem=1800 - 100 * (i % 4), pos_score_diff_start=float(d),
                         down=1, distance=10, yards_to_goal=50, play_type="Rush", rush=1, **{"pass": 0},
                         EPA=0.1, success=1, scoring_opp=0, drive_id=gid * 100 + i // 2, drive_pts=0.0,
                         drive_start_yards_to_goal=75, neutral_site=False, home_team_id=1, away_team_id=2,
                         home_team_division=div[0], away_team_division=div[1],
                         home_team_conference="X", away_team_conference="Y", spread=-3.5, over_under=50.5))
    df = pd.DataFrame(rows)
    df.loc[df.index[-1], "play_type"] = "End Period"
    df.loc[df.index[-1], ["rush", "EPA"]] = [0, np.nan]
    return df


def test_final_scores_rebuilt_from_score_diff():
    out = predict.final_scores(scored_game(1, 2019)).set_index("game_id")
    assert out.loc[1, "home_points"] == 10 and out.loc[1, "away_points"] == 7


def test_team_games_all_flags_fbs_and_caches(tmp_path, monkeypatch):
    loads = []

    def fake_load(season, data_dir):
        loads.append(season)
        return pd.concat([scored_game(10 + season % 10, season),
                          scored_game(20 + season % 10, season, "A", "C", ("fbs", "fcs"))])

    monkeypatch.setattr(pbp, "load", fake_load)
    monkeypatch.setattr(pbp, "current_season", lambda *a: 2026)
    out = predict.team_games_all([2018, 2019], tmp_path)
    assert sorted(loads) == [2018, 2019]
    c = out[out["opp"] == "C"].iloc[0]
    assert bool(c["team_fbs"]) and not bool(c["opp_fbs"])
    c_row = out[out["team"] == "C"].iloc[0]
    assert not bool(c_row["team_fbs"]) and bool(c_row["opp_fbs"])
    assert str(out["start_date"].dt.tz) == "UTC"
    a = out[(out["team"] == "A") & (out["opp"] == "B")].iloc[0]
    assert a["team_score"] == 10 and a["opp_score"] == 7 and a["spread"] == -3.5
    assert (tmp_path / "team_games.parquet").exists()

    again = predict.team_games_all([2018, 2019], tmp_path)
    assert sorted(loads) == [2018, 2019]  # past seasons come from the cache
    pd.testing.assert_frame_equal(again.reset_index(drop=True), out.reset_index(drop=True),
                                  check_dtype=False)

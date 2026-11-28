import itertools
import time

import numpy as np
import pandas as pd
import pytest

from cfbmodel import ratings
from cfbmodel.ratings import METRICS

SEASON_START = pd.Timestamp("2024-08-31", tz="UTC")


def make_tg(teams, true_off, true_def, *, season=2024, noise=0.01, seed=0, rounds=1, fcs=()):
    """Round robin: one row per (game, offense). value = true_off[team] - true_def[opp] + noise."""
    rng = np.random.default_rng(seed)
    rows = []
    gid = 0
    pairs = list(itertools.combinations(teams, 2)) * rounds
    for i, (a, b) in enumerate(pairs):
        gid += 1
        date = SEASON_START + pd.Timedelta(days=7 * (i % 14))
        for team, opp, home in ((a, b, 1), (b, a, 0)):
            r = dict(game_id=gid, season=season, week=1 + i % 14, start_date=date, team=team, opp=opp,
                     team_id=hash(team) % 10000, opp_id=hash(opp) % 10000, home=home,
                     team_fbs=team not in fcs, opp_fbs=opp not in fcs, plays=70, drives=12, points=24)
            for m in METRICS:
                r[m] = true_off[team] - true_def[opp] + noise * rng.standard_normal()
            rows.append(r)
    return pd.DataFrame(rows)


def zero_prior(teams):
    cols = [f"{s}_{m}" for m in METRICS for s in ("off", "def")]
    return pd.DataFrame(0.0, index=pd.Index(teams, name="team"), columns=cols)


def test_adjust_recovers_known_strengths():
    rng = np.random.default_rng(1)
    teams = [f"T{i:02d}" for i in range(12)]
    true_off = dict(zip(teams, rng.normal(0, 0.1, 12)))
    true_def = dict(zip(teams, rng.normal(0, 0.1, 12)))
    tg = make_tg(teams, true_off, true_def, rounds=2)
    out = ratings.adjust(tg, "success", zero_prior(teams), prior_games=0.5, decay=1.0)
    assert list(out.columns[:3]) == ["team", "off", "def"]
    out = out.set_index("team").loc[teams]
    assert np.corrcoef(out["off"], [true_off[t] for t in teams])[0, 1] > 0.99
    # def[opp] is added to the value, so it estimates -true_def
    assert np.corrcoef(out["def"], [-true_def[t] for t in teams])[0, 1] > 0.99


def test_adjust_shrinks_toward_prior():
    teams = [f"T{i:02d}" for i in range(6)]
    tg = make_tg(teams, dict.fromkeys(teams, 0.0), dict.fromkeys(teams, 0.0), noise=0.0)
    prior = zero_prior(teams)
    prior.loc["T00", "off_success"] = 0.2
    weak = ratings.adjust(tg, "success", prior, prior_games=0.1, decay=1.0).set_index("team")
    strong = ratings.adjust(tg, "success", prior, prior_games=1000.0, decay=1.0).set_index("team")
    assert abs(strong.loc["T00", "off"] - 0.2) < 0.01
    assert strong.loc["T00", "off"] > weak.loc["T00", "off"]


def test_sd_mult_weakens_prior():
    teams = [f"T{i:02d}" for i in range(6)]
    tg = make_tg(teams, dict.fromkeys(teams, 0.0), dict.fromkeys(teams, 0.0), noise=0.0)
    prior = zero_prior(teams)
    prior.loc["T00", "off_success"] = 0.2
    prior["sd_mult"] = 1.0
    base = ratings.adjust(tg, "success", prior, prior_games=4.0, decay=1.0).set_index("team")
    prior.loc["T00", "sd_mult"] = 1.3
    wide = ratings.adjust(tg, "success", prior, prior_games=4.0, decay=1.0).set_index("team")
    assert wide.loc["T00", "off"] < base.loc["T00", "off"]


def test_decay_weights_recent_games_more():
    # Team A is great early, average late; with decay its rating should sit nearer the late form.
    teams = [f"T{i:02d}" for i in range(6)]
    tg = make_tg(teams, dict.fromkeys(teams, 0.0), dict.fromkeys(teams, 0.0), noise=0.0, rounds=2)
    early = tg["start_date"] < tg["start_date"].median()
    tg.loc[early & (tg["team"] == "T00"), "success"] += 0.3
    flat = ratings.adjust(tg, "success", zero_prior(teams), prior_games=1.0, decay=1.0).set_index("team")
    fast = ratings.adjust(tg, "success", zero_prior(teams), prior_games=1.0, decay=0.7).set_index("team")
    assert fast.loc["T00", "off"] < flat.loc["T00", "off"]


def test_fcs_pooled():
    teams = ["A", "B", "C", "D", "F1", "F2"]
    tg = make_tg(teams, dict.fromkeys(teams, 0.0), dict.fromkeys(teams, 0.0), fcs=("F1", "F2"))
    tg = tg[~((tg["team"].isin(["F1", "F2"])) & (tg["opp"].isin(["F1", "F2"])))]
    out = ratings.adjust(tg, "success", zero_prior(["A", "B", "C", "D"]), prior_games=4.0, decay=1.0)
    assert (out["team"] == "FCS").sum() == 1
    assert not out["team"].isin(["F1", "F2"]).any()
    wide = ratings.as_of(tg, pd.Timestamp("2025-01-15", tz="UTC"), zero_prior(["A", "B", "C", "D"]), {})
    assert "FCS" in wide.index and "F1" not in wide.index


def _season_frame():
    rng = np.random.default_rng(2)
    teams = [f"T{i:02d}" for i in range(10)]
    tg = make_tg(teams, dict(zip(teams, rng.normal(0, 0.1, 10))), dict(zip(teams, rng.normal(0, 0.1, 10))),
                 noise=0.05)
    prior = zero_prior(teams) + rng.normal(0, 0.05, (10, 2 * len(METRICS)))
    return teams, tg, prior


def test_as_of_shape_and_games():
    teams, tg, prior = _season_frame()
    cutoff = SEASON_START + pd.Timedelta(days=7 * 5)
    out = ratings.as_of(tg, cutoff, prior, {"prior_games": 4.0, "decay": 0.97})
    assert set(out.columns) == {f"{s}_{m}" for m in METRICS for s in ("off", "def")} | {"games"}
    assert set(out.index) == set(teams)
    assert not out.isna().any().any()
    expected = tg[tg["start_date"] < cutoff].groupby("team").size()
    assert (out.loc[expected.index, "games"] == expected).all()


def test_as_of_ignores_future_games():
    teams, tg, prior = _season_frame()
    cutoff = SEASON_START + pd.Timedelta(days=7 * 5)
    base = ratings.as_of(tg, cutoff, prior, {})
    later = tg[tg["start_date"] >= cutoff].copy()
    later["success"] += 5.0
    later["game_id"] += 10_000
    more = pd.concat([tg, later.assign(start_date=cutoff), later.assign(start_date=cutoff + pd.Timedelta(days=30))])
    pd.testing.assert_frame_equal(base, ratings.as_of(more, cutoff, prior, {}))


def test_as_of_uses_only_same_season():
    teams, tg, prior = _season_frame()
    cutoff = SEASON_START + pd.Timedelta(days=7 * 5)
    last_year = tg.assign(season=2023, start_date=tg["start_date"] - pd.Timedelta(days=364))
    last_year["success"] += 5.0
    base = ratings.as_of(tg, cutoff, prior, {})
    pd.testing.assert_frame_equal(base, ratings.as_of(pd.concat([last_year, tg]), cutoff, prior, {}))


def test_no_games_returns_prior_exactly():
    teams, tg, prior = _season_frame()
    last_year = tg.assign(season=2023, start_date=tg["start_date"] - pd.Timedelta(days=364))
    for cutoff in (SEASON_START, pd.Timestamp("2024-08-01")):  # week 0; also a tz-naive cutoff
        out = ratings.as_of(pd.concat([last_year, tg]), cutoff, prior, {"prior_games": 4.0, "decay": 0.97})
        assert not out.isna().any().any()
        pd.testing.assert_frame_equal(out.drop(columns="games"), prior.astype(float), check_names=False)
        assert (out["games"] == 0).all()


def test_team_without_games_keeps_prior_midseason():
    teams, tg, prior = _season_frame()
    prior.loc["IDLE"] = 0.123
    cutoff = SEASON_START + pd.Timedelta(days=7 * 5)
    out = ratings.as_of(tg, cutoff, prior, {})
    assert np.allclose(out.loc["IDLE"].drop("games"), 0.123)
    assert out.loc["IDLE", "games"] == 0


def test_as_of_speed():
    rng = np.random.default_rng(3)
    teams = [f"T{i:03d}" for i in range(130)]
    rows = []
    for g in range(800):
        a, b = rng.choice(130, 2, replace=False)
        date = SEASON_START + pd.Timedelta(days=7 * (g % 14))
        for t, o, h in ((a, b, 1), (b, a, 0)):
            rows.append(dict(game_id=g, season=2024, week=1 + g % 14, start_date=date, team=teams[t],
                             opp=teams[o], team_id=t, opp_id=o, home=h, team_fbs=True, opp_fbs=True,
                             plays=70, drives=12, points=24, **{m: rng.normal() for m in METRICS}))
    tg = pd.DataFrame(rows)
    prior = zero_prior(teams)
    cutoff = pd.Timestamp("2025-01-01", tz="UTC")
    ratings.as_of(tg, cutoff, prior, {})
    t0 = time.perf_counter()
    for _ in range(5):
        ratings.as_of(tg, cutoff, prior, {})
    per_metric_ms = (time.perf_counter() - t0) / 5 / len(METRICS) * 1000
    print(f"as_of: {per_metric_ms:.2f} ms per metric")
    assert per_metric_ms < 50

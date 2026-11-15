"""Tests for the player props model on a small synthetic league (no downloads)."""
import numpy as np
import pandas as pd
import pytest

from nflmodel import availability, props as P, props_card as C, props_cli

TEAMS = ["AAA", "BBB", "CCC", "DDD"]
ROSTER = [("QB", 1), ("RB", 2), ("WR", 3), ("TE", 1)]


def make_league(seasons=(2013, 2014, 2015), weeks=8, seed=0):
    rng = np.random.default_rng(seed)
    games, rows = [], []
    skill = {}
    for s in seasons:
        for w in range(1, weeks + 1):
            order = TEAMS if w % 2 else [TEAMS[0], TEAMS[2], TEAMS[1], TEAMS[3]]
            for home, away in (order[:2], order[2:]):
                gid = f"{s}_{w:02d}_{away}_{home}"
                day = pd.Timestamp(s, 9, 1) + pd.Timedelta(days=7 * w)
                spread, total = float(rng.integers(-7, 8)), float(rng.integers(38, 52))
                games.append({"game_id": gid, "season": s, "week": w, "gameday": day.strftime("%Y-%m-%d"),
                              "home_team": home, "away_team": away, "home_score": 24, "away_score": 20,
                              "result": 4.0, "total": 44.0, "location": "Home", "spread_line": spread,
                              "total_line": total, "roof": "outdoors", "home_qb_id": f"{home}-QB0",
                              "away_qb_id": f"{away}-QB0", "home_qb_name": f"{home} QB0", "away_qb_name": f"{away} QB0"})
                for team, opp in ((home, away), (away, home)):
                    for pos, n in ROSTER:
                        for i in range(n):
                            pid = f"{team}-{pos}{i}"
                            k = skill.setdefault(pid, rng.uniform(0.6, 1.4))
                            r = dict.fromkeys(P._RAW, 0.0)
                            if pos == "QB":
                                r.update(attempts=rng.poisson(32 * k), carries=rng.poisson(3))
                                r.update(completions=rng.binomial(int(r["attempts"]), 0.63))
                                r.update(passing_yards=round(r["completions"] * rng.gamma(9, 1.25)),
                                         passing_tds=rng.poisson(1.5 * k), rushing_yards=round(r["carries"] * 4.0))
                            else:
                                tgt = {"RB": 3, "WR": 7, "TE": 5}[pos] * k / (1 + i * 0.4)
                                car = (14 * k / (1 + i)) if pos == "RB" else 0.0
                                r.update(targets=rng.poisson(tgt), carries=rng.poisson(car))
                                r.update(receptions=rng.binomial(int(r["targets"]), 0.65))
                                r.update(receiving_yards=round(r["receptions"] * rng.gamma(4, 2.8)),
                                         rushing_yards=round(r["carries"] * rng.gamma(4, 1.05)),
                                         rushing_tds=rng.poisson(0.03 * r["carries"]),
                                         receiving_tds=rng.poisson(0.05 * r["targets"]))
                            rows.append({"player_id": pid, "player_display_name": pid.replace("-", " "), "position": pos,
                                         "season": s, "week": w, "game_id": gid, "team": team, "opponent_team": opp, **r})
    return pd.DataFrame(rows), pd.DataFrame(games)


@pytest.fixture(scope="module")
def league():
    return make_league()


@pytest.fixture(scope="module")
def panel(league):
    return P.build_panel(*league)


def test_decayed_is_pregame_and_breaks_memory():
    keys = np.array(["a", "a", "a", "b"])
    x = np.array([[1.0], [2.0], [4.0], [8.0]])
    assert P._decayed(keys, x, 0.5)[:, 0].tolist() == [0.0, 1.0, 2.5, 0.0]
    cut = P._decayed(keys, x, 0.5, np.array([1.0, 1.0, 0.5, 1.0]))
    assert cut[:, 0].tolist() == [0.0, 1.0, 1.25, 0.0]


def test_count_distributions_sum_to_one_and_keep_their_mean():
    for pmf, mean in ((P.nb_pmf(4.2, 0.2), 4.2), (P.nb_pmf(0.4, 0.0), 0.4), (P.binom_pmf(1.6, 9), 1.6)):
        assert pmf.sum() == pytest.approx(1.0)
        assert (pmf * np.arange(len(pmf))).sum() == pytest.approx(mean, abs=1e-3)


def test_features_do_not_see_the_game_they_predict(league, panel):
    offense, games = league
    last = games["game_id"].iloc[-1]
    changed = offense.copy()
    hit = changed["game_id"] == last
    for c in P._RAW:
        changed.loc[hit, c] = changed.loc[hit, c] * 3 + 5
    other = P.build_panel(changed, games)
    cols = sorted({c for f in P.FEATURES.values() for c in f} | {p.gate for p in P.PROPS})
    a = panel[panel["game_id"] == last].sort_values("player_id")[cols].to_numpy(float)
    b = other[other["game_id"] == last].sort_values("player_id")[cols].to_numpy(float)
    assert np.allclose(a, b)


def test_vacated_volume_rises_when_a_teammate_is_missing(league):
    offense, games = league
    last = games["game_id"].iloc[-1]
    team = games["home_team"].iloc[-1]
    without = offense[~((offense["game_id"] == last) & (offense["player_id"] == f"{team}-WR0"))]
    full, short = P.build_panel(offense, games), P.build_panel(without, games)
    pick = lambda d: d[(d["game_id"] == last) & (d["player_id"] == f"{team}-WR1")]["vac_tgt"].iloc[0]
    assert pick(short) > pick(full) + 0.05


def test_models_give_sane_distributions(panel):
    models = P.fit_all(panel)
    last = panel[panel["season"] == panel["season"].max()]
    for prop in P.PROPS:
        d = last[P.eligible(last, prop) & last["position"].isin(list(models[prop.key].models)).to_numpy()]
        assert len(d) > 0
        mu = models[prop.key].expected(d)
        assert np.isfinite(mu).all() and (mu > 0).all()
        pos, m = d["position"].iloc[0], float(mu[0])
        lines = [0.5, 1.5, 3.5, 20.5, 60.5, 150.5, 400.5]
        probs = [models[prop.key].prob_over(pos, m, x) for x in lines]
        assert all(0.0 <= p <= 1.0 for p in probs)
        assert all(a >= b - 1e-12 for a, b in zip(probs, probs[1:]))
        if prop.kind == "yards":
            assert (np.diff(models[prop.key].quantiles(pos, m)) >= -1e-9).all()
        else:
            assert models[prop.key].pmf(pos, m).sum() == pytest.approx(1.0)


def test_better_players_get_higher_expectations(panel):
    models = P.fit_all(panel)
    last = panel[(panel["season"] == panel["season"].max()) & (panel["position"] == "WR")]
    d = last[P.eligible(last, P.PROP_BY_KEY["rec_yds"])]
    mu = models["rec_yds"].expected(d)
    assert np.corrcoef(mu, d["receiving_yards_slow"])[0, 1] > 0.6


def _avail(rows):
    return pd.DataFrame(rows, columns=availability.COLUMNS)


def test_for_week_uses_latest_row_and_flags_departures():
    rows = [("p1", "One", "WR", 2015, 5, "AAA", "ACT", "Out", None, "Knee", True),
            ("p1", "One", "WR", 2015, 6, "AAA", "ACT", None, None, None, False),
            ("p2", "Two", "WR", 2015, 5, "AAA", "ACT", "Out", None, "Knee", True),     # bye in week 6 for nobody: gone
            ("p3", "Three", "RB", 2015, 6, "BBB", "RES", None, None, None, True),
            ("p4", "Four", "TE", 2015, 5, "CCC", "INA", "Out", None, "Ankle", True)]   # CCC has no later roster
    w = availability.for_week(_avail(rows), 2015, 6).set_index("gsis_id")
    assert not w.at["p1", "out"]
    assert w.at["p2", "out"]            # no week 6 row although his team has one: he left the team
    assert w.at["p3", "out"]
    assert not w.at["p4", "out"] and pd.isna(w.at["p4", "report_status"])   # an old report does not carry over
    assert w.at["p4", "source_week"] == 5


def test_card_drops_players_who_are_out_and_moves_traded_players(league):
    offense, games = league
    games = games.copy()
    nxt = games["game_id"].iloc[-2:]
    games.loc[games["game_id"].isin(nxt), ["home_score", "away_score", "result", "total"]] = np.nan
    offense = offense[~offense["game_id"].isin(nxt)]
    season, week = int(games["season"].iloc[-1]), int(games["week"].iloc[-1])
    ids = offense["player_id"].unique()
    rows = [(p, p, p.split("-")[1][:2], season, week, p.split("-")[0], "ACT", None, None, None, False) for p in ids]
    av = _avail(rows).set_index("gsis_id")
    av.loc["AAA-WR0", ["roster_status", "out"]] = ["RES", True]
    av.loc["BBB-WR0", "team"] = "CCC"                                   # traded
    av.loc["DDD-TE0", "report_status"] = "Questionable"
    df, models = C.week_props(offense, games, season, week, avail=av.reset_index())
    assert "AAA-WR0" not in set(df["player_id"])
    assert set(df[df["player_id"] == "BBB-WR0"]["team"]) == {"CCC"}
    assert set(df[df["player_id"] == "DDD-TE0"]["status"]) == {"Questionable"}
    assert df["actual"].isna().all()
    assert {"pass_yds", "rush_yds", "rec_yds", "receptions", "any_td", "pass_tds"} <= set(df["prop"])
    # The exported distribution reproduces the model's own probability of clearing a line.
    r = df[df["prop"] == "rec_yds"].iloc[0]
    browser = 1 - np.interp(r["fair_line"], r["q"], C.JSON_PROBS, left=0.0, right=1.0)
    assert browser == pytest.approx(r["fair_over"], abs=0.02)
    c = df[df["prop"] == "receptions"].iloc[0]
    assert sum(c["pmf"][int(c["fair_line"] + 0.5):]) == pytest.approx(c["fair_over"], abs=0.002)
    assert set(df[df["prop"] == "rec_yds"]["consistency"]) <= {"steady", "typical", "boom or bust"}
    assert df[df["prop"] == "pass_yds"]["consistency"].isna().all()
    td = df[df["prop"] == "any_td"].iloc[0]
    assert 1 - td["pmf"][0] == pytest.approx(td["chance"], abs=0.001)
    text = props_cli.render(df, season, week)
    assert "(Q)" in text and "1-800-GAMBLER" in text
    out = props_cli.to_json(df, season, week)
    assert len(out["probs"]) == len(r["q"]) and out["props"]["rec_yds"]["kind"] == "yards"
    assert "DDD TE0" in props_cli.lookup(df, models, "ddd te0", "rec_yds", 30.5)


def test_consistency_scale_widens_the_range_and_keeps_the_mean(panel):
    model = P.fit_all(panel)["rec_yds"]
    wr = panel[(panel["season"] == panel["season"].max()) & (panel["position"] == "WR")]
    wr = wr[P.eligible(wr, model.prop)]
    scale = model.spread_scale(wr)
    assert ((scale >= P.SPREAD_CLIP[0]) & (scale <= P.SPREAD_CLIP[1])).all()
    mu = float(model.expected(wr)[0])
    steady, usual, wild = (model.quantiles("WR", mu, s) for s in (0.7, 1.0, 1.4))
    width = lambda q: np.interp(0.9, P.QUANTILES, q) - np.interp(0.1, P.QUANTILES, q)
    assert width(steady) < width(usual) < width(wild)
    assert steady.mean() == pytest.approx(usual.mean(), rel=1e-6) == pytest.approx(wild.mean(), rel=1e-6)
    assert (steady >= 0).all() or (usual < 0).any()
    assert model.prob_over("WR", mu, mu * 2, 1.4) > model.prob_over("WR", mu, mu * 2, 0.7)
    # Props without the adjustment always report a typical range.
    qb = panel[(panel["season"] == panel["season"].max()) & (panel["position"] == "QB")]
    assert (P.fit_all(panel)["pass_yds"].spread_scale(qb) == 1.0).all()


def test_consistency_labels():
    assert C.consistency_label(None) is None
    assert [C.consistency_label(s) for s in (0.9, 1.0, 1.1)] == ["steady", "typical", "boom or bust"]

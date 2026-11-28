"""Real final scores and ESPN lines from the scoreboard: schedule.season_games and its use in predict/card.

Fixtures are real ESPN scoreboard responses trimmed to a few events and the fields cfbmodel reads:
``espn_2025_week5.json`` (2025 regular season week 5, three finals) and ``espn_2025_post.json`` (two
2025 bowls, one at a neutral site). Past games come back with scores and without odds.
"""
import copy
import json
import logging
import os
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from cfbmodel import card, pbp, predict, schedule
from test_cfb_card import _games, ctx  # noqa: F401 - ctx is a module fixture
from test_cfb_predict import make_tg_all, scored_game

FIX = Path(__file__).parent / "fixtures"
AFTER_2025 = pd.Timestamp("2026-11-28", tz="UTC")


def _load(name):
    return json.loads((FIX / name).read_text())


@pytest.fixture
def week5():
    return _load("espn_2025_week5.json")


@pytest.fixture
def post():
    return _load("espn_2025_post.json")


def _fake_fetch(week5, post, calls):
    empty = copy.deepcopy(week5)
    empty["events"] = []

    def fetch(params):
        calls.append(dict(params))
        if params["seasontype"] == schedule.POSTSEASON:
            return post
        return week5 if params["week"] == 5 else empty
    return fetch


# ---------------------------------------------------------------------------------------------
# schedule.season_games
# ---------------------------------------------------------------------------------------------

def test_season_games_one_call_per_week_then_cached(week5, post, tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(schedule, "_fetch", _fake_fetch(week5, post, calls))
    g = schedule.season_games(2025, tmp_path, now=AFTER_2025)
    assert len(calls) == 17  # 16 regular-season weeks and one postseason call (bowls and CFP)
    assert sorted(c["week"] for c in calls if c["seasontype"] == schedule.REGULAR) == list(range(1, 17))
    assert [c for c in calls if c["seasontype"] == schedule.POSTSEASON] == [
        {"groups": 80, "limit": 300, "dates": 2025, "seasontype": 3, "week": 1}]
    assert list(g.columns) == schedule.SEASON_COLUMNS
    assert len(g) == 5 and g["game_id"].is_unique
    assert str(g["start_date"].dt.tz) == "UTC"

    g = g.set_index("game_id")
    army = g.loc[401762471]  # Army 6 at East Carolina 28
    assert (army["home"], army["away"], army["home_points"], army["away_points"]) == ("East Carolina", "Army", 28.0, 6.0)
    assert army["completed"] and not army["neutral"] and army["week"] == 5 and army["season_type"] == 2
    assert army["home_conf"] == army["away_conf"] == "American Athletic"
    assert np.isnan(army["spread_close"]) and army["line_provider"] is None  # past games carry no odds
    bowl = g.loc[401778302]  # Boise State 10, Washington 38 at a neutral site
    assert bowl["neutral"] and bowl["season_type"] == 3
    assert (bowl["home_conf"], bowl["away_conf"]) == ("Big Ten", "Mountain West")
    assert (bowl["home_points"], bowl["away_points"]) == (38.0, 10.0)

    again = schedule.season_games(2025, tmp_path, now=AFTER_2025)
    assert len(calls) == 17  # a finished season is read back from disk
    pd.testing.assert_frame_equal(again.set_index("game_id"), g)


def test_season_games_refetches_only_open_weeks(week5, post, tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(schedule, "_fetch", _fake_fetch(week5, post, calls))
    now = pd.Timestamp("2025-09-25 12:00", tz="UTC")  # week 5 under way
    schedule.season_games(2025, tmp_path, now=now)
    assert len(calls) == 17
    schedule.season_games(2025, tmp_path, now=now)
    assert len(calls) == 17  # fresh within MAX_AGE_S
    fetched = pd.Timestamp("2025-09-20", tz="UTC").timestamp()  # before week 4 ended
    for f in (tmp_path / "espn").glob("dates-*.json"):
        os.utime(f, (fetched, fetched))
    calls.clear()
    schedule.season_games(2025, tmp_path, now=now)
    weeks = sorted(c["week"] for c in calls if c["seasontype"] == schedule.REGULAR)
    assert weeks == list(range(4, 17)) and len(calls) == 14  # weeks 1-3 were fetched after they ended
    assert not list((tmp_path / "espn").glob("season_*"))  # an unfinished season is not frozen


def test_season_games_lines_open_close_and_prices(tmp_path, monkeypatch):
    board = _load("espn_scoreboard.json")  # 2026 week 13, pregame odds from DraftKings
    empty = copy.deepcopy(board)
    empty["events"] = []
    monkeypatch.setattr(schedule, "_fetch", lambda p: board if p.get("week") == 13 and p["seasontype"] == 2 else empty)
    g = schedule.season_games(2026, tmp_path, now=pd.Timestamp("2026-11-28", tz="UTC")).set_index("game_id")
    nd = g.loc[401858314]  # Notre Dame at Syracuse: home +25.5 (opened +21), total 53.5 (opened 54.5)
    assert (nd["spread_close"], nd["total_close"], nd["spread_open"], nd["total_open"]) == (25.5, 53.5, 21.0, 54.5)
    assert (nd["spread_price_home"], nd["spread_price_away"]) == (-115.0, -105.0)
    assert (nd["total_price_over"], nd["total_price_under"]) == (-115.0, -105.0)
    assert nd["line_provider"] == "DraftKings"
    final = g.loc[401866470]  # final with no odds
    assert final["completed"] and final["home_points"] == 17.0
    assert np.isnan(final["spread_open"]) and np.isnan(final["spread_price_home"])


def test_final_keeps_pregame_open_and_prices(tmp_path, monkeypatch):
    board = _load("espn_scoreboard.json")
    done = copy.deepcopy(board)
    comp = done["events"][0]["competitions"][0]  # Notre Dame at Syracuse goes final
    comp["odds"] = None
    comp["status"]["type"].update(completed=True, state="post")
    for c in comp["competitors"]:
        c["score"] = "10" if c["homeAway"] == "home" else "35"
    responses = iter([board, done])
    monkeypatch.setattr(schedule, "_fetch", lambda p: next(responses))
    schedule.espn_week(2026, 13, tmp_path)
    for f in (tmp_path / "espn").glob("dates-*.json"):
        f.unlink()
    g = schedule.espn_week(2026, 13, tmp_path).set_index("game_id").loc[401858314]
    assert g["completed"] and (g["home_points"], g["away_points"]) == (10.0, 35.0)
    assert (g["spread_line"], g["spread_open"], g["spread_price_home"], g["total_price_under"]) == (
        25.5, 21.0, -115.0, -105.0)


def test_odds_detail_prices():
    comp = {"odds": [{"pointSpread": {"home": {"close": {"line": "-3", "odds": "EVEN"}},
                                      "away": {"close": {"line": "+3", "odds": "+105"}, "open": {"line": "+2.5"}}},
                      "total": {"over": {"open": {"line": "o48", "odds": "-110"}}}}]}
    d = schedule.odds_detail(comp)
    assert d["spread_price_home"] == 100.0 and d["spread_price_away"] == 105.0
    assert d["spread_open"] == -2.5  # from the away opener when the home one is missing
    assert d["total_open"] == 48.0 and np.isnan(d["total_price_over"])  # the close price, not the open
    assert all(np.isnan(v) for v in schedule.odds_detail({}).values())


# ---------------------------------------------------------------------------------------------
# predict: real scores in team games, ESPN lines in season output
# ---------------------------------------------------------------------------------------------

def _espn(rows):
    out = pd.DataFrame(rows)
    for c in schedule.SEASON_COLUMNS:
        if c not in out:
            out[c] = None if c in ("home", "away", "home_conf", "away_conf", "line_provider") else np.nan
    return out[schedule.SEASON_COLUMNS]


def _fake_pbp(monkeypatch):
    def fake_load(season, data_dir):
        return pd.concat([scored_game(10 + season % 10, season),
                          scored_game(20 + season % 10, season, "A", "C", ("fbs", "fcs"))])
    monkeypatch.setattr(pbp, "load", fake_load)
    monkeypatch.setattr(pbp, "current_season", lambda *a: 2026)


def test_team_games_all_prefers_espn_scores(tmp_path, monkeypatch, caplog):
    _fake_pbp(monkeypatch)
    # game 19: ESPN has A (id 1, home) 24, B 21; game 29 has no ESPN score and keeps the rebuilt 10-7
    espn = _espn([{"game_id": 19, "season": 2019, "home_id": 1, "away_id": 2, "completed": True,
                   "home_points": 24.0, "away_points": 21.0}])
    monkeypatch.setattr(schedule, "season_games", lambda season, data_dir: espn if season == 2019 else espn.iloc[:0])
    with caplog.at_level(logging.INFO, logger="cfbmodel.predict"):
        out = predict.team_games_all([2019], tmp_path)
    a = out[(out["game_id"] == 19) & (out["team"] == "A")].iloc[0]
    b = out[(out["game_id"] == 19) & (out["team"] == "B")].iloc[0]
    assert (a["team_score"], a["opp_score"], b["team_score"], b["opp_score"]) == (24, 21, 21, 24)
    c = out[(out["game_id"] == 29) & (out["team"] == "A")].iloc[0]
    assert (c["team_score"], c["opp_score"]) == (10, 7)
    assert "1 of 2 games" in caplog.text


def test_team_games_all_matches_espn_by_team_id(tmp_path, monkeypatch):
    _fake_pbp(monkeypatch)  # ESPN lists B (id 2) as the home side of a neutral-site game
    espn = _espn([{"game_id": 19, "season": 2019, "home_id": 2, "away_id": 1, "completed": True,
                   "home_points": 21.0, "away_points": 24.0}])
    monkeypatch.setattr(schedule, "season_games", lambda season, data_dir: espn)
    out = predict.team_games_all([2019], tmp_path)
    a = out[(out["game_id"] == 19) & (out["team"] == "A")].iloc[0]
    assert (a["team_score"], a["opp_score"]) == (24, 21)


def test_team_games_all_without_team_ids_uses_margin_to_orient(tmp_path, monkeypatch):
    real = scored_game

    def no_ids(season, data_dir):  # old postseason play-by-play has no team ids
        p = real(10 + season % 10, season)
        p[["home_team_id", "away_team_id"]] = np.nan
        return p
    monkeypatch.setattr(pbp, "load", no_ids)
    monkeypatch.setattr(pbp, "current_season", lambda *a: 2026)
    # ESPN calls B the home side; the rebuilt margin (A by 3) says A is ESPN's away team
    espn = _espn([{"game_id": 19, "season": 2019, "home_id": 2, "away_id": 1, "completed": True,
                   "home_points": 21.0, "away_points": 24.0}])
    monkeypatch.setattr(schedule, "season_games", lambda season, data_dir: espn)
    out = predict.team_games_all([2019], tmp_path)
    a = out[out["team"] == "A"].iloc[0]
    assert (a["team_score"], a["opp_score"]) == (24, 21)


def test_team_games_all_falls_back_without_espn(tmp_path, monkeypatch):
    _fake_pbp(monkeypatch)

    def down(season, data_dir):
        raise OSError("offline")
    monkeypatch.setattr(schedule, "season_games", down)
    out = predict.team_games_all([2019], tmp_path)
    a = out[(out["game_id"] == 19) & (out["team"] == "A")].iloc[0]
    assert (a["team_score"], a["opp_score"]) == (10, 7)


def test_season_output_has_espn_lines(tmp_path, monkeypatch):
    tg = make_tg_all([2016, 2017])
    monkeypatch.setattr(predict, "team_games_all", lambda seasons, data_dir: tg[tg["season"].isin(seasons)])
    homes = tg[(tg["season"] == 2017) & (tg["is_home"] == 1)].drop_duplicates("game_id")
    first, second = homes.iloc[0], homes.iloc[1]
    espn = _espn([
        {"game_id": first.game_id, "season": 2017, "home_id": first.team_id, "away_id": first.opp_id,
         "completed": True, "spread_close": -6.5, "total_close": 54.5, "spread_open": -7.0, "total_open": 52.0,
         "spread_price_home": -115.0, "spread_price_away": -105.0, "total_price_over": -110.0,
         "total_price_under": -110.0, "line_provider": "DraftKings"},
        # sides swapped against the play-by-play: the spread flips and the prices swap
        {"game_id": second.game_id, "season": 2017, "home_id": second.opp_id, "away_id": second.team_id,
         "completed": True, "spread_close": 3.0, "spread_price_home": -120.0, "spread_price_away": 100.0},
    ])
    monkeypatch.setattr(predict, "espn_games", lambda seasons, data_dir: espn)
    out = predict.season(2017, tmp_path, {"train_seasons": [2016]}).set_index("game_id")
    for c in predict.ESPN_LINE_COLS:
        assert c in out.columns
    r = out.loc[first.game_id]
    assert (r["spread_close_espn"], r["total_close_espn"], r["spread_open"], r["total_open"]) == (-6.5, 54.5, -7.0, 52.0)
    assert (r["spread_price_home"], r["spread_price_away"], r["line_provider_espn"]) == (-115.0, -105.0, "DraftKings")
    assert r["spread"] == -7.0 and r["over_under"] == 55.0  # play-by-play lines kept
    s = out.loc[second.game_id]
    assert (s["spread_close_espn"], s["spread_price_home"], s["spread_price_away"]) == (-3.0, 100.0, -120.0)
    other = out.drop(index=[first.game_id, second.game_id])
    assert other["spread_close_espn"].isna().all() and other["line_provider_espn"].isna().all()


# ---------------------------------------------------------------------------------------------
# card
# ---------------------------------------------------------------------------------------------

def test_card_final_without_espn_score_takes_team_games_score(ctx, tmp_path):  # noqa: F811
    tg = ctx["tg_all"]
    row = tg[(tg["season"] == 2018) & (tg["is_home"] == 1)].iloc[0]
    games = _games(completed=True)
    games.loc[0, "game_id"] = int(row["game_id"])
    games.loc[0, ["home_points", "away_points"]] = np.nan
    g = card.week_card(2018, 20, tmp_path, ctx=ctx, games=games)[0]
    assert g["final"] and (g["home_score"], g["away_score"]) == (int(row["team_score"]), int(row["opp_score"]))


def test_context_trains_on_every_default_season(tmp_path, monkeypatch):
    tg = make_tg_all([2015, 2016, 2017, 2018])
    tg.loc[tg["season"] == 2017, "team_score"] += 100  # no season is dropped for its scores any more
    monkeypatch.setattr(predict, "team_games_all", lambda seasons, data_dir: tg[tg["season"].isin(seasons)])
    ctx = card.context(2018, tmp_path)
    assert ctx["train"] == [2015, 2016, 2017]

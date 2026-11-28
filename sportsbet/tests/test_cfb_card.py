"""ESPN schedule parsing, the weekly card, rankings and the cfb.json the college page reads."""
import copy
import io
import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from cfbmodel import card, cli, dist, predict, schedule, site
from test_cfb_predict import TEAMS, make_tg_all

FIXTURE = Path(__file__).parent / "fixtures" / "espn_scoreboard.json"


@pytest.fixture
def board():
    return json.loads(FIXTURE.read_text())


@pytest.fixture
def no_network(monkeypatch):
    def boom(*a, **k):
        raise AssertionError("network used in a test")
    monkeypatch.setattr("urllib.request.urlopen", boom)


# ---------------------------------------------------------------------------------------------
# cfb.json contract validator (docs/superpowers/plans/cfb-site-json.md)
# ---------------------------------------------------------------------------------------------

def _is_num(x):
    return x is None or (isinstance(x, (int, float)) and not isinstance(x, bool) and np.isfinite(x))


def validate_cfb(data: dict) -> None:
    """Raise AssertionError when ``data`` breaks the cfb.json contract."""
    assert set(data) == {"generated_at", "season", "week", "weeks", "status", "games", "rankings", "backtest"}
    pd.Timestamp(data["generated_at"])
    assert isinstance(data["season"], int) and isinstance(data["week"], int)
    assert data["weeks"] == sorted(data["weeks"]) and data["week"] in data["weeks"]
    assert all(isinstance(w, int) for w in data["weeks"])
    assert set(data["status"]) == {"passed", "note"} and data["status"]["passed"] in (True, False, None)
    assert isinstance(data["status"]["note"], str) and data["status"]["note"]
    ids = set()
    for g in data["games"]:
        assert set(g) == set(card.GAME_KEYS), set(g) ^ set(card.GAME_KEYS)
        assert isinstance(g["game_id"], int) and g["game_id"] not in ids
        ids.add(g["game_id"])
        assert g["week"] in data["weeks"]
        if g["start"] is not None:
            assert g["start"].endswith("Z")
            pd.Timestamp(g["start"])
        assert isinstance(g["home"], str) and isinstance(g["away"], str)
        for k in ("home_id", "away_id", "home_score", "away_score"):
            assert g[k] is None or (isinstance(g[k], int) and not isinstance(g[k], bool))
        for k in ("spread_line", "total_line", "model_margin", "model_total", "home_cover_prob", "over_prob",
                  "edge_spread", "edge_total"):
            assert _is_num(g[k]), (k, g[k])
        for k in ("home_cover_prob", "over_prob"):
            assert g[k] is None or 0 <= g[k] <= 1
        assert g["pick_spread"] in ("home", "away", None) and g["pick_total"] in ("over", "under", None)
        if g["spread_line"] is None or g["model_margin"] is None:
            assert g["pick_spread"] is None
        if g["total_line"] is None or g["model_total"] is None:
            assert g["pick_total"] is None
        for m, p, e, h in (("spread", "home_cover_prob", "edge_spread", "ats_hit"),
                           ("total", "over_prob", "edge_total", "ou_hit")):
            if g[f"pick_{m}"] is None:  # no pick: no chance, no edge, no grade
                assert g[p] is None and g[e] is None and g[h] is None
        for k in ("neutral", "bet_spread", "bet_total", "final"):
            assert isinstance(g[k], bool), k
        for k in ("ats_hit", "ou_hit"):
            assert g[k] in (True, False, None)
        if not g["final"]:
            assert g["home_score"] is None and g["ats_hit"] is None and g["ou_hit"] is None
        if g["bet_spread"]:
            assert g["pick_spread"] is not None
        for k in ("home_conf", "away_conf", "line_provider"):
            assert g[k] is None or isinstance(g[k], str)
    ranks = [r["rank"] for r in data["rankings"]]
    assert ranks == list(range(1, len(ranks) + 1))
    for r in data["rankings"]:
        assert set(r) == set(card.RANK_KEYS)
        assert isinstance(r["team"], str) and _is_num(r["off"]) and _is_num(r["def"]) and _is_num(r["net"])
    nets = [r["net"] for r in data["rankings"] if r["net"] is not None]
    assert nets == sorted(nets, reverse=True)
    b = data["backtest"]
    assert {"tune_seasons", "scored_seasons", "passed", "markets"} <= set(b)
    for m in ("spread", "total"):
        mk = b["markets"][m]
        assert {"n", "win_rate", "roi", "clv_mean", "clv_t", "by_season"} <= set(mk)
        for s in mk["by_season"]:
            assert {"season", "n", "win_rate", "roi"} <= set(s)
    json.dumps(data, allow_nan=False)


# ---------------------------------------------------------------------------------------------
# schedule
# ---------------------------------------------------------------------------------------------

def test_parse_real_scoreboard(board):
    g = schedule.parse(board).set_index("game_id")
    assert len(g) == 5
    assert list(schedule.COLUMNS[1:]) == list(g.columns)
    assert str(g["start_date"].dt.tz) == "UTC"
    assert set(g["season"]) == {2026} and set(g["week"]) == {13}

    nd = g.loc[401858314]  # Notre Dame -25.5 at Syracuse: home spread +25.5
    assert (nd["home"], nd["away"], nd["home_id"], nd["away_id"]) == ("Syracuse", "Notre Dame", 183, 87)
    assert nd["spread_line"] == 25.5 and nd["total_line"] == 53.5 and nd["line_provider"] == "DraftKings"
    assert (nd["home_conf"], nd["away_conf"]) == ("ACC", "FBS Independents")
    assert not nd["completed"] and np.isnan(nd["home_points"]) and not nd["neutral"]

    osu = g.loc[401858540]  # Ohio State -10 at home
    assert osu["spread_line"] == -10.0 and osu["total_line"] == 36.5
    assert osu["home_conf"] == osu["away_conf"] == "Big Ten"

    final = g.loc[401866470]  # Kent State 12 at Eastern Michigan 17, no odds once final
    assert final["completed"] and (final["home_points"], final["away_points"]) == (17.0, 12.0)
    assert np.isnan(final["spread_line"]) and final["line_provider"] is None
    assert g.loc[401864549, "away_conf"] == "Mountain West"  # North Dakota State, new to FBS in 2026


def test_parse_odds_fallbacks():
    comp = {"odds": [{"provider": {"name": "Book"}, "details": "OSU -10", "overUnder": 44.5}]}
    assert schedule.parse_odds(comp, "OSU", "MICH") == (-10.0, 44.5, "Book")
    comp = {"odds": [{"provider": {"name": "Book"}, "details": "ND -25.5"}]}
    spread, total, prov = schedule.parse_odds(comp, "SYR", "ND")
    assert spread == 25.5 and np.isnan(total) and prov == "Book"
    assert schedule.parse_odds({"odds": [{"details": "EVEN"}]}, "A", "B")[0] == 0.0
    assert schedule.parse_odds({"odds": [{"spread": -3.0, "pointSpread": {"home": {"close": {"line": "-3.5"}}}}]},
                               "A", "B")[0] == -3.5
    assert np.isnan(schedule.parse_odds({}, "A", "B")[0])


def test_neutral_site_flag(board):
    b = copy.deepcopy(board)
    b["events"][0]["competitions"][0]["neutralSite"] = True
    assert schedule.parse(b).set_index("game_id").loc[401858314, "neutral"]


def test_espn_week_caches_responses(board, tmp_path, monkeypatch):
    calls = []

    def fake(params):
        calls.append(params)
        return board
    monkeypatch.setattr(schedule, "_fetch", fake)
    a = schedule.espn_week(2026, 13, tmp_path)
    b = schedule.espn_week(2026, 13, tmp_path)
    assert len(calls) == 1 and len(a) == 5
    assert calls[0] == {"groups": 80, "limit": 300, "dates": 2026, "seasontype": 2, "week": 13}
    pd.testing.assert_frame_equal(a, b)


def test_final_keeps_last_pregame_line(board, tmp_path, monkeypatch):
    pre = copy.deepcopy(board)
    post = copy.deepcopy(board)
    comp = post["events"][1]["competitions"][0]  # Michigan at Ohio State, now final without odds
    comp["odds"] = None
    comp["status"]["type"].update(completed=True, state="post")
    for c in comp["competitors"]:
        c["score"] = "24" if c["homeAway"] == "home" else "20"
    responses = iter([pre, post])
    monkeypatch.setattr(schedule, "_fetch", lambda params: next(responses))
    schedule.espn_week(2026, 13, tmp_path)
    for f in (tmp_path / "espn").glob("dates-*.json"):
        f.unlink()  # force a refetch
    g = schedule.espn_week(2026, 13, tmp_path).set_index("game_id").loc[401858540]
    assert g["completed"] and g["home_points"] == 24.0
    assert (g["spread_line"], g["total_line"], g["line_provider"]) == (-10.0, 36.5, "DraftKings")


def test_current_week_and_weeks(board, tmp_path, monkeypatch):
    monkeypatch.setattr(schedule, "_fetch", lambda params: board)
    info = schedule.season_info(2026, tmp_path)
    assert schedule.current_week(2026, tmp_path) == 13
    assert info["weeks"][:3] == [1, 2, 3] and info["weeks"][-1] == 15
    assert schedule.current_week(2025, tmp_path) == 15  # a finished season: its last regular week


# ---------------------------------------------------------------------------------------------
# card
# ---------------------------------------------------------------------------------------------

def _ids():
    return {t: hash(t) % 10000 for t in TEAMS}


@pytest.fixture(scope="module")
def ctx():
    tg = make_tg_all([2016, 2017, 2018])
    prior_fn = lambda s: predict.carryover_prior(s, tg)  # noqa: E731
    hist = pd.concat([predict.walk(s, tg, prior_fn(s), {}) for s in (2016, 2017)])
    coef = predict.fit_points(hist)
    fit = dist.fit(card.dist_history(tg, [2016, 2017]))
    return {"season": 2018, "params": {}, "tg_all": tg, "prior": prior_fn(2018), "coef": coef, "dist_fit": fit}


def _games(week=20, completed=False, spread=-3.5, total=55.5, start="2018-12-20 17:00"):
    ids = _ids()
    rows = []
    for i, (h, a) in enumerate([("T00", "T01"), ("T02", "T03"), ("T04", "T05")]):
        rows.append({"game_id": 9000 + i, "season": 2018, "week": week, "season_type": 2,
                     "start_date": pd.Timestamp(start, tz="UTC") + pd.Timedelta(hours=i),
                     "home": h, "away": a, "home_id": ids[h], "away_id": ids[a], "home_abbr": h, "away_abbr": a,
                     "home_conf": "SEC", "away_conf": "Big Ten" if i else "SEC", "neutral": i == 2,
                     "completed": completed, "state": "post" if completed else "pre",
                     "home_points": 30.0 if completed else np.nan, "away_points": 20.0 if completed else np.nan,
                     "spread_line": spread if i < 2 else np.nan, "total_line": total if i < 2 else np.nan,
                     "line_provider": "DraftKings" if i < 2 else None})
    return pd.DataFrame(rows, columns=schedule.COLUMNS)


def test_week_card_upcoming(ctx, tmp_path):
    out = card.week_card(2018, 20, tmp_path, ctx=ctx, games=_games())
    assert len(out) == 3
    for g in out:
        assert list(g) == card.GAME_KEYS
        assert not g["bet_spread"] and not g["bet_total"] and not g["final"]
    with_line = out[0]
    assert with_line["spread_line"] == -3.5 and with_line["line_provider"] == "DraftKings"
    # the pick follows the model against the line: home when the model's margin beats the spread
    assert with_line["pick_spread"] == ("home" if with_line["model_margin"] - 3.5 > 0 else "away")
    assert (with_line["home_cover_prob"] > 0.5) == (with_line["pick_spread"] == "home")
    assert (with_line["over_prob"] > 0.5) == (with_line["pick_total"] == "over")
    no_line = out[2]
    assert no_line["neutral"] and no_line["spread_line"] is None and no_line["pick_spread"] is None
    assert no_line["home_cover_prob"] is None and no_line["model_margin"] is not None


def test_week_card_uses_ratings_before_first_kickoff(ctx, tmp_path, monkeypatch):
    seen = []
    real = predict.ratings.as_of

    def spy(tg_all, cutoff, prior, params):
        seen.append(pd.Timestamp(cutoff))
        return real(tg_all, cutoff, prior, params)
    monkeypatch.setattr(predict.ratings, "as_of", spy)
    games = _games(start="2018-10-01 17:00")
    card.week_card(2018, 20, tmp_path, ctx=ctx, games=games)
    assert seen == [games["start_date"].min()]


def test_bets_only_when_rulebook_passed(ctx, tmp_path):
    games = _games()
    loose = {"spread": {"edge_min": -1.0, "segment": "all"}, "total": {"edge_min": -1.0, "segment": "all"}}
    failed = card.week_card(2018, 20, tmp_path, ctx=ctx, games=games, rulebook={**loose, "passed": False})
    assert not any(g["bet_spread"] or g["bet_total"] for g in failed)
    passed = card.week_card(2018, 20, tmp_path, ctx=ctx, games=games, rulebook={**loose, "passed": True})
    assert [g["bet_spread"] for g in passed] == [True, True, False]  # the third game has no line
    p4 = {"spread": {"edge_min": -1.0, "segment": "p4"}, "passed": True}
    only = card.week_card(2018, 20, tmp_path, ctx=ctx, games=games, rulebook=p4)
    assert [g["bet_spread"] for g in only] == [True, True, False] and not any(g["bet_total"] for g in only)
    strict = {"spread": {"edge_min": 5.0, "segment": "all"}, "passed": True}
    assert not any(g["bet_spread"] for g in card.week_card(2018, 20, tmp_path, ctx=ctx, games=games,
                                                            rulebook=strict))


def test_finals_show_hit_or_miss(ctx, tmp_path):
    out = card.week_card(2018, 20, tmp_path, ctx=ctx, games=_games(completed=True))
    g = out[0]  # home won 30-20 against -3.5: home covered; total 50 under 55.5
    assert g["final"] and (g["home_score"], g["away_score"]) == (30, 20)
    assert g["ats_hit"] == (g["pick_spread"] == "home")
    assert g["ou_hit"] == (g["pick_total"] == "under")
    assert out[2]["ats_hit"] is None  # no line, no grade


def test_final_without_espn_line_takes_pbp_close(ctx, tmp_path):
    tg = ctx["tg_all"]
    gid = int(tg.loc[(tg["season"] == 2018) & (tg["is_home"] == 1), "game_id"].iloc[0])
    games = _games(completed=True, spread=np.nan, total=np.nan)
    games.loc[0, "game_id"] = gid
    games.loc[0, "line_provider"] = None
    g = card.week_card(2018, 20, tmp_path, ctx=ctx, games=games)[0]
    assert g["spread_line"] == -7.0 and g["total_line"] == 55.0 and g["line_provider"] == card.PBP_LINE
    assert g["ats_hit"] is not None


def test_final_pbp_close_flips_when_pbp_home_is_espn_away(ctx, tmp_path):
    tg = ctx["tg_all"]
    row = tg[(tg["season"] == 2018) & (tg["is_home"] == 1)].iloc[0]
    games = _games(completed=True, spread=np.nan, total=np.nan)
    games.loc[0, ["game_id", "home", "away", "home_id", "away_id"]] = [
        int(row["game_id"]), row["opp"], row["team"], int(row["opp_id"]), int(row["team_id"])]
    games.loc[0, "line_provider"] = None
    g = card.week_card(2018, 20, tmp_path, ctx=ctx, games=games)[0]
    assert g["spread_line"] == 7.0 and g["total_line"] == 55.0 and g["line_provider"] == card.PBP_LINE


def test_unrated_or_fcs_games_get_no_chance_pick_or_grade(ctx, tmp_path):
    games = _games(completed=True)
    games.loc[0, "away_id"] = 999999  # no FBS play-by-play row: rated as the pooled FCS team
    games.loc[1, "away_conf"] = "Big Sky"  # not an FBS conference
    out = card.week_card(2018, 20, tmp_path, ctx=ctx, games=games)
    for g in out[:2]:
        assert g["model_margin"] is not None and g["spread_line"] is not None
        for k in ("home_cover_prob", "over_prob", "pick_spread", "pick_total", "edge_spread", "edge_total",
                  "ats_hit", "ou_hit"):
            assert g[k] is None, k
        assert not g["bet_spread"] and not g["bet_total"]


def test_new_fbs_team_on_the_pooled_fcs_prior_is_unrated(ctx, tmp_path):
    prior = ctx["prior"].copy()
    prior.attrs["seeded"] = ["T01"]
    early = {**ctx, "prior": prior}
    out = card.week_card(2018, 20, tmp_path, ctx=early, games=_games(start="2018-08-01 17:00"))
    assert out[0]["pick_spread"] is None and out[0]["home_cover_prob"] is None  # T00 v T01
    assert out[1]["pick_spread"] is not None  # T02 v T03, both carried over


def test_shrink_history_is_out_of_sample(ctx, monkeypatch):
    tg = ctx["tg_all"]
    prior_fn = lambda s: predict.carryover_prior(s, tg)  # noqa: E731
    hist = pd.concat([predict.walk(s, tg, prior_fn(s), {}) for s in (2016, 2017, 2018)], ignore_index=True)
    fitted_on = []
    real = predict.fit_points

    def spy(history, kind=predict.DEFAULT_KIND):
        fitted_on.append(sorted(set(history["season"])))
        return real(history, kind)
    monkeypatch.setattr(predict, "fit_points", spy)
    h = card.shrink_history(hist)
    assert fitted_on == [[2016], [2016, 2017]]
    assert sorted(set(h["season"])) == [2017, 2018]  # the first season has nothing earlier to fit on
    assert {"spread_close", "total_close", "home_points", "away_points", "margin", "total"} <= set(h.columns)
    assert np.isfinite(h[["margin", "total"]].to_numpy()).all()
    n_games = tg[(tg["season"].isin([2017, 2018])) & (tg["is_home"] == 1) & tg["team_fbs"] & tg["opp_fbs"]]
    assert len(h) == len(n_games)


def test_context_fits_and_caches_betas(tmp_path, monkeypatch):
    tg = make_tg_all([2015, 2016, 2017, 2018])
    monkeypatch.setattr(predict, "team_games_all", lambda seasons, data_dir: tg[tg["season"].isin(seasons)])
    params = {"train_seasons": [2016, 2017], "prior_games": 2.0, "decay": 0.95, "regress": 0.2}
    c = card.context(2018, tmp_path, params)
    for k in ("beta_margin", "beta_total"):
        assert 0.0 <= c["dist_fit"][k] <= 1.0
    assert c["dist_fit"]["n_shrink_margin"] > 0
    again = card.context(2018, tmp_path, params)  # from the cache
    assert again["dist_fit"]["beta_margin"] == c["dist_fit"]["beta_margin"]
    assert list(again["prior"].index) == list(c["prior"].index)


def test_zero_beta_pick_follows_the_model_lean(ctx):
    zero = {**ctx["dist_fit"], "beta_margin": 0.0, "beta_total": 0.0}
    home = card._market(10.0, -3.5, "spread", zero)  # model home by 10 against home -3.5: leans home
    assert home["pick"] == "home" and home["prob"] == pytest.approx(0.5, abs=1e-6) and home["edge"] < 0
    assert card._market(1.0, -3.5, "spread", zero)["pick"] == "away"
    assert card._market(-10.0, -3.5, "spread", zero)["pick"] == "away"
    assert card._market(50.0, 55.5, "total", zero)["pick"] == "under"
    assert card._market(60.0, 55.5, "total", zero)["pick"] == "over"


def test_grade():
    assert card.grade("home", 3.5) is True and card.grade("away", 3.5) is False
    assert card.grade("under", -2.0) is True and card.grade("over", 0.0) is None
    assert card.grade(None, 5.0) is None


def test_rankings(ctx):
    cut = pd.Timestamp("2018-12-31", tz="UTC")
    out = card.rankings(2018, None, ctx=ctx, cutoff=cut, conferences={_ids()["T00"]: "SEC"})
    assert [r["rank"] for r in out] == list(range(1, len(TEAMS) + 1))
    assert {r["team"] for r in out} == set(TEAMS)  # FCS is not ranked
    nets = [r["net"] for r in out]
    assert nets == sorted(nets, reverse=True)
    assert abs(np.mean(nets)) < 1.5  # net is against the average FBS team
    assert next(r for r in out if r["team"] == "T00")["conf"] == "SEC"
    assert all(set(r) == set(card.RANK_KEYS) for r in out)


def test_bad_score_seasons_detected():
    tg = make_tg_all([2016, 2017])
    tg.loc[tg["season"] == 2017, "team_score"] += 100
    share = card.bad_score_share(tg)
    assert share[2016] == 0.0 and share[2017] == 1.0


# ---------------------------------------------------------------------------------------------
# site and CLI
# ---------------------------------------------------------------------------------------------

@pytest.fixture
def fake_site(ctx, monkeypatch, board):
    """site.build without network: two weeks of synthetic games, ESPN calendar from the fixture."""
    info = {"week": 21, "season_type": 2, "weeks": [19, 20, 21, 22]}
    monkeypatch.setattr(schedule, "season_info", lambda season, data_dir: info)
    monkeypatch.setattr(schedule, "current_week", lambda season, data_dir: 21)

    def week(season, w, data_dir, season_type=2):
        g = _games(week=w, completed=w < 21)
        g["game_id"] += 10 * w
        return g if w != 19 else g.iloc[0:0]
    monkeypatch.setattr(schedule, "espn_week", week)
    monkeypatch.setattr(card, "context", lambda *a, **k: ctx)
    monkeypatch.setattr(card, "_conferences", lambda *a, **k: {})


def test_build_matches_contract(fake_site, tmp_path, no_network):
    data = site.build(2018, None, tmp_path, result_path=tmp_path / "missing.json")
    validate_cfb(data)
    assert data["week"] == 21 and data["weeks"] == [20, 21]  # week 19 had no games; 22 is not here yet
    assert data["status"] == {"passed": None, "note": site.RESEARCH_NOTE}
    assert data["backtest"]["passed"] is None and data["backtest"]["markets"]["spread"]["n"] == 0
    assert any(g["final"] for g in data["games"] if g["week"] == 20)
    assert not any(g["bet_spread"] or g["bet_total"] for g in data["games"])


def test_build_reads_result_json(fake_site, tmp_path):
    result = {"passed": True, "rulebook": {"spread": {"edge_min": -1.0, "segment": "all"}},
              "backtest": {"markets": {"spread": {"n": 450, "win_rate": 0.54, "roi": 0.03, "clv_mean": 0.4,
                                                  "clv_t": 2.3, "by_season": [{"season": 2023, "n": 150,
                                                                               "win_rate": 0.55, "roi": 0.05}]}}}}
    path = tmp_path / "RESULT.json"
    path.write_text(json.dumps(result))
    data = site.build(2018, 21, tmp_path, result_path=path)
    validate_cfb(data)
    assert data["status"]["passed"] is True and data["backtest"]["passed"] is True
    assert data["backtest"]["markets"]["spread"]["n"] == 450
    assert data["backtest"]["markets"]["total"]["n"] == 0
    assert any(g["bet_spread"] for g in data["games"])


def test_tuned_params_from_summary(tmp_path):
    path = tmp_path / "tune_summary.json"
    assert site.tuned_params(path) == site.TUNED_PARAMS  # no file: the packaged copy
    path.write_text(json.dumps({"params": {"prior_games": 8.0, "decay": 1.0, "regress": 0.5}}))
    assert site.tuned_params(path) == {"prior_games": 8.0, "decay": 1.0, "regress": 0.5}
    shipped = json.loads(site.TUNE_SUMMARY_PATH.read_text())["params"]
    assert site.TUNED_PARAMS == shipped  # the constant mirrors the committed tune


def test_build_uses_tuned_params(fake_site, ctx, tmp_path, monkeypatch):
    seen = []

    def spy(season, data_dir, params=None, prior_fn=None):
        seen.append(params)
        return ctx
    monkeypatch.setattr(card, "context", spy)
    site.build(2018, 21, tmp_path, result_path=tmp_path / "none.json")
    assert seen == [site.tuned_params()]
    site.build(2018, 21, tmp_path, params={"prior_games": 2.0}, result_path=tmp_path / "none.json")
    assert seen[-1] == {"prior_games": 2.0}


def test_site_cli_writes_valid_json(fake_site, tmp_path, monkeypatch, no_network):
    monkeypatch.delenv("CFBD_API_KEY", raising=False)  # the page must build without a key
    out = tmp_path / "dist" / "data" / "cfb.json"
    assert cli.main(["site", "--json", str(out), "--season", "2018", "--data-dir", str(tmp_path),
                     "--result", str(tmp_path / "none.json")]) == 0
    validate_cfb(json.loads(out.read_text()))


def test_predict_prints_research_only_when_not_passed(fake_site, tmp_path, capsys):
    path = tmp_path / "RESULT.json"
    path.write_text(json.dumps({"passed": False}))
    assert cli.main(["predict", "--season", "2018", "--week", "21", "--data-dir", str(tmp_path),
                     "--result", str(path)]) == 0
    out = capsys.readouterr().out
    assert out.splitlines()[0] == "Research only: did not pass the backtest"
    assert "T00" in out and "DraftKings" in out


def test_predict_json(fake_site, tmp_path, capsys):
    assert cli.main(["predict", "--season", "2018", "--week", "20", "--data-dir", str(tmp_path),
                     "--result", str(tmp_path / "none.json"), "--json"]) == 0
    data = json.loads(capsys.readouterr().out)
    assert data["week"] == 20 and len(data["games"]) == 3 and data["status"]["passed"] is None


def test_print_card_header_when_passed():
    buf = io.StringIO()
    cli.print_card([], 2026, 13, {"passed": True}, out=buf)
    assert "did not pass" not in buf.getvalue().splitlines()[0]

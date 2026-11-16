import numpy as np
import pandas as pd
import pytest

import datetime as dt
import json

from nflmodel import margin_model, qb_form, ratings, scoredist, site, totals_model, weather
from nflmodel.card import (BREAK_EVEN, fit_key_factors, game_card, market_home_prob, next_week, norm_cdf,
                           predict_all, season_record, week_card)
from nflmodel.cli import render

TEAMS = [f"T{i:02d}" for i in range(16)]  # the margin model needs 200 past games to fit


def row(**kw):
    base = dict(game_id="g", gameday="2026-11-15", home_team="HOM", away_team="AWY", pred_margin=3.0,
                pred_total=44.0, spread_line=6.5, total_line=41.5, home_moneyline=-280.0,
                away_moneyline=230.0, qb_assumed=0)
    return pd.Series({**base, **kw})


def test_norm_cdf_and_market_prob():
    assert norm_cdf(0) == 0.5
    assert norm_cdf(1.96) == pytest.approx(0.975, abs=1e-3)
    assert market_home_prob(-110, -110) == pytest.approx(0.5)
    assert market_home_prob(-280, 230) == pytest.approx(0.7086, abs=1e-3)
    assert market_home_prob(float("nan"), 100) is None


def test_card_home_favorite_model_lower_than_line():
    c = game_card(row())  # home favored by 6.5, model says home by 3: take the dog, and the over
    assert c["ml_pick"] == "HOM" and c["home_win_prob"] > 0.5
    assert c["ats_pick"] == "AWY +6.5" and c["ats_gap"] == pytest.approx(3.5)
    assert c["ou_pick"] == "Over 41.5" and c["ou_gap"] == pytest.approx(2.5)
    assert c["pred_home_score"] == pytest.approx(23.5) and c["pred_away_score"] == pytest.approx(20.5)
    assert 0.5 < c["ats_prob"] < BREAK_EVEN and 0.5 < c["ou_prob"] < BREAK_EVEN


def test_card_other_sides():
    c = game_card(row(pred_margin=9.0, pred_total=38.0))
    assert c["ats_pick"] == "HOM -6.5" and c["ou_pick"] == "Under 41.5"
    c = game_card(row(pred_margin=-8.0, spread_line=-4.5))  # away favored by 4.5, model says away by 8
    assert c["ml_pick"] == "AWY" and c["ats_pick"] == "AWY -4.5"
    c = game_card(row(pred_margin=-1.0, spread_line=-4.5))
    assert c["ats_pick"] == "HOM +4.5"
    c = game_card(row(spread_line=float("nan"), total_line=float("nan")))
    assert c["ats_pick"] is None and c["ou_pick"] is None


def test_season_record_grades_pushes_out():
    preds = pd.DataFrame([
        dict(season=2026, result=7.0, total=50.0, pred_margin=3.0, pred_total=44.0, spread_line=6.5, total_line=41.5),
        dict(season=2026, result=3.0, total=40.0, pred_margin=5.0, pred_total=44.0, spread_line=3.0, total_line=41.5),
        dict(season=2026, result=np.nan, total=np.nan, pred_margin=5.0, pred_total=44.0, spread_line=3.0, total_line=41.5),
    ])
    rec = season_record(preds, 2026)
    assert rec == {"games": 2, "su": (2, 2), "ats": (0, 1), "ou": (1, 2)}


def synthetic(seed=0, seasons=range(1999, 2006), weeks=14):
    rng = np.random.default_rng(seed)
    strength = dict(zip(TEAMS, rng.normal(0, 5, len(TEAMS))))
    games, stats, players = [], [], []
    for season in seasons:
        for week in range(1, weeks + 1):
            order = list(rng.permutation(TEAMS))
            for i in range(0, len(order), 2):
                home, away = order[i], order[i + 1]
                played = not (season == max(seasons) and week > weeks - 2)
                hs = int(max(0, rng.normal(23 + strength[home] / 2 + 1, 9)))
                as_ = int(max(0, rng.normal(21 + strength[away] / 2, 9)))
                gid = f"{season}_{week:02d}_{away}_{home}"
                games.append(dict(
                    game_id=gid, season=season, game_type="REG", week=week,
                    gameday=str(pd.Timestamp(season, 9, 7) + pd.Timedelta(weeks=week - 1))[:10],
                    gametime="13:00", away_team=away, home_team=home,
                    away_score=as_ if played else np.nan, home_score=hs if played else np.nan,
                    location="Home", result=hs - as_ if played else np.nan,
                    total=hs + as_ if played else np.nan, overtime=0, away_rest=7, home_rest=7,
                    away_moneyline=120.0, home_moneyline=-140.0, spread_line=2.5, total_line=44.5,
                    div_game=0, roof="outdoors", surface="grass", temp=60.0, wind=8.0,
                    away_qb_id=f"qb_{away}", home_qb_id=f"qb_{home}", stadium_id=f"st_{home}"))
                if played:
                    for team, opp, pts in ((home, away, hs), (away, home, as_)):
                        stats.append(dict(season=season, week=week, team=team, opponent_team=opp, game_id=gid,
                                          passing_epa=(pts - 21) / 3 + rng.normal(0, 2), rushing_epa=rng.normal(0, 2),
                                          attempts=34, carries=27, sacks_suffered=2))
                        players.append(dict(player_id=f"qb_{team}", position="QB", game_id=gid, team=team, attempts=34,
                                            sacks_suffered=2, carries=3, passing_epa=(pts - 21) / 3,
                                            rushing_epa=rng.normal(0, 1)))
    return pd.DataFrame(games), pd.DataFrame(stats), pd.DataFrame(players)


def backup_story(games, players):
    """Give one team a backup for a stretch of 2004 and have a starter leave a game early."""
    games = games.copy()
    team = TEAMS[0]
    for side in ("home", "away"):
        m = (games.season == 2004) & (games.week.between(3, 6)) & (games[f"{side}_team"] == team)
        games.loc[m, f"{side}_qb_id"] = "qb_backup"
    gid = games.loc[(games.season == 2004) & (games.week == 8) & ((games.home_team == team) | (games.away_team == team)), "game_id"].iloc[0]
    return games, {(gid, team): [(f"qb_{team}", 0.2), ("qb_backup", 0.8)]}


def test_models_cover_every_game_and_ignore_the_future_and_the_market():
    games, stats, players = synthetic()
    m = margin_model.predict_margins(games, stats, players).set_index("game_id")["pred_margin"]
    games, shares = backup_story(games, players)
    m = margin_model.predict_margins(games, stats, players, qb_shares=shares).set_index("game_id")["pred_margin"]
    t = totals_model.predict_totals(games, stats, player_stats=players, qb_shares=shares).set_index("game_id")["pred_total"]
    expected = set(games.loc[games.season >= 2003, "game_id"])
    assert set(m.index) == expected and set(t.index) == expected
    assert m.notna().all() and t.notna().all()

    cut = "2004-10-01"
    past_ids = games.loc[(games.gameday <= cut) & (games.season >= 2003), "game_id"]
    g2 = games[games.gameday <= cut].copy()
    s2 = stats[stats.game_id.isin(g2.game_id)]
    p2 = players[players.game_id.isin(g2.game_id)]
    for col in ("spread_line", "total_line", "home_moneyline", "away_moneyline"):
        g2[col] = g2[col] * 3 + 11  # the betting market must not matter
    sh2 = {k: v for k, v in shares.items() if k[0] in set(g2.game_id)}
    m2 = margin_model.predict_margins(g2, s2, p2, qb_shares=sh2).set_index("game_id")["pred_margin"]
    t2 = totals_model.predict_totals(g2, s2, player_stats=p2, qb_shares=sh2).set_index("game_id")["pred_total"]
    assert np.allclose(m.loc[past_ids], m2.loc[past_ids], atol=1e-9)
    assert np.allclose(t.loc[past_ids], t2.loc[past_ids], atol=1e-9)


def test_predict_all_and_render(tmp_path):
    games, stats, players = synthetic()
    for season, part in stats.groupby("season"):
        part.to_csv(tmp_path / f"stats_team_week_{season}.csv", index=False)
    players.to_csv(tmp_path / "stats_player_week_2005.csv", index=False)
    preds = predict_all(games, str(tmp_path))
    season, week = next_week(games)
    assert (season, week) == (2005, 13)
    cards = week_card(preds, season, week)
    assert len(cards) == 8
    text = render(cards, season, week, season_record(preds, season))
    assert "Moneyline" in text and "1-800-GAMBLER" in text

    # The web page's data file: valid JSON, every week present, results graded only for finished games.
    site.write(tmp_path / "out" / "site.json", preds, season, week)
    page = json.loads((tmp_path / "out" / "site.json").read_text())
    assert page["season"] == 2005 and page["week"] == 13 and len(page["weeks"]) == 14
    assert [w["week"] for w in page["by_week"]] == list(range(1, 13))
    played, upcoming = page["weeks"]["12"][0], page["weeks"]["13"][0]
    assert played["final"] and played["home_score"] is not None and played["ml_hit"] in (True, False, None)
    assert not upcoming["final"] and upcoming["home_score"] is None and upcoming["ats_hit"] is None
    assert page["record"] == json.loads(json.dumps(season_record(preds, season)))
    back = site.build(preds, season, week, backtest_first=2003)["backtest"]
    assert [s["season"] for s in back["seasons"]] == [2003, 2004]
    assert back["ats"][1] == sum(s["ats"][1] for s in back["seasons"]) and 0 < back["su_model"] < 1


def test_ratings_split_each_predicted_margin_exactly_and_rank_strong_teams_first():
    games, stats, players = synthetic()
    out = ratings.build(games, stats, players, 2005)
    teams = out["teams"]
    assert len(teams) == len(TEAMS) and [t["rank"] for t in teams] == list(range(1, len(TEAMS) + 1))
    assert teams[0]["power"] > 0 > teams[-1]["power"]
    for part in ratings.PARTS:  # every part is measured against the average team
        assert abs(sum(t[part] for t in teams)) < 1e-9
    assert all(abs(t["power"] - sum(t[p] for p in ratings.PARTS)) < 1e-9 for t in teams)
    assert all(abs(t["offense"] - t["pass_off"] - t["rush_off"]) < 1e-9 and abs(t["defense"] - t["pass_def"] - t["rush_def"]) < 1e-9 for t in teams)
    assert sorted(t["pass_def_rank"] for t in teams) == list(range(1, len(TEAMS) + 1))
    totals = {}
    totals_model.build_features(games, stats, state=totals)
    paced = ratings.build(games, stats, players, 2005, pace=totals["pace"])["teams"]
    assert set(totals["pace"]) == set(TEAMS) and abs(sum(t["pace"] for t in paced)) < 1e-9
    assert sorted(t["pace_rank"] for t in paced) == list(range(1, len(TEAMS) + 1))
    nxt = {t["team"]: t["next"] for t in teams}
    assert all(nxt[nxt[team]["opponent"]]["opponent"] == team and nxt[team]["home"] != nxt[nxt[team]["opponent"]]["home"] for team in nxt)
    assert all(sum(t["record"]) == 12 for t in teams)

    # The parts of a game add up to the model's own prediction, played or not.
    margins = margin_model.predict_margins(games, stats, players).set_index("game_id")["pred_margin"]
    ids = games.loc[games.season == 2005, "game_id"]
    assert set(out["games"]) == set(ids)
    assert all(abs(sum(out["games"][g].values()) - margins[g]) < 1e-9 for g in ids)

    # A team's power difference plus the game's circumstances is the same number.
    power = {t["team"]: t["power"] for t in teams}
    upcoming = games[(games.season == 2005) & games.home_score.isna() & (games.week == 13)].iloc[0]
    parts = out["games"][upcoming.game_id]
    assert abs(power[upcoming.home_team] - power[upcoming.away_team] - parts["team"] - parts["qb"]) < 1e-6

    preds = games.merge(margins.reset_index(), on="game_id").assign(pred_total=44.0, wind_mph=np.nan, wind_source=None, qb_assumed=False)
    page = site.build(preds, 2005, 13, backtest_first=2003, team_ratings=out)
    assert len(page["rankings"]["teams"]) == len(TEAMS)
    assert set(page["weeks"]["13"][0]["why"]) == {"team", "qb", "home", "rest"}
    assert site.build(preds, 2005, 13, backtest_first=2003)["rankings"] is None


def test_qb_form_rates_good_quarterbacks_higher_and_flags_backups():
    games, _, players = synthetic()
    best = players.groupby("player_id")["passing_epa"].mean().idxmax().removeprefix("qb_")
    last = games[(games.season == 2005) & (games.home_team == best)].iloc[-1:].copy()
    f = qb_form.qb_form_features(games, players).set_index("game_id")
    assert f.loc[last.game_id.iloc[0], "qb_form_diff"] > 0
    assert (f["qb_backup_diff"] == 0).all()  # every synthetic team keeps one starter
    swapped = games.copy()
    swapped.loc[swapped.game_id == last.game_id.iloc[0], "home_qb_id"] = "qb_new"
    f2 = qb_form.qb_form_features(swapped, players).set_index("game_id")
    assert f2.loc[last.game_id.iloc[0], "qb_backup_diff"] == 1
    assert f2.loc[last.game_id.iloc[0], "qb_form_diff"] < f.loc[last.game_id.iloc[0], "qb_form_diff"]


def test_wind_forecast_lowers_an_outdoor_total_only():
    games, stats, _ = synthetic()
    games["wind"] = np.where(games.total.notna(), 8.5 + (44 - games.total.fillna(44)) / 2, np.nan)  # wind lowers scoring
    todo = games[games.result.isna()].game_id.tolist()
    base = totals_model.predict_totals(games, stats).set_index("game_id")
    windy = totals_model.predict_totals(games, stats, wind_forecast={todo[0]: 30.0}).set_index("game_id")
    assert windy.loc[todo[0], "pred_total"] < base.loc[todo[0], "pred_total"] - 1
    assert windy.loc[todo[0], "wind_source"] == "forecast" and windy.loc[todo[0], "wind_mph"] == 30
    assert windy.loc[todo[1], "pred_total"] == base.loc[todo[1], "pred_total"]
    assert base.loc[todo[0], "wind_source"] == "climatology"
    with pytest.raises(ValueError):
        totals_model.predict_totals(games, stats, wind="guess")


def test_weather_parsing_and_forecast_lookup():
    assert weather._mph("10 to 15 mph") == 12.5 and weather._mph("5 mph") == 5 and weather._mph(None) is None
    start = weather.kickoff("2026-11-15", "13:00")
    assert start.utcoffset() == dt.timedelta(hours=-5) and weather.kickoff("2026-11-15", None) is None
    periods = [{"startTime": f"2026-11-15T{h:02d}:00:00-06:00", "windSpeed": f"{h} mph"} for h in range(24)]
    assert weather.wind_at(periods, start) == 13  # 1 PM Eastern is noon Central: hours 12, 13, 14
    soon = (dt.datetime.now(weather.EASTERN) + dt.timedelta(days=1)).replace(hour=13, minute=0)
    games = pd.DataFrame([
        dict(game_id="out", result=np.nan, location="Home", home_team="GB", gameday=str(soon.date()), gametime="13:00"),
        dict(game_id="dome", result=np.nan, location="Home", home_team="DET", gameday=str(soon.date()), gametime="13:00"),
        dict(game_id="done", result=3.0, location="Home", home_team="GB", gameday=str(soon.date()), gametime="13:00"),
        dict(game_id="far", result=np.nan, location="Home", home_team="GB",
             gameday=str(soon.date() + dt.timedelta(days=30)), gametime="13:00"),
    ])
    hours = [soon.replace(tzinfo=weather.EASTERN) + dt.timedelta(hours=h) for h in range(-2, 6)]

    def fetch(url):
        if "/points/" in url:
            return {"properties": {"forecastHourly": "hourly-url"}}
        return {"properties": {"periods": [{"startTime": t.isoformat(), "windSpeed": "18 mph"} for t in hours]}}

    assert weather.forecast_winds(games, fetch) == {"out": 18.0}

    def broken(url):
        raise OSError("offline")

    assert weather.forecast_winds(games, broken) == {}


def test_key_number_distribution_and_push_probability():
    rng = np.random.default_rng(1)
    lines = rng.choice([-7.0, -3.0, 2.5, 3.0, 6.5], 4000)
    results = np.rint(lines + rng.normal(0, 13, 4000))
    results[rng.random(4000) < 0.10] = 3  # extra games landing on a home win by 3
    lo, hi = scoredist.MARGIN_RANGE
    factors = scoredist.key_factors(lines, results, 13.0, lo, hi, True)
    assert factors[3 - lo] > 1.5 and factors[3 - lo] == factors[-3 - lo]
    pmf = scoredist.keyed_pmf(3.0, 13.0, factors, lo, hi)
    plain = scoredist.discrete_normal(3.0, 13.0, lo, hi)[0]
    assert pmf.sum() == pytest.approx(1) and pmf[3 - lo] > 1.5 * plain[3 - lo]
    above, push, below = scoredist.line_probs(pmf, 3.0, lo)
    assert above + push + below == pytest.approx(1) and push == pytest.approx(pmf[3 - lo])
    assert scoredist.line_probs(pmf, 3.5, lo)[1] == 0

    games = pd.DataFrame(dict(gameday="2020-01-01", result=results, spread_line=lines,
                              total=44 + rng.normal(0, 13, 4000).round(), total_line=44.5))
    card = game_card(row(spread_line=3.0, pred_margin=5.0), fit_key_factors(games))
    assert card["ats_pick"] == "HOM -3" and card["ats_push"] > 0.05 and 0.5 < card["ats_prob"] < BREAK_EVEN
    assert card["ou_push"] == 0  # half-point total cannot push
    assert game_card(row())["ats_push"] == 0  # without factors: plain normal, no push
    assert fit_key_factors(games, before="2019-01-01")["margin"] == pytest.approx(1)


def test_week_card_lists_games_by_kickoff_time():
    base = dict(season=2026, week=10, pred_margin=1.0, pred_total=44.0, result=float("nan"), total=float("nan"),
                spread_line=float("nan"), total_line=float("nan"))
    games = [("2026_10_BUF_NYJ", "2026-11-15", "13:00"), ("2026_10_NE_DET", "2026-11-15", "09:30"),
             ("2026_10_PIT_CIN", "2026-11-15", "20:20"), ("2026_10_WAS_NYG", "2026-11-12", "20:15"),
             ("2026_10_CAR_NO", "2026-11-15", "13:00")]
    preds = pd.DataFrame([dict(base, game_id=g, gameday=d, gametime=t, away_team=g.split("_")[2],
                               home_team=g.split("_")[3]) for g, d, t in games])
    assert [c["away"] for c in week_card(preds, 2026, 10)] == ["WAS", "NE", "BUF", "CAR", "PIT"]


def test_qb_depth_corrects_starters_and_flags_backups():
    from nflmodel import qb_depth
    row = dict(season=2026, gameday="2026-09-13", home_score=20.0, away_score=17.0)
    games = pd.DataFrame([
        dict(row, game_id="g1", week=1, home_team="AAA", away_team="BBB", home_qb_id="a1", away_qb_id="b1"),
        dict(row, game_id="g2", week=2, gameday="2026-09-20", home_team="BBB", away_team="AAA", home_qb_id="b1", away_qb_id="a1"),
        dict(row, game_id="g3", week=3, gameday="2026-09-27", home_team="AAA", away_team="BBB", home_qb_id="a1", away_qb_id="b1"),
        dict(row, game_id="g4", week=4, gameday="2026-10-04", home_team="AAA", away_team="BBB", home_qb_id="a2", away_qb_id="b1",
             home_score=np.nan, away_score=np.nan),
    ])
    qb = lambda gid, team, pid, att: dict(game_id=gid, team=team, player_id=pid, position="QB", attempts=att, sacks_suffered=0)
    players = pd.DataFrame([
        qb("g1", "AAA", "a1", 30), qb("g1", "BBB", "b1", 30),
        qb("g2", "AAA", "a1", 5), qb("g2", "AAA", "a2", 25), qb("g2", "BBB", "b1", 30),   # a1 left early
        qb("g3", "AAA", "a2", 30), qb("g3", "BBB", "b1", 30),                              # a1 listed, never played
    ])
    fixed, shares = qb_depth.prepare(games, players)
    assert list(fixed.home_qb_id) == ["a1", "b1", "a2", "a2"]     # g3 corrected to the quarterback who played
    assert fixed.away_qb_id[1] == "a1"                             # a starter who left early keeps the start
    assert set(shares) == {("g2", "AAA")}
    assert dict(shares[("g2", "AAA")]) == pytest.approx({"a1": 5 / 30, "a2": 25 / 30})

    sides = qb_depth.side_table(fixed).set_index("game_id")
    assert sides.loc["g3", ["home_backup", "home_new", "home_nth", "home_starts"]].tolist() == [1, 1, 2, 0]
    assert sides.loc["g4", ["home_backup", "home_new", "home_starts"]].tolist() == [1, 0, 1]   # second start of the stint
    assert sides.loc["g4", ["away_backup", "away_new", "away_starts"]].tolist() == [0, 0, 3]
    feats = qb_depth.depth_features(fixed).set_index("game_id")
    assert feats.loc["g3", "qb_new_diff"] == 1 and feats.loc["g3", "qb_greenbk_sum"] == 1
    assert feats.loc["g4", "qb_new_diff"] == 0 and feats.loc["g4", "qb_greenbk_sum"] == pytest.approx(7 / 8)


def test_expected_starters_replaces_a_starter_who_is_out():
    from nflmodel import qb_depth
    games = pd.DataFrame([
        dict(game_id="2026_09_BBB_AAA", season=2026, week=9, gameday="2026-11-08", home_team="AAA", away_team="BBB",
             home_qb_id="a1", away_qb_id="b1", home_qb_name="A One", away_qb_name="B One", home_score=20.0, away_score=17.0),
        dict(game_id="2026_10_AAA_BBB", season=2026, week=10, gameday="2026-11-15", home_team="BBB", away_team="AAA",
             home_qb_id="b1", away_qb_id="a1", home_qb_name="B One", away_qb_name="A One", home_score=np.nan, away_score=np.nan),
    ])
    qb = lambda pid, name, team, out, first: dict(gsis_id=pid, full_name=name, position="QB", team=team, out=out, first_week=first)
    avail = pd.DataFrame([qb("a1", "A One", "AAA", True, 1), qb("a2", "A Two", "AAA", False, 1),
                          qb("a3", "A Three", "AAA", False, 10), qb("b1", "B One", "BBB", False, 1)])
    players = pd.DataFrame([dict(game_id="2026_09_BBB_AAA", team="AAA", player_id="a1", position="QB", attempts=30, sacks_suffered=1)])
    new, changes = qb_depth.expected_starters(games, avail, players, 2026, 10)
    assert changes == [{"game_id": "2026_10_AAA_BBB", "team": "AAA", "was": "a1", "now": "a2"}]   # longest on the roster
    assert new.away_qb_id[1] == "a2" and new.away_qb_name[1] == "A Two" and new.home_qb_id[1] == "b1"
    assert new.home_qb_id[0] == "a1"                                                              # played games untouched
    played = pd.concat([players, pd.DataFrame([dict(game_id="2026_09_BBB_AAA", team="AAA", player_id="a3", position="QB",
                                                    attempts=9, sacks_suffered=0)])])
    assert qb_depth.expected_starters(games, avail, played, 2026, 10)[1][0]["now"] == "a3"        # played most recently


def test_slots_due_finds_games_kicking_off_soon():
    from nflmodel import slots
    rows = [dict(game_id="early", gameday="2026-11-22", gametime="13:00", home_score=""),
            dict(game_id="late1", gameday="2026-11-22", gametime="16:05", home_score=""),
            dict(game_id="late2", gameday="2026-11-22", gametime="16:25", home_score=""),
            dict(game_id="done", gameday="2026-11-22", gametime="16:25", home_score="21")]
    at = lambda h, m: dt.datetime(2026, 11, 22, h, m, tzinfo=dt.timezone.utc)   # 17:20 UTC is 12:20 Eastern
    assert slots.due(rows, at(17, 20)) == ["early"]
    assert slots.due(rows, at(16, 20)) == [] and slots.due(rows, at(18, 20)) == []
    assert slots.due(rows, at(20, 20)) == ["late1", "late2"]          # one rebuild covers both late kickoffs
    summer = [dict(game_id="sept", gameday="2026-09-13", gametime="13:00", home_score="")]
    assert slots.due(summer, dt.datetime(2026, 9, 13, 16, 20, tzinfo=dt.timezone.utc)) == ["sept"]   # daylight time


def test_depth_live_takes_the_first_quarterback_who_is_not_out():
    from nflmodel import depth_live
    assert depth_live.pick(["A One", "A Two", "A Three"], {"A One": "Injured Reserve", "A Two": "Questionable"}) == "A Two"
    assert depth_live.pick(["A One Jr."], {"a one": "Out"}) is None

    def fake(url):
        if "scoreboard" in url:
            team = lambda i, a: {"team": {"id": i, "abbreviation": a}}
            return {"events": [
                {"id": "1", "competitions": [{"status": {"type": {"state": "pre"}}, "competitors": [team("7", "AAA"), team("8", "LAR")]}]},
                {"id": "2", "competitions": [{"status": {"type": {"state": "post"}}, "competitors": [team("9", "CCC"), team("10", "DDD")]}]}]}
        if "summary" in url:
            return {"injuries": [{"injuries": [{"athlete": {"displayName": "A One"}, "status": "Out"}]}]}
        if "/teams/7/" in url:
            return {"depthchart": [{"positions": {"qb": {"athletes": [{"displayName": "A One"}, {"displayName": "A Two"}]}}}]}
        raise OSError("down")
    live = depth_live.starters(["2026-11-15"], get=fake)
    assert live == {"AAA": "A Two"}                                  # the failed team and the finished game are skipped

    games = pd.DataFrame([dict(game_id="g", season=2026, week=10, home_team="AAA", away_team="LA", home_qb_id="a1",
                               away_qb_id="b1", home_qb_name="A One", away_qb_name="B One", home_score=np.nan)])
    new, changes = depth_live.apply(games, 2026, 10, live, {"A Two": "a2"})
    assert new.home_qb_id[0] == "a2" and new.home_qb_name[0] == "A Two" and new.away_qb_id[0] == "b1"
    assert [(c["team"], c["was_name"], c["now_name"]) for c in changes] == [("AAA", "A One", "A Two")]
    assert depth_live.apply(games, 2026, 10, {"AAA": "Nobody Known"}, {"A Two": "a2"})[1] == []   # unmatched name: no change

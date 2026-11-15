import numpy as np
import pandas as pd
import pytest

import datetime as dt
import json

from nflmodel import margin_model, qb_form, scoredist, site, totals_model, weather
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
                        players.append(dict(player_id=f"qb_{team}", position="QB", game_id=gid, attempts=34,
                                            sacks_suffered=2, carries=3, passing_epa=(pts - 21) / 3,
                                            rushing_epa=rng.normal(0, 1)))
    return pd.DataFrame(games), pd.DataFrame(stats), pd.DataFrame(players)


def test_models_cover_every_game_and_ignore_the_future_and_the_market():
    games, stats, players = synthetic()
    m = margin_model.predict_margins(games, stats, players).set_index("game_id")["pred_margin"]
    t = totals_model.predict_totals(games, stats).set_index("game_id")["pred_total"]
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
    m2 = margin_model.predict_margins(g2, s2, p2).set_index("game_id")["pred_margin"]
    t2 = totals_model.predict_totals(g2, s2).set_index("game_id")["pred_total"]
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

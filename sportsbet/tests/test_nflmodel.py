import numpy as np
import pandas as pd
import pytest

from nflmodel import margin_model, totals_model
from nflmodel.card import (BREAK_EVEN, game_card, market_home_prob, next_week, norm_cdf, predict_all,
                           season_record)
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
    games, stats = [], []
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
    return pd.DataFrame(games), pd.DataFrame(stats)


def test_models_cover_every_game_and_ignore_the_future_and_the_market():
    games, stats = synthetic()
    m = margin_model.predict_margins(games, stats).set_index("game_id")["pred_margin"]
    t = totals_model.predict_totals(games, stats).set_index("game_id")["pred_total"]
    expected = set(games.loc[games.season >= 2003, "game_id"])
    assert set(m.index) == expected and set(t.index) == expected
    assert m.notna().all() and t.notna().all()

    cut = "2004-10-01"
    past_ids = games.loc[(games.gameday <= cut) & (games.season >= 2003), "game_id"]
    g2 = games[games.gameday <= cut].copy()
    s2 = stats[stats.game_id.isin(g2.game_id)]
    for col in ("spread_line", "total_line", "home_moneyline", "away_moneyline"):
        g2[col] = g2[col] * 3 + 11  # the betting market must not matter
    m2 = margin_model.predict_margins(g2, s2).set_index("game_id")["pred_margin"]
    t2 = totals_model.predict_totals(g2, s2).set_index("game_id")["pred_total"]
    assert np.allclose(m.loc[past_ids], m2.loc[past_ids], atol=1e-9)
    assert np.allclose(t.loc[past_ids], t2.loc[past_ids], atol=1e-9)


def test_predict_all_and_render(tmp_path):
    games, stats = synthetic()
    for season, part in stats.groupby("season"):
        part.to_csv(tmp_path / f"stats_team_week_{season}.csv", index=False)
    preds = predict_all(games, str(tmp_path))
    season, week = next_week(games)
    assert (season, week) == (2005, 13)
    from nflmodel.card import week_card
    cards = week_card(preds, season, week)
    assert len(cards) == 8
    text = render(cards, season, week, season_record(preds, season))
    assert "Moneyline" in text and "1-800-GAMBLER" in text

import math

import numpy as np
import pandas as pd
import pytest

from cfbmodel import backtest, dist
from test_cfb_dist import synthetic_history


@pytest.fixture(scope="module")
def fit():
    return dist.fit(synthetic_history())


def make_games(n=6, **over):
    base = {
        "game_id": np.arange(1, n + 1),
        "season": 2023,
        "week": np.arange(1, n + 1),
        "start_date": pd.date_range("2023-09-02", periods=n, freq="7D"),
        "home": [f"H{i}" for i in range(n)],
        "away": [f"A{i}" for i in range(n)],
        "home_conf": "SEC",
        "away_conf": "SEC",
        "home_points": 30.0,
        "away_points": 20.0,
        "spread_close": -3.5,
        "total_close": 50.5,
        "spread_open": np.nan,
        "total_open": np.nan,
        "spread_price_home": np.nan,
        "spread_price_away": np.nan,
        "total_price_over": np.nan,
        "total_price_under": np.nan,
    }
    base.update(over)
    return pd.DataFrame(base)


def bet_frame(results, season=2023, week=None, price=-110.0, clv=0.0):
    n = len(results)
    return pd.DataFrame({
        "game_id": np.arange(n), "season": season,
        "week": np.arange(n) if week is None else week,
        "side": "home", "line": -3.5, "price": price, "prob": 0.6, "edge": 0.1,
        "result": results, "clv_points": clv,
    })


def test_summary_counts_and_roi():
    s = backtest.summary(bet_frame([1, 1, 1, 0]))
    assert (s["n"], s["wins"], s["losses"], s["pushes"]) == (4, 3, 1, 0)
    assert s["roi"] == pytest.approx((3 * 0.9091 - 1) / 4, abs=1e-4)
    assert s["roi_110"] == pytest.approx((3 * 0.9091 - 1) / 4, abs=1e-4)
    assert s["win_rate"] == pytest.approx(0.75)


def test_summary_roi_uses_real_price():
    s = backtest.summary(bet_frame([1, 0], price=[+150.0, -110.0]))
    assert s["roi"] == pytest.approx((1.5 - 1) / 2)
    assert s["roi_110"] == pytest.approx((0.9091 - 1) / 2, abs=1e-4)


def test_push_excluded_from_win_rate():
    s = backtest.summary(bet_frame([1, 0, np.nan, np.nan]))
    assert (s["n"], s["wins"], s["losses"], s["pushes"]) == (4, 1, 1, 2)
    assert s["win_rate"] == pytest.approx(0.5)
    assert s["roi"] == pytest.approx((0.9091 - 1) / 4, abs=1e-4)  # pushes return 0 profit


def test_summary_by_season_list():
    b = pd.concat([bet_frame([1, 1], season=2023), bet_frame([0], season=2024)], ignore_index=True)
    s = backtest.summary(b)
    assert [d["season"] for d in s["by_season"]] == [2023, 2024]
    assert s["by_season"][0]["wins"] == 2 and s["by_season"][1]["losses"] == 1
    assert s["by_season"][1]["win_rate"] == 0.0


def test_clustered_se_larger_than_naive_when_week_correlated():
    rng = np.random.default_rng(1)
    weeks = np.repeat(np.arange(30), 20)
    clv = 0.2 + rng.normal(0, 1, 30)[weeks] + rng.normal(0, 0.3, len(weeks))
    s = backtest.summary(bet_frame(np.ones(len(weeks)), week=weeks, clv=clv))
    assert s["clv_se"] > 2 * s["clv_se_naive"]
    assert s["clv_t"] == pytest.approx(s["clv_mean"] / s["clv_se"])


def test_clustered_se_close_to_naive_when_independent():
    rng = np.random.default_rng(2)
    weeks = np.repeat(np.arange(30), 20)
    s = backtest.summary(bet_frame(np.ones(len(weeks)), week=weeks, clv=rng.normal(0, 1, len(weeks))))
    assert 0.7 < s["clv_se"] / s["clv_se_naive"] < 1.4


def test_holm():
    adj = backtest.holm([0.01, 0.04, 0.03])
    assert adj == pytest.approx([0.03, 0.06, 0.06])
    assert backtest.holm([0.5, 0.9]) == pytest.approx([1.0, 1.0])


def test_is_power4():
    assert backtest.is_power4("SEC", "Alabama", 2023)
    assert backtest.is_power4("Pac-12", "Oregon", 2023)
    assert not backtest.is_power4("Pac-12", "Oregon State", 2024)
    assert backtest.is_power4("FBS Independents", "Notre Dame", 2024)
    assert not backtest.is_power4("Mountain West", "Boise State", 2024)
    assert not backtest.is_power4(None, "Somebody", 2024)


def test_one_bet_per_game(fit):
    g = make_games()
    preds = pd.DataFrame({"game_id": g["game_id"], "margin": [20, -20, 3.5, 10, -15, 0.0],
                          "total": 60.0})
    for market in ("spread", "total"):
        b = backtest.bets(preds, g, market, 0.0, "all", fit)
        assert b["game_id"].is_unique
        assert set(b.columns) >= {"game_id", "season", "week", "side", "line", "price", "prob",
                                  "edge", "result", "clv_points", "price_assumed"}
    b = backtest.bets(preds, g, "spread", 0.0, "all", fit).set_index("game_id")
    assert b.loc[1, "side"] == "home" and b.loc[2, "side"] == "away"
    assert b.loc[1, "result"] == 1 and b.loc[2, "result"] == 0  # home won by 10 on -3.5


def test_bet_edge_price_and_threshold(fit):
    g = make_games(2, spread_price_home=[+120.0, np.nan], spread_price_away=[-140.0, np.nan])
    preds = pd.DataFrame({"game_id": [1, 2], "margin": [12.0, 12.0], "total": 50.5})
    b = backtest.bets(preds, g, "spread", 0.0, "all", fit).set_index("game_id")
    p_home = dist.cover_prob(12.0, -3.5, fit)[0]
    assert b.loc[1, "price"] == 120.0 and not b.loc[1, "price_assumed"]
    assert b.loc[1, "edge"] == pytest.approx(p_home * 2.2 - 1)
    assert b.loc[2, "price"] == -110.0 and b.loc[2, "price_assumed"]
    assert b.loc[2, "edge"] == pytest.approx(p_home * (1 + 100 / 110) - 1)
    assert backtest.bets(preds, g, "spread", 5.0, "all", fit).empty


def test_total_bets_and_push(fit):
    g = make_games(3, total_close=[50.5, 50.0, 60.5], home_points=[30.0, 30.0, 30.0],
                   away_points=[25.0, 20.0, 20.0])
    preds = pd.DataFrame({"game_id": [1, 2, 3], "margin": 0.0, "total": [65.0, 65.0, 40.0]})
    b = backtest.bets(preds, g, "total", 0.0, "all", fit).set_index("game_id")
    assert list(b["side"]) == ["over", "over", "under"]
    assert b.loc[1, "result"] == 1 and math.isnan(b.loc[2, "result"]) and b.loc[3, "result"] == 1


def test_clv_points_sign(fit):
    g = make_games(4, spread_open=[-2.5, -2.5, np.nan, -2.5], spread_close=[-3.5, -3.5, -3.5, -3.5],
                   total_open=[48.5, 52.5, np.nan, 48.5], total_close=50.5)
    preds = pd.DataFrame({"game_id": [1, 2, 3, 4], "margin": [20.0, 20.0, 20.0, -20.0],
                          "total": [70.0, 30.0, 70.0, 30.0]})
    s = backtest.bets(preds, g, "spread", 0.0, "all", fit).set_index("game_id")
    assert s.loc[1, "clv_points"] == pytest.approx(1.0)  # home, line moved -2.5 -> -3.5
    assert math.isnan(s.loc[3, "clv_points"])
    assert s.loc[4, "clv_points"] == pytest.approx(-1.0)  # away, line moved against
    t = backtest.bets(preds, g, "total", 0.0, "all", fit).set_index("game_id")
    assert t.loc[1, "clv_points"] == pytest.approx(2.0)  # over, 48.5 -> 50.5
    assert t.loc[2, "clv_points"] == pytest.approx(2.0)  # under, 52.5 -> 50.5
    assert t.loc[4, "clv_points"] == pytest.approx(-2.0)


def test_segments(fit):
    g = make_games(5, week=[1, 4, 5, 8, 9], season=[2023, 2023, 2023, 2024, 2024],
                   home=["Alabama", "Oregon", "Boise State", "Oregon State", "Georgia"],
                   away=["Auburn", "Notre Dame", "Utah State", "Washington State", "Notre Dame"],
                   home_conf=["SEC", "Pac-12", "Mountain West", "Pac-12", "SEC"],
                   away_conf=["SEC", "FBS Independents", "Mountain West", "Pac-12",
                              "FBS Independents"])
    preds = pd.DataFrame({"game_id": g["game_id"], "margin": 25.0, "total": 50.5})
    ids = {seg: sorted(backtest.bets(preds, g, "spread", -1.0, seg, fit)["game_id"])
           for seg in backtest.SEGMENTS}
    assert ids["all"] == [1, 2, 3, 4, 5]
    assert ids["p4"] == [1, 2, 5]
    assert ids["g5"] == [3, 4]
    assert ids["early"] == [1, 2]
    with pytest.raises(ValueError):
        backtest.bets(preds, g, "spread", 0.0, "bogus", fit)


def staleness_games(stale, seed=0, n_teams=40, weeks=12, seasons=(2021, 2022, 2023), k=0.3):
    """Round-robin-ish schedule; market ratings update by k times each surprise.

    With `stale`, the opener is built from ratings before the previous game (last week's line);
    otherwise the opener is the close plus independent noise."""
    rng = np.random.default_rng(seed)
    rows, gid = [], 0
    for season in seasons:
        true = rng.normal(0, 10, n_teams)
        market = true + rng.normal(0, 6, n_teams)
        for week in range(1, weeks + 1):
            before = market.copy()
            order = rng.permutation(n_teams)
            for h, a in zip(order[::2], order[1::2]):
                close_margin = market[h] - market[a] + 2.5
                stale_margin = before_prev[h] - before_prev[a] + 2.5 if stale and week > 1 else None
                margin = round(true[h] - true[a] + 2.5 + rng.normal(0, 14))
                spread_close = -round(close_margin * 2) / 2
                if stale_margin is not None:
                    spread_open = -round(stale_margin * 2) / 2
                else:
                    spread_open = spread_close + round(rng.normal(0, 1.0) * 2) / 2
                surprise = margin - close_margin
                market[h] += k * surprise
                market[a] -= k * surprise
                gid += 1
                rows.append({"game_id": gid, "season": season, "week": week,
                             "start_date": pd.Timestamp(f"{season}-09-01") + pd.Timedelta(weeks=week),
                             "home": f"T{h}", "away": f"T{a}", "home_conf": "SEC",
                             "away_conf": "SEC", "home_points": 30 + margin, "away_points": 30,
                             "spread_close": spread_close, "total_close": 55.5,
                             "spread_open": spread_open, "total_open": 55.5})
            before_prev = before
    return pd.DataFrame(rows)


def test_staleness_detects_planted_artifact():
    assert abs(backtest.opener_staleness(staleness_games(stale=True))) > 3


def test_staleness_clean_case():
    assert abs(backtest.opener_staleness(staleness_games(stale=False))) < 2

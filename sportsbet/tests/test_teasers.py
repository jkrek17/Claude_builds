import numpy as np
import pandas as pd
import pytest

from nflmodel import teasers as tz


def games():
    # spread_line is positive when the home team is favoured; result is home minus away.
    return pd.DataFrame({
        "season": [2025, 2025, 2025, 2026, 2026, 2026],
        "week": [1, 1, 1, 1, 1, 1],
        "game_id": ["a", "b", "c", "d", "e", "f"],
        "gameday": ["2025-09-07"] * 3 + ["2026-09-13"] * 3,
        "home_team": ["H1", "H2", "H3", "H4", "H5", "H6"],
        "away_team": ["A1", "A2", "A3", "A4", "A5", "A6"],
        "spread_line": [8.0, -2.5, 4.0, 7.5, 2.0, -1.5],
        "result": [2.0, -9.0, 3.0, np.nan, np.nan, np.nan],
        "total_line": [44.0] * 6,
        "div_game": [0] * 6,
        "pred_margin": [9.0, -2.0, 4.0, 9.5, 1.0, -1.0],
    })


def test_prices():
    assert tz.payout(-134) == pytest.approx(100 / 134)
    assert tz.payout(160) == pytest.approx(1.6)
    assert tz.break_even_leg(-110) == pytest.approx(0.7237, abs=1e-4)
    assert tz.break_even_leg(-134) == pytest.approx(0.7568, abs=1e-4)
    assert tz.break_even_leg(160, legs=3) == pytest.approx(0.7272, abs=1e-4)
    assert tz.price_needed(tz.break_even_leg(-125)) == pytest.approx(-125)
    assert tz.price_needed(tz.break_even_leg(160, 3), 3) == pytest.approx(160)


def test_kind_and_leg_result():
    assert list(tz.kind([1.5, 2, 2.5, 3, 1, -7.5, -8, -8.5, -7, -9])) == \
        ["dog", "dog", "dog", "", "", "fav", "fav", "fav", "", ""]
    # a +2.5 underdog teased to +8.5 survives a 7-point loss; a -8 favourite teased to -2 pushes on a 2-point win
    assert list(tz.leg_result([-7, -9, 2, 3], [2.5, 2.5, -8, -8])) == [1, -1, 0, 1]


def test_team_rows_sides_and_gap():
    rows = tz.team_rows(games())
    q = tz.qualifying(rows)
    got = {(r.team, r.line, r.kind) for r in q.itertuples()}
    assert got == {("H1", -8.0, "fav"), ("H2", 2.5, "dog"), ("H4", -7.5, "fav"), ("A5", 2.0, "dog"), ("H6", 1.5, "dog")}
    h1 = rows[rows.team == "H1"].iloc[0]
    assert h1.margin == 2 and h1.res == 0 and h1.gap == pytest.approx(1.0)   # model 9 vs line 8
    a5 = rows[rows.team == "A5"].iloc[0]
    assert a5.gap == pytest.approx(1.0)   # home favoured by 2, model says 1: the model likes the away side by 1
    h2 = rows[rows.team == "H2"].iloc[0]
    assert h2.res == -1                    # +2.5 teased to +8.5, lost by 9


def test_past_rate_ignores_pushes_and_later_seasons():
    rows = tz.team_rows(games())
    rate, n = tz.past_rate(rows, 2026)
    assert (rate, n) == (0.0, 1)          # H1 pushed, H2 lost; 2026 is not in the past
    assert tz.past_rate(rows, 2025)[1] == 0


def test_gate():
    assert not tz.gate(-134, 0.753)
    assert tz.gate(-120, 0.753)
    assert not tz.gate(-125, 0.753)       # needs 74.5%, one point under 75.3% is 74.3%
    assert tz.gate(-134, 0.770)


def test_pair_up_one_leg_per_game_ranked_by_gap():
    rows = tz.team_rows(games())
    legs = tz.qualifying(rows[rows.season == 2026])
    pairs = tz.pair_up(legs)
    assert len(pairs) == 1                 # three games, so one teaser and one leg left over
    teams = {legs.loc[i, "team"] for i in pairs[0]}
    assert teams == {"H4", "A5"}           # gaps 2.0 and 1.0 beat H6's 0.5
    assert tz.pair_up(legs.iloc[:1]) == []


def test_settle():
    assert tz.settle(1, 1, -120) == pytest.approx(100 / 120)
    assert tz.settle(1, -1, -120) == -1
    assert tz.settle(0, 1, -120) == 0 and tz.settle(0, -1, -120) == 0


def test_render_verdicts():
    rows = tz.team_rows(games())
    legs = tz.qualifying(rows[rows.season == 2026])
    text = tz.render(legs, tz.pair_up(legs), 2026, 1, 0.753, 1200, {"FanDuel": -134, "Fanatics": -120})
    assert "H4   -7.5 ->  -1.5" in text
    assert "FanDuel at -134: each leg must win 75.7%  ->  NO BET" in text
    assert "Fanatics at -120: each leg must win 73.9%  ->  BET ($5 each)" in text
    assert "1-800-GAMBLER" in text
    empty = tz.render(legs.iloc[:0], [], 2026, 1, 0.753, 1200, {"Fanatics": -120})
    assert "No game has a spread" in empty and "NO BET" in empty

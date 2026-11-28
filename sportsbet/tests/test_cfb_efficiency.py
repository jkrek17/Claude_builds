import math

import pandas as pd
import pytest

from cfbmodel import efficiency

COLS = ["pos_team", "period", "TimeSecsRem", "play_type", "rush", "pass", "EPA", "success", "drive_id",
        "drive_pts", "yards_to_goal", "garbage"]

# Home team A (id 10) hosts B (id 20). A scores a touchdown on drive 1 (reaches the B 35) and punts on
# drive 3 (never inside the 40). B punts on drive 2 and scores a garbage-time touchdown on drive 4.
ROWS = [
    # The kicking team's kickoff row shares A's drive id, as in the real data; it must not make a B drive.
    ("B", 1, 1800, "Kickoff", 0, 0, 0.10, 0, 1, 7, 65, False),
    ("A", 1, 1790, "Rush", 1, 0, 0.50, 1, 1, 7, 75, False),
    ("A", 1, 1760, "Pass Reception", 0, 1, 1.50, 1, 1, 7, 70, False),
    ("A", 1, 1730, "Pass Incompletion", 0, 1, -0.50, 0, 1, 7, 35, False),
    ("A", 1, 1700, "Rushing Touchdown", 1, 0, 2.00, 1, 1, 7, 35, False),
    ("B", 1, 1690, "Rush", 1, 0, -1.00, 0, 2, 0, 75, False),
    ("B", 1, 1680, "Pass Reception", 0, 1, 0.20, 1, 2, 0, 70, False),
    ("B", 1, 1670, "Pass Incompletion", 0, 1, float("nan"), 0, 2, 0, 70, False),
    ("B", 1, 1660, "Punt", 0, 0, -0.30, 0, 2, 0, 70, False),
    ("A", 2, 1650, "Rush", 1, 0, -0.20, 0, 3, 0, 45, False),
    ("A", 2, 1610, "Pass Reception", 0, 1, 0.40, 1, 3, 0, 50, False),
    ("A", 2, 1600, "Punt", 0, 0, 0.00, 0, 3, 0, 50, False),
    ("B", 3, 1800, "Rush", 1, 0, 1.00, 1, 4, 7, 30, True),
    ("B", 3, 1780, "Passing Touchdown", 0, 1, 3.00, 1, 4, 7, 10, True),
]


def tiny_game(neutral=False, game_id=401, drive_pts=True):
    df = pd.DataFrame(ROWS, columns=COLS)
    df["def_pos_team"] = df["pos_team"].map({"A": "B", "B": "A"})
    df["game_id"] = game_id
    df["season"] = 2023
    df["week"] = 3
    df["season_type"] = "regular"
    df["start_date"] = "2023-09-16T19:00:00.000Z"
    df["home"] = "A"
    df["away"] = "B"
    df["home_team_id"] = 10
    df["away_team_id"] = 20
    df["neutral_site"] = neutral
    df["drive_id"] = df["drive_id"] + game_id * 100
    if not drive_pts:
        df["drive_pts"] = float("nan")
    return df


def by_team(out):
    return out.set_index("team")


def test_team_games_on_tiny_game():
    out = efficiency.team_games(tiny_game())
    assert len(out) == 2
    t = by_team(out)
    a, b = t.loc["A"], t.loc["B"]

    assert a["opp"] == "B" and b["opp"] == "A"
    assert a["team_id"] == 10 and a["opp_id"] == 20
    assert b["team_id"] == 20 and b["opp_id"] == 10
    assert a["home"] == 1 and b["home"] == 0
    assert a["game_id"] == 401 and a["season"] == 2023 and a["week"] == 3
    assert a["start_date"] == "2023-09-16T19:00:00.000Z"

    assert a["epa_rush"] == pytest.approx((0.5 + 2.0 - 0.2) / 3, abs=1e-9)
    assert a["epa_pass"] == pytest.approx((1.5 - 0.5 + 0.4) / 3, abs=1e-9)
    assert a["success"] == pytest.approx(4 / 6, abs=1e-9)
    assert a["explosive"] == pytest.approx((0.5 + 1.5 + 2.0 + 0.4) / 4, abs=1e-9)
    assert a["plays"] == 6
    assert a["drives"] == 2
    assert a["points"] == pytest.approx(7.0, abs=1e-9)
    assert a["opps"] == 1
    assert a["finish"] == pytest.approx(7.0, abs=1e-9)
    # drive 1 runs 1800 -> 1690 (next drive's start); drive 3 is the last of the half, 1650 -> 1600
    assert a["sec_per_play"] == pytest.approx((110 + 50) / 6, abs=1e-9)

    # B: the null-EPA pass and both garbage-time plays are out of the efficiency stats ...
    assert b["epa_rush"] == pytest.approx(-1.0, abs=1e-9)
    assert b["epa_pass"] == pytest.approx(0.2, abs=1e-9)
    assert b["success"] == pytest.approx(0.5, abs=1e-9)
    assert b["explosive"] == pytest.approx(0.2, abs=1e-9)
    assert b["plays"] == 2
    # pace uses every snap of every drive: drive 2 runs 1690 -> 1650 over 3 snaps, drive 4 1800 -> 1780 over 2
    assert b["sec_per_play"] == pytest.approx((40 + 20) / 5, abs=1e-9)
    # ... but the garbage-time drive still counts for drives, points and finishing.
    assert b["drives"] == 2
    assert b["points"] == pytest.approx(7.0, abs=1e-9)
    assert b["opps"] == 1
    assert b["finish"] == pytest.approx(7.0, abs=1e-9)


def test_neutral_site_has_no_home_team():
    out = by_team(efficiency.team_games(tiny_game(neutral=True)))
    assert out.loc["A", "home"] == 0 and out.loc["B", "home"] == 0


def test_missing_drive_points_give_nan_not_zero():
    out = by_team(efficiency.team_games(tiny_game(drive_pts=False)))
    assert math.isnan(out.loc["A", "points"]) and math.isnan(out.loc["A", "finish"])
    assert out.loc["A", "drives"] == 2 and out.loc["A", "opps"] == 1


def test_drive_points_sign_repaired_and_defensive_scores_not_offense_points():
    df = tiny_game()
    # The source sometimes stores an offensive touchdown drive as -7; a pick-six drive is -7 for real.
    df.loc[df["drive_id"].eq(40101), "drive_pts"] = -7
    df.loc[df["drive_id"].eq(40103), "drive_pts"] = -7
    df.loc[df["drive_id"].eq(40103) & df["play_type"].eq("Punt"), "play_type"] = "Interception Return Touchdown"
    out = by_team(efficiency.team_games(df))
    assert out.loc["A", "points"] == pytest.approx(7.0, abs=1e-9)
    assert out.loc["A", "finish"] == pytest.approx(7.0, abs=1e-9)


def test_pace_works_when_clock_only_changes_between_drives():
    df = tiny_game()
    df["TimeSecsRem"] = df.groupby("drive_id")["TimeSecsRem"].transform("first")
    out = by_team(efficiency.team_games(df))
    # drive 1 still runs 1800 -> 1690; drive 3, last of the half, has no measurable length and is left out
    assert out.loc["A", "sec_per_play"] == pytest.approx(110 / 4, abs=1e-9)


def test_no_scoring_opportunity_gives_nan_finish():
    df = tiny_game()
    df.loc[df["pos_team"].eq("A"), "yards_to_goal"] = 60
    out = by_team(efficiency.team_games(df))
    assert out.loc["A", "opps"] == 0 and math.isnan(out.loc["A", "finish"])


def test_two_games_stay_separate_and_columns_are_stable():
    out = efficiency.team_games(pd.concat([tiny_game(game_id=401), tiny_game(game_id=402, neutral=True)]))
    assert len(out) == 4
    assert list(out.columns) == efficiency.COLUMNS
    assert set(efficiency.METRICS) <= set(out.columns)
    assert out.groupby("game_id").size().tolist() == [2, 2]


def test_drive_with_backwards_clock_is_left_out_of_pace():
    df = tiny_game()
    df.loc[df["drive_id"].eq(40103) & df["play_type"].eq("Rush"), "TimeSecsRem"] = 1500  # runs back to 1600
    out = by_team(efficiency.team_games(df))
    assert out.loc["A", "sec_per_play"] == pytest.approx(110 / 4, abs=1e-9)


def test_drive_longer_than_a_minute_a_snap_is_left_out_of_pace():
    df = tiny_game()
    # B's punt drive (3 snaps) now appears to run 1690 -> 1450: 80 s a snap, a broken clock
    df.loc[df["drive_id"].eq(40103), "TimeSecsRem"] = [1450, 1440, 1430]
    out = by_team(efficiency.team_games(df))
    assert out.loc["B", "sec_per_play"] == pytest.approx(20 / 2, abs=1e-9)

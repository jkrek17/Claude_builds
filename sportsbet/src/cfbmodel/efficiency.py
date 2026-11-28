"""Per-game team efficiency from play-by-play.

``team_games`` turns play-by-play rows (the ``pbp.KEEP_COLS`` columns plus a boolean ``garbage``
column) into one row per offense per game.

Two kinds of rows feed it:

* Efficiency rows: rush or pass plays (``rush == 1`` or ``pass == 1``) with EPA present and not in
  garbage time. They give ``epa_rush``, ``epa_pass``, ``success``, ``explosive`` (mean EPA of the
  successful plays) and ``plays``.
* All rows, garbage time included, give the drive columns: ``drives``, ``points``, ``opps``,
  ``finish`` and ``sec_per_play``.

Choices worth knowing:

* A drive is a ``(game_id, drive_id)`` pair owned by the team with the most rush or pass rows in it.
  The source puts the other team's kickoff row inside a drive, so drives are not grouped by
  ``pos_team``. A drive with no rush or pass row (a lone kickoff, a return touchdown) is not counted.
* ``points`` are offensive points: ``drive_pts`` per drive, with two repairs. The source sometimes
  stores an offensive touchdown or field goal drive with a negative sign (2023: 47 drives in 15
  games), so a negative drive with a touchdown or made field goal by the offense is flipped; any
  other negative drive (pick-six, fumble return, safety) counts 0. ``points`` is NaN when the
  source has no ``drive_pts`` for the game (2023: every game without an FBS team).
* A scoring opportunity (``opps``) is a drive whose owner's rows reach the opponent 40
  (``min(yards_to_goal) <= 40``). ``finish`` is points per opportunity, NaN when there were none.
* ``sec_per_play``: the kept columns have no drive start or end times, and some games record the
  clock only once per drive, so pace comes from drive length rather than gaps between snaps. A
  drive runs from its first row's ``TimeSecsRem`` (seconds left in the half) to the first row of
  the next drive in the same half; the last drive of a half ends at its own last row. Pace is the
  team's total drive seconds over the rush and pass rows of those drives. Left out: overtime
  drives, drives of zero measured length, and drives with a broken source clock (it runs backwards,
  or the drive takes more than ``MAX_SECS_PER_SNAP`` per snap; some FCS games jump to 0:00).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

METRICS = ["epa_rush", "epa_pass", "success", "explosive", "finish", "sec_per_play"]

COLUMNS = ["game_id", "season", "week", "season_type", "start_date", "team", "team_id", "opp", "opp_id",
           "home", "epa_rush", "epa_pass", "success", "explosive", "finish", "plays", "drives",
           "sec_per_play", "points", "opps"]

OPP_YARDS = 40  # a drive reaching the opponent 40 is a scoring opportunity
OFFENSE_SCORES = {"Rushing Touchdown", "Passing Touchdown", "Field Goal Good"}
MAX_SECS_PER_SNAP = 60  # a longer drive means a broken clock (real drives run about 20-45 s a snap)


def _is_play(df: pd.DataFrame) -> pd.Series:
    return df["rush"].eq(1) | df["pass"].eq(1)


def _efficiency(plays: pd.DataFrame) -> pd.DataFrame:
    eff = plays[_is_play(plays) & plays["EPA"].notna() & ~plays["garbage"].astype(bool)].copy()
    eff["epa_r"] = eff["EPA"].where(eff["rush"].eq(1))
    eff["epa_p"] = eff["EPA"].where(eff["pass"].eq(1))
    eff["epa_s"] = eff["EPA"].where(eff["success"].eq(1))

    keys = ["game_id", "pos_team"]
    return eff.groupby(keys, sort=False).agg(
        epa_rush=("epa_r", "mean"), epa_pass=("epa_p", "mean"), success=("success", "mean"),
        explosive=("epa_s", "mean"), plays=("EPA", "size"),
    ).reset_index()


def _drive_clock(rows: pd.DataFrame) -> pd.DataFrame:
    """Seconds each drive took, from its first row to the next drive's first row in the same half."""
    d = rows.groupby(["game_id", "drive_id"], sort=False).agg(
        first_row=("row", "min"), period=("period", "first"), start=("TimeSecsRem", "first"),
        last=("TimeSecsRem", "last")).reset_index().sort_values(["game_id", "first_row"])
    d["half"] = np.where(d["period"] <= 2, 1, np.where(d["period"] <= 4, 2, 3))
    g = d.groupby("game_id", sort=False)
    same_half = g["half"].shift(-1).eq(d["half"])
    end = g["start"].shift(-1).where(same_half, d["last"])
    # Some games carry a broken clock (it runs backwards inside a drive, or the next drive starts
    # later than this one's last row). Those drives get no time rather than a wrong one.
    sane = (d["last"] <= d["start"]) & (end <= d["last"]) & (d["half"] < 3)
    d["secs"] = (d["start"] - end).where(sane)
    return d[["game_id", "drive_id", "secs"]]


def _drives(plays: pd.DataFrame) -> pd.DataFrame:
    rows = plays[plays["drive_id"].notna()]
    owner = (rows[_is_play(rows)].groupby(["game_id", "drive_id", "pos_team"], sort=False).size()
             .rename("n").reset_index().sort_values("n", ascending=False, kind="stable")
             .drop_duplicates(["game_id", "drive_id"]).drop(columns="n"))
    own = rows.merge(owner, on=["game_id", "drive_id", "pos_team"])
    own["snap"] = _is_play(own)
    own["off_score"] = own["play_type"].isin(OFFENSE_SCORES)
    per_drive = own.groupby(["game_id", "drive_id", "pos_team"], sort=False).agg(
        pts=("drive_pts", "first"), ytg=("yards_to_goal", "min"), snaps=("snap", "sum"),
        off_score=("off_score", "any")).reset_index()
    per_drive = per_drive.merge(_drive_clock(rows), on=["game_id", "drive_id"], how="left")

    pts = per_drive["pts"]
    per_drive["pts"] = pts.where(pts >= 0, np.where(per_drive["off_score"], -pts, 0.0)).where(pts.notna())
    per_drive["is_opp"] = per_drive["ytg"] <= OPP_YARDS
    per_drive["opp_pts"] = per_drive["pts"].where(per_drive["is_opp"])
    timed = (per_drive["secs"] > 0) & (per_drive["secs"] <= MAX_SECS_PER_SNAP * per_drive["snaps"])
    per_drive["pace_secs"] = per_drive["secs"].where(timed)
    per_drive["pace_snaps"] = per_drive["snaps"].where(timed, 0)

    out = per_drive.groupby(["game_id", "pos_team"], sort=False).agg(
        drives=("drive_id", "size"), points=("pts", lambda s: s.sum(min_count=1)),
        opps=("is_opp", "sum"), opp_points=("opp_pts", lambda s: s.sum(min_count=1)),
        pace_secs=("pace_secs", "sum"), pace_snaps=("pace_snaps", "sum"),
    ).reset_index()
    out["finish"] = out["opp_points"] / out["opps"].where(out["opps"] > 0)
    out["sec_per_play"] = out["pace_secs"] / out["pace_snaps"].where(out["pace_snaps"] > 0)
    return out.drop(columns=["opp_points", "pace_secs", "pace_snaps"])


def team_games(plays: pd.DataFrame) -> pd.DataFrame:
    """One row per (game, offense) with the efficiency columns listed in ``COLUMNS``."""
    plays = plays.reset_index(drop=True)
    plays["row"] = np.arange(len(plays))  # source order; drives are timed in this order
    games = plays.groupby("game_id", sort=False).agg(
        season=("season", "first"), week=("week", "first"), season_type=("season_type", "first"),
        start_date=("start_date", "first"), home_name=("home", "first"), away_name=("away", "first"),
        home_team_id=("home_team_id", "first"), away_team_id=("away_team_id", "first"),
        neutral_site=("neutral_site", "first"),
    ).reset_index()

    teams = plays.loc[_is_play(plays), ["game_id", "pos_team"]].drop_duplicates()
    out = (teams.merge(_efficiency(plays), on=["game_id", "pos_team"], how="left")
           .merge(_drives(plays), on=["game_id", "pos_team"], how="left")
           .merge(games, on="game_id", how="left")
           .rename(columns={"pos_team": "team"}))

    is_home = out["team"].eq(out["home_name"])
    is_away = out["team"].eq(out["away_name"])
    out["opp"] = np.where(is_home, out["away_name"], np.where(is_away, out["home_name"], None))
    home_id = out["home_team_id"].astype("Int64")
    away_id = out["away_team_id"].astype("Int64")
    out["team_id"] = home_id.where(is_home, away_id.where(is_away))
    out["opp_id"] = away_id.where(is_home, home_id.where(is_away))
    neutral = out["neutral_site"].fillna(False).astype(bool)
    out["home"] = (is_home & ~neutral).astype(int)

    out["plays"] = out["plays"].fillna(0).astype(int)
    out["drives"] = out["drives"].fillna(0).astype(int)
    out["opps"] = out["opps"].fillna(0).astype(int)
    out["game_id"] = out["game_id"].astype("int64")
    out["season"] = out["season"].astype(int)
    out["week"] = out["week"].astype(int)
    return out.sort_values(["game_id", "home"], ascending=[True, False], kind="stable")[COLUMNS] \
        .reset_index(drop=True)

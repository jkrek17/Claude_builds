"""Who is available each week: roster status, current team and the official injury report.

One row per player, season and week, from two nflverse files:

* ``roster_weekly_{season}.csv``: the team a player is on that week (so trades and signings show up)
  and his roster status (active, injured reserve, practice squad, ...).
* ``injuries_{season}.csv``: the game status report (Out, Doubtful, Questionable) and practice status.

Both are published during the week, so the latest week in the files can be the one about to be played.
A week that is not in the files yet falls back to the latest earlier week (see :func:`for_week`).
"""
from __future__ import annotations

import glob
import os

import pandas as pd

TEAM_MAP = {"OAK": "LV", "SD": "LAC", "STL": "LA", "JAC": "JAX", "SL": "LA"}
COLUMNS = ["gsis_id", "full_name", "position", "season", "week", "team", "roster_status", "report_status",
           "practice_status", "injury", "out"]
OUT_ROSTER = {"RES", "CUT", "RET", "DEV", "EXE", "TRC", "TRT", "TRD", "INA"}   # not on the active roster
OUT_REPORT = {"Out", "Doubtful"}


def _season_files(data_dir: str, stem: str, seasons) -> list[str]:
    files = sorted(glob.glob(os.path.join(data_dir, f"{stem}_*.csv")))
    if seasons is not None:
        want = {int(s) for s in seasons}
        files = [f for f in files if int(os.path.basename(f)[-8:-4]) in want]
    return files


def load(data_dir: str, seasons=None) -> pd.DataFrame:
    """Availability rows (``COLUMNS``) for the cached seasons, or only ``seasons`` when given.

    ``out`` is True when the player is off the active roster or listed Out or Doubtful. Roster status
    "INA" (game-day inactive) is only known after the fact for past weeks; treat it as hindsight.
    """
    rosters = []
    for f in _season_files(data_dir, "roster_weekly", seasons):
        d = pd.read_csv(f, usecols=["season", "week", "team", "position", "status", "full_name", "gsis_id"],
                        low_memory=False)
        rosters.append(d)
    if not rosters:
        return pd.DataFrame(columns=COLUMNS)
    r = pd.concat(rosters, ignore_index=True).dropna(subset=["gsis_id"])
    r = r.rename(columns={"status": "roster_status"}).drop_duplicates(["gsis_id", "season", "week"], keep="last")
    reports = []
    for f in _season_files(data_dir, "injuries", seasons):
        d = pd.read_csv(f, usecols=["season", "week", "gsis_id", "report_status", "practice_status",
                                    "report_primary_injury", "practice_primary_injury"], low_memory=False)
        reports.append(d)
    if reports:
        i = pd.concat(reports, ignore_index=True).dropna(subset=["gsis_id"])
        i["injury"] = i["report_primary_injury"].fillna(i["practice_primary_injury"])
        i = i.drop_duplicates(["gsis_id", "season", "week"], keep="last")
        r = r.merge(i[["gsis_id", "season", "week", "report_status", "practice_status", "injury"]],
                    on=["gsis_id", "season", "week"], how="left")
    else:
        r["report_status"] = r["practice_status"] = r["injury"] = None
    r["team"] = r["team"].replace(TEAM_MAP)
    r["out"] = r["roster_status"].isin(OUT_ROSTER) | r["report_status"].isin(OUT_REPORT)
    return r[COLUMNS].reset_index(drop=True)


def for_week(avail: pd.DataFrame, season: int, week: int) -> pd.DataFrame:
    """The latest known row for every player as of one week, with a ``source_week`` column.

    A team's roster for a week appears only once that week's report is published, and not at all in
    its bye week, so each player's most recent row up to ``week`` is used. A row from an earlier week
    has its injury report blanked (an old report says nothing about this week) and its game-day
    inactive status ignored. A player whose last row is older than his team's latest roster has left
    that team and is marked out.
    """
    s = avail[(avail["season"] == season) & (avail["week"] <= week)]
    if s.empty:
        return s.assign(source_week=pd.Series(dtype=int))
    out = s.sort_values("week").drop_duplicates("gsis_id", keep="last").copy()
    out["source_week"] = out["week"]
    old = out["week"] < week
    out.loc[old, ["report_status", "practice_status", "injury"]] = None
    out.loc[old, "out"] = out.loc[old, "roster_status"].isin(OUT_ROSTER - {"INA"})
    team_latest = s.groupby("team")["week"].max()
    out.loc[out["week"] < out["team"].map(team_latest), "out"] = True
    out["week"] = week
    return out.reset_index(drop=True)

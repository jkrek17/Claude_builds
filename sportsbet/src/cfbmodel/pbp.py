"""Play-by-play from sportsdataverse (cfbfastR builds), slimmed to the columns the model uses.

Each season is downloaded once, cut to KEEP_COLS and cached as ``data_dir/pbp/slim_{season}.parquet``;
the full file (about 110 MB) is deleted. The current season is refetched when its cache is older
than 6 hours. Teams in these files are names (``pos_team``, ``home``, ``away``); only the home and
away team ids are present, so join to other sources on ``home_team_id`` / ``away_team_id``.
"""
from __future__ import annotations

import datetime as dt
import shutil
import time
import urllib.request
from pathlib import Path

import pandas as pd

PBP_URL = ("https://github.com/sportsdataverse/sportsdataverse-data/releases/download/"
           "cfbfastR_cfb_pbp/play_by_play_{season}.parquet")

KEEP_COLS = [
    "game_id", "season", "week", "season_type", "start_date", "pos_team", "def_pos_team", "home", "away",
    "period", "TimeSecsRem", "pos_score_diff_start", "down", "distance", "yards_to_goal", "play_type",
    "rush", "pass", "EPA", "success", "scoring_opp", "drive_id", "drive_pts", "drive_start_yards_to_goal",
    "neutral_site", "home_team_id", "away_team_id", "home_team_division", "away_team_division",
    "home_team_conference", "away_team_conference",
]

LIVE_MAX_AGE = 6 * 3600  # seconds
# Garbage time: the offense's margin at the snap is beyond this, by quarter. Overtime never is.
GARBAGE_MARGIN = {2: 38, 3: 28, 4: 22}


def current_season(today: dt.date | None = None) -> int:
    """The season in progress: a season runs from August through the January bowls."""
    today = today or dt.date.today()
    return today.year if today.month >= 7 else today.year - 1


def _download(url: str, dest: Path) -> None:
    part = dest.with_name(dest.name + ".part")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "cfbmodel"})
        with urllib.request.urlopen(req, timeout=120) as resp, open(part, "wb") as out:
            shutil.copyfileobj(resp, out, 1 << 20)
        part.replace(dest)
    finally:
        part.unlink(missing_ok=True)


def _slim(full: Path, slim: Path) -> None:
    import pyarrow.parquet as pq

    table = pq.read_table(full, columns=KEEP_COLS)
    part = slim.with_name(slim.name + ".part")
    try:
        pq.write_table(table, part)
        part.replace(slim)
    finally:
        part.unlink(missing_ok=True)


def load(season: int, data_dir: Path) -> pd.DataFrame:
    """Every play of a season, KEEP_COLS only, downloading and slimming on first use."""
    folder = Path(data_dir) / "pbp"
    folder.mkdir(parents=True, exist_ok=True)
    slim = folder / f"slim_{season}.parquet"
    fresh = slim.exists() and (season != current_season()
                               or time.time() - slim.stat().st_mtime < LIVE_MAX_AGE)
    if not fresh:
        full = folder / f"full_{season}.parquet"
        try:
            _download(PBP_URL.format(season=season), full)
            _slim(full, slim)
        finally:
            full.unlink(missing_ok=True)
    return pd.read_parquet(slim, columns=KEEP_COLS)


def is_garbage(df: pd.DataFrame) -> pd.Series:
    """True for plays snapped with the game out of reach (|margin| > 38 Q2, > 28 Q3, > 22 Q4)."""
    limit = df["period"].map(GARBAGE_MARGIN)
    return (df["pos_score_diff_start"].abs() > limit).fillna(False).astype(bool)


def scrimmage(df: pd.DataFrame) -> pd.DataFrame:
    """Rush and pass plays with an EPA value, garbage time removed."""
    keep = ((df["rush"] == 1) | (df["pass"] == 1)) & df["EPA"].notna() & ~is_garbage(df)
    return df[keep]

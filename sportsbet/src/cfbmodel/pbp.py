"""Play-by-play from sportsdataverse (cfbfastR builds), slimmed to the columns the model uses.

Each season is downloaded once, cut to KEEP_COLS and cached as ``data_dir/pbp/slim_{season}.parquet``;
the full file (about 110 MB) is deleted. The current season is refetched when its cache is older
than 6 hours, until a copy fetched after its January bowls (``season_over``) is on disk; then it is
final. When a refetch fails the existing slim file is used (logged). ``available`` tells a season the
source has not published yet (the offseason, a 404) from an outage. Teams in these files are names (``pos_team``, ``home``, ``away``); only the home and
away team ids are present, so join to other sources on ``home_team_id`` / ``away_team_id``.
"""
from __future__ import annotations

import datetime as dt
import logging
import shutil
import time
import urllib.error
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
# Game lines the source carries (home perspective spread, negative = home favoured; total). Kept when
# the source file has them; slim files cut before these were added come back with NaN.
LINE_COLS = ["spread", "over_under"]

log = logging.getLogger(__name__)
LIVE_MAX_AGE = 6 * 3600  # seconds
# Garbage time: the offense's margin at the snap is beyond this, by quarter. Overtime never is.
GARBAGE_MARGIN = {2: 38, 3: 28, 4: 22}


def current_season(today: dt.date | None = None) -> int:
    """The season in progress: a season runs from August through the January bowls."""
    today = today or dt.date.today()
    return today.year if today.month >= 7 else today.year - 1


def season_over(season: int, when: dt.date) -> bool:
    """True once ``season``'s January bowls and playoff are over (from February 1 of the next year)."""
    return when >= dt.date(season + 1, 2, 1)


def _fresh(slim: Path, season: int) -> bool:
    if not slim.exists():
        return False
    if season != current_season():
        return True
    mtime = slim.stat().st_mtime
    fetched = dt.datetime.fromtimestamp(mtime, dt.timezone.utc).date()
    return season_over(season, fetched) or time.time() - mtime < LIVE_MAX_AGE


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

    names = pq.read_schema(full).names
    table = pq.read_table(full, columns=KEEP_COLS + [c for c in LINE_COLS if c in names])
    part = slim.with_name(slim.name + ".part")
    try:
        pq.write_table(table, part)
        part.replace(slim)
    finally:
        part.unlink(missing_ok=True)


def load(season: int, data_dir: Path) -> pd.DataFrame:
    """Every play of a season, KEEP_COLS then LINE_COLS, downloading and slimming on first use.

    LINE_COLS are NaN when the slim file lacks them."""
    folder = Path(data_dir) / "pbp"
    folder.mkdir(parents=True, exist_ok=True)
    slim = folder / f"slim_{season}.parquet"
    if not _fresh(slim, season):
        full = folder / f"full_{season}.parquet"
        try:
            _download(PBP_URL.format(season=season), full)
            _slim(full, slim)
        except Exception as e:
            if not slim.exists():
                raise
            log.warning("play-by-play refetch for %s failed (%s); using the cached copy", season, e)
        finally:
            full.unlink(missing_ok=True)
    import pyarrow.parquet as pq

    have = [c for c in LINE_COLS if c in pq.read_schema(slim).names]
    df = pd.read_parquet(slim, columns=KEEP_COLS + have)
    for c in LINE_COLS:
        if c not in have:
            df[c] = float("nan")
    return df


def available(season: int, data_dir: Path) -> bool:
    """True when ``season``'s play-by-play is on disk or can be downloaded; False when the source has
    not published it (HTTP 404, the offseason). Any other failure raises: an outage is not the offseason."""
    if (Path(data_dir) / "pbp" / f"slim_{season}.parquet").exists():
        return True
    try:
        load(season, data_dir)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise
    return True


def is_garbage(df: pd.DataFrame) -> pd.Series:
    """True for plays snapped with the game out of reach (|margin| > 38 Q2, > 28 Q3, > 22 Q4)."""
    limit = df["period"].map(GARBAGE_MARGIN)
    return (df["pos_score_diff_start"].abs() > limit).fillna(False).astype(bool)


def scrimmage(df: pd.DataFrame) -> pd.DataFrame:
    """Rush and pass plays with an EPA value, garbage time removed."""
    keep = ((df["rush"] == 1) | (df["pass"] == 1)) & df["EPA"].notna() & ~is_garbage(df)
    return df[keep]

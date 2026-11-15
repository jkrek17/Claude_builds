"""Download and cache the nflverse files the models read."""
from __future__ import annotations

import datetime as dt
import time
import urllib.request
from pathlib import Path

GAMES_URL = "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv"
STATS_URL = "https://github.com/nflverse/nflverse-data/releases/download/stats_team/stats_team_week_{season}.csv"
FIRST_SEASON = 1999
DEFAULT_DIR = Path.home() / ".cache" / "nflmodel"


def _fetch(url: str, dest: Path) -> None:
    tmp = dest.with_suffix(".part")
    with urllib.request.urlopen(url, timeout=60) as resp:
        tmp.write_bytes(resp.read())
    tmp.replace(dest)


def refresh(data_dir: Path, season: int, max_age_hours: float = 3.0) -> None:
    """Make sure games.csv and every season's team stats are present; refresh the live files when stale.

    Finished seasons are downloaded once. games.csv and the given season's stats change weekly.
    """
    data_dir.mkdir(parents=True, exist_ok=True)
    cutoff = time.time() - max_age_hours * 3600
    live = {data_dir / "games.csv": GAMES_URL,
            data_dir / f"stats_team_week_{season}.csv": STATS_URL.format(season=season)}
    for path, url in live.items():
        if not path.exists() or path.stat().st_mtime < cutoff:
            _fetch(url, path)
    # A finished season's file is final once its playoffs are over; a copy cached before then is stale.
    final_after = dt.datetime(season, 3, 1).timestamp()
    for year in range(FIRST_SEASON, season):
        path = data_dir / f"stats_team_week_{year}.csv"
        if not path.exists() or (year == season - 1 and path.stat().st_mtime < final_after):
            _fetch(STATS_URL.format(season=year), path)

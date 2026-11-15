"""Download and cache the nflverse files the models read."""
from __future__ import annotations

import datetime as dt
import time
import urllib.request
from pathlib import Path

GAMES_URL = "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv"
STATS_URL = "https://github.com/nflverse/nflverse-data/releases/download/stats_team/stats_team_week_{season}.csv"
PLAYER_URL = "https://github.com/nflverse/nflverse-data/releases/download/stats_player/stats_player_week_{season}.csv"
SEASON_FILES = {"stats_team_week_{season}.csv": STATS_URL, "stats_player_week_{season}.csv": PLAYER_URL}
# Who is available: the official injury report and the weekly roster (current team and roster status).
# Only the season being predicted is kept fresh; fetch_availability() gets an earlier season on demand.
AVAILABILITY_FILES = {
    "injuries_{season}.csv": "https://github.com/nflverse/nflverse-data/releases/download/injuries/injuries_{season}.csv",
    "roster_weekly_{season}.csv": "https://github.com/nflverse/nflverse-data/releases/download/weekly_rosters/roster_weekly_{season}.csv",
}
# For the props model: offensive snap counts (from 2013) and the player id table that maps them.
SNAPS_URL = "https://github.com/nflverse/nflverse-data/releases/download/snap_counts/snap_counts_{season}.csv"
PLAYERS_URL = "https://github.com/nflverse/nflverse-data/releases/download/players/players.csv"
SNAPS_FIRST = 2013
LINEUPS_FIRST = 2019
FIRST_SEASON = 1999
DEFAULT_DIR = Path.home() / ".cache" / "nflmodel"


def _fetch(url: str, dest: Path) -> None:
    tmp = dest.with_suffix(".part")
    with urllib.request.urlopen(url, timeout=60) as resp:
        tmp.write_bytes(resp.read())
    tmp.replace(dest)


def refresh(data_dir: Path, season: int, max_age_hours: float = 3.0) -> None:
    """Make sure games.csv and every season's team and player stats are present; refresh the live files when stale.

    Finished seasons are downloaded once. games.csv and the given season's stats change weekly. The first run downloads about 250 MB.
    """
    data_dir.mkdir(parents=True, exist_ok=True)
    cutoff = time.time() - max_age_hours * 3600
    live = {data_dir / "games.csv": GAMES_URL}
    live.update({data_dir / name.format(season=season): url.format(season=season)
                 for name, url in SEASON_FILES.items()})
    live.update({data_dir / name.format(season=season): url.format(season=season)
                 for name, url in AVAILABILITY_FILES.items()})
    live[data_dir / f"snap_counts_{season}.csv"] = SNAPS_URL.format(season=season)
    live[data_dir / "players.csv"] = PLAYERS_URL
    for path, url in live.items():
        if not path.exists() or path.stat().st_mtime < cutoff:
            _fetch(url, path)
    # A finished season's file is final once its playoffs are over; a copy cached before then is stale.
    final_after = dt.datetime(season, 3, 1).timestamp()
    for year in range(FIRST_SEASON, season):
        for name, url in SEASON_FILES.items():
            path = data_dir / name.format(season=year)
            if not path.exists() or (year == season - 1 and path.stat().st_mtime < final_after):
                _fetch(url.format(season=year), path)
    # Past active lists for the props model's line-ups (game-day status is recorded from 2019).
    for year in range(LINEUPS_FIRST, season):
        path = data_dir / f"roster_weekly_{year}.csv"
        if not path.exists() or (year == season - 1 and path.stat().st_mtime < final_after):
            _fetch(AVAILABILITY_FILES["roster_weekly_{season}.csv"].format(season=year), path)
    for year in range(SNAPS_FIRST, season):
        path = data_dir / f"snap_counts_{year}.csv"
        if not path.exists() or (year == season - 1 and path.stat().st_mtime < final_after):
            _fetch(SNAPS_URL.format(season=year), path)


def fetch_availability(data_dir: Path, season: int) -> None:
    """Download one earlier season's injury report and weekly roster if they are not cached."""
    data_dir.mkdir(parents=True, exist_ok=True)
    for name, url in AVAILABILITY_FILES.items():
        path = data_dir / name.format(season=season)
        if not path.exists():
            _fetch(url.format(season=season), path)

"""Is a kickoff slot coming up? Used by the hourly schedule to rebuild the page before each one.

    python src/nflmodel/slots.py            # prints run=true or run=false

Standard library only, so the workflow can run it before anything is installed. nflverse game
times are US Eastern. With an hourly check and a window one hour wide, each slot (Thursday night,
Sunday 9:30, 1:00, 4:05 and 4:25 together, Sunday night, Monday night, holidays, Saturdays) gets
exactly one rebuild, 20 to 80 minutes before its first kickoff, after inactives are announced.
"""
from __future__ import annotations

import csv
import datetime as dt
import io
import sys
import urllib.request
from zoneinfo import ZoneInfo

GAMES_URL = "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv"
EASTERN = ZoneInfo("America/New_York")
WINDOW = (20, 80)   # minutes before kickoff


def due(rows, now: dt.datetime, window: tuple[int, int] = WINDOW) -> list[str]:
    """game_ids of unplayed games kicking off between window[0] and window[1] minutes after `now`."""
    out = []
    for r in rows:
        if r.get("home_score") or not r.get("gametime"):
            continue
        try:
            kick = dt.datetime.fromisoformat(f"{r['gameday']}T{r['gametime']}").replace(tzinfo=EASTERN)
        except ValueError:
            continue
        minutes = (kick - now).total_seconds() / 60
        if window[0] <= minutes < window[1]:
            out.append(r["game_id"])
    return out


def main() -> int:
    try:
        with urllib.request.urlopen(GAMES_URL, timeout=60) as resp:
            rows = list(csv.DictReader(io.StringIO(resp.read().decode())))
        games = due(rows, dt.datetime.now(dt.timezone.utc))
    except Exception as exc:   # if the schedule cannot be read, skip this hour and say why
        print(f"could not read the schedule: {exc}", file=sys.stderr)
        games = []
    print(f"kicking off soon: {', '.join(games) or 'nothing'}", file=sys.stderr)
    print(f"run={'true' if games else 'false'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

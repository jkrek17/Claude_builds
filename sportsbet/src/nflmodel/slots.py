"""Should this scheduled run rebuild the page? Yes before each kickoff slot, and once a day from 6 AM Eastern.

    python src/nflmodel/slots.py            # prints run=true or run=false

GitHub drops or delays many scheduled runs (some arrive hours late, the busy top-of-the-hour ones
often never come), so the daily rebuild is not tied to one cron time. Every hourly run checks the
live page instead: if it is 6 AM Eastern or later and the page was last built before 6 AM today,
this run rebuilds it. Whichever run comes first after 6 AM does the morning rebuild.

Standard library only, so the workflow can run it before anything is installed. nflverse game
times are US Eastern. With an hourly check and a window one hour wide, each slot (Thursday night,
Sunday 9:30, 1:00, 4:05 and 4:25 together, Sunday night, Monday night, holidays, Saturdays) gets
exactly one rebuild, 20 to 80 minutes before its first kickoff, after inactives are announced.
"""
from __future__ import annotations

import csv
import datetime as dt
import io
import json
import os
import sys
import urllib.request
from zoneinfo import ZoneInfo

GAMES_URL = "https://raw.githubusercontent.com/nflverse/nfldata/master/data/games.csv"
EASTERN = ZoneInfo("America/New_York")
WINDOW = (20, 80)   # minutes before kickoff
DAILY_HOUR = 6      # Eastern
SITE_JSON = os.environ.get("SITE_JSON", "https://jkrek17.github.io/Claude_builds/nfl/data/site.json")


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


def daily_due(now: dt.datetime, built: dt.datetime | None, hour: int = DAILY_HOUR) -> bool:
    """True once it is `hour` Eastern or later and the page was last built before `hour` today.

    `built` is None when the live page's build time cannot be read; then rebuild (after `hour`)."""
    local = now.astimezone(EASTERN)
    cutoff = local.replace(hour=hour, minute=0, second=0, microsecond=0)
    if local < cutoff:
        return False
    return built is None or built < cutoff


def live_built_at(url: str = SITE_JSON) -> dt.datetime | None:
    """When the published page was built (its generated_at), or None if it cannot be read."""
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            stamp = json.load(resp)["generated_at"]
        return dt.datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except Exception as exc:
        print(f"could not read the live page: {exc}", file=sys.stderr)
        return None


def main() -> int:
    now = dt.datetime.now(dt.timezone.utc)
    try:
        with urllib.request.urlopen(GAMES_URL, timeout=60) as resp:
            rows = list(csv.DictReader(io.StringIO(resp.read().decode())))
        games = due(rows, now)
    except Exception as exc:   # if the schedule cannot be read, skip the slot check and say why
        print(f"could not read the schedule: {exc}", file=sys.stderr)
        games = []
    print(f"kicking off soon: {', '.join(games) or 'nothing'}", file=sys.stderr)
    built = live_built_at()
    morning = daily_due(now, built)
    print(f"page last built: {built.isoformat() if built else 'unknown'}; "
          f"daily rebuild due: {'yes' if morning else 'no'}", file=sys.stderr)
    print(f"run={'true' if games or morning else 'false'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

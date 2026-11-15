"""Download the extra nflverse release files used by the usage research into ./data."""
import os, sys, urllib.request
HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
BASE = "https://github.com/nflverse/nflverse-data/releases/download/"
FILES = [f"snap_counts/snap_counts_{s}.csv" for s in range(2012, 2027)]
FILES += ["draft_picks/draft_picks.csv", "players/players.csv"]
FILES += [f"weekly_rosters/roster_weekly_{s}.csv" for s in range(2009, 2027)]
FILES += [f"injuries/injuries_{s}.csv" for s in range(2009, 2027)]
os.makedirs(HERE, exist_ok=True)
for f in FILES:
    out = os.path.join(HERE, os.path.basename(f))
    if os.path.exists(out) and os.path.getsize(out) > 1000:
        continue
    try:
        urllib.request.urlretrieve(BASE + f, out)
        print("ok", f, os.path.getsize(out))
    except Exception as e:
        print("FAIL", f, e)

"""Download past FanDuel and Fanatics spreads and totals near the close, one call per kickoff window.

Usage: ODDS_API_KEY=... python research/system/pull_game_lines.py OUT_DIR [first_season] [minutes_before] [max_calls]

One call returns every game on the board at that moment and costs 20 credits (two markets, one
region). Responses are saved as OUT_DIR/raw/{timestamp}.json.gz and the run can be resumed. The key
is read from the environment and never written anywhere.
"""
from __future__ import annotations

import datetime as dt
import gzip
import json
import sys
import time
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).parents[1] / "props" / "prices"))
from pull import get, kickoff  # noqa: E402


def main() -> None:
    out = Path(sys.argv[1])
    first = int(sys.argv[2]) if len(sys.argv) > 2 else 2025
    minutes = int(sys.argv[3]) if len(sys.argv) > 3 else 30
    limit = int(sys.argv[4]) if len(sys.argv) > 4 else 10 ** 9
    (out / "raw").mkdir(parents=True, exist_ok=True)
    games = pd.read_csv(Path.home() / ".cache" / "nflmodel" / "games.csv")
    games = games[(games["season"] >= first) & games["result"].notna()]
    windows = sorted({kickoff(r) for _, r in games.iterrows()})
    done = spent = 0
    left = None
    for k in windows:
        stamp = (k - dt.timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")
        path = out / "raw" / f"{stamp.replace(':', '')}.json.gz"
        if path.exists() or done >= limit:
            continue
        try:
            body, hdr = get("/odds", date=stamp, regions="us", bookmakers="fanduel,fanatics",
                            markets="spreads,totals", oddsFormat="american")
        except RuntimeError as err:
            print(f"{stamp}: {err}", flush=True)
            continue
        with gzip.open(path, "wt") as f:
            json.dump({"kickoff": k.strftime("%Y-%m-%dT%H:%M:%SZ"), **body}, f, separators=(",", ":"))
        done += 1
        spent += int(hdr.get("x-requests-last", hdr.get("X-Requests-Last", 0)) or 0)
        left = hdr.get("x-requests-remaining", hdr.get("X-Requests-Remaining"))
        if done % 20 == 0:
            print(f"{done} windows, {spent} credits this run, {left} left", flush=True)
        time.sleep(0.15)
    print(f"finished: {done} of {len(windows)} windows, {spent} credits this run, {left} left", flush=True)


if __name__ == "__main__":
    main()

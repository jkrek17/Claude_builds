"""Download past NFL player prop prices from The Odds API, one snapshot per game shortly before kickoff.

Usage: ODDS_API_KEY=... python research/props/prices/pull.py OUT_DIR [first_season] [minutes_before] [max_games]

The key is read from the environment and never written anywhere. Each game costs 10 credits per
market that returns data (60 for the six markets here); the events list costs 1 per game day.
Raw responses are saved as OUT_DIR/raw/{game_id}_{minutes}.json.gz and the run can be resumed.
"""
from __future__ import annotations

import datetime as dt
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from zoneinfo import ZoneInfo

import pandas as pd

BASE = "https://api.the-odds-api.com/v4/historical/sports/americanfootball_nfl"
MARKETS = "player_pass_yds,player_pass_tds,player_rush_yds,player_reception_yds,player_receptions,player_anytime_td"
TEAMS = {"Arizona Cardinals": "ARI", "Atlanta Falcons": "ATL", "Baltimore Ravens": "BAL", "Buffalo Bills": "BUF",
         "Carolina Panthers": "CAR", "Chicago Bears": "CHI", "Cincinnati Bengals": "CIN", "Cleveland Browns": "CLE",
         "Dallas Cowboys": "DAL", "Denver Broncos": "DEN", "Detroit Lions": "DET", "Green Bay Packers": "GB",
         "Houston Texans": "HOU", "Indianapolis Colts": "IND", "Jacksonville Jaguars": "JAX", "Kansas City Chiefs": "KC",
         "Las Vegas Raiders": "LV", "Los Angeles Chargers": "LAC", "Los Angeles Rams": "LA", "Miami Dolphins": "MIA",
         "Minnesota Vikings": "MIN", "New England Patriots": "NE", "New Orleans Saints": "NO", "New York Giants": "NYG",
         "New York Jets": "NYJ", "Philadelphia Eagles": "PHI", "Pittsburgh Steelers": "PIT", "San Francisco 49ers": "SF",
         "Seattle Seahawks": "SEA", "Tampa Bay Buccaneers": "TB", "Tennessee Titans": "TEN", "Washington Commanders": "WAS"}
ET = ZoneInfo("America/New_York")


def get(path: str, **params) -> tuple[dict, dict]:
    params["apiKey"] = os.environ["ODDS_API_KEY"]
    url = f"{BASE}{path}?{urllib.parse.urlencode(params)}"
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=60) as resp:
                return json.loads(resp.read()), dict(resp.headers)
        except urllib.error.HTTPError as err:
            if err.code in (401, 403, 422):
                raise RuntimeError(f"HTTP {err.code}: {err.read()[:200]!r}") from None   # never echo the URL (it holds the key)
            time.sleep(2 * (attempt + 1))
        except Exception:
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"gave up on {path}")


def kickoff(row) -> dt.datetime:
    h, m = (int(x) for x in str(row["gametime"]).split(":")[:2])
    d = dt.date.fromisoformat(str(row["gameday"]))
    return dt.datetime(d.year, d.month, d.day, h, m, tzinfo=ET).astimezone(dt.timezone.utc)


def main() -> None:
    out = Path(sys.argv[1])
    first = int(sys.argv[2]) if len(sys.argv) > 2 else 2023
    minutes = int(sys.argv[3]) if len(sys.argv) > 3 else 30
    limit = int(sys.argv[4]) if len(sys.argv) > 4 else 10 ** 9
    (out / "raw").mkdir(parents=True, exist_ok=True)
    games = pd.read_csv(Path.home() / ".cache" / "nflmodel" / "games.csv")
    games = games[(games["season"] >= first) & games["result"].notna()].sort_values(["gameday", "game_id"])
    fmt = lambda t: t.strftime("%Y-%m-%dT%H:%M:%SZ")
    done = spent = 0
    for day, grp in games.groupby("gameday", sort=True):
        todo = [r for _, r in grp.iterrows() if not (out / "raw" / f"{r['game_id']}_{minutes}.json.gz").exists()]
        if not todo or done >= limit:
            continue
        first_kick = min(kickoff(r) for r in todo)
        events, hdr = get("/events", date=fmt(first_kick - dt.timedelta(hours=3)))
        ids = {(TEAMS.get(e["home_team"]), TEAMS.get(e["away_team"])): e["id"] for e in events.get("data", [])}
        for r in todo:
            if done >= limit:
                break
            eid = ids.get((r["home_team"], r["away_team"])) or ids.get((r["away_team"], r["home_team"]))
            path = out / "raw" / f"{r['game_id']}_{minutes}.json.gz"
            if eid is None:
                print(f"{r['game_id']}: no event found", flush=True)
                continue
            try:
                body, hdr = get(f"/events/{eid}/odds", date=fmt(kickoff(r) - dt.timedelta(minutes=minutes)),
                                regions="us", oddsFormat="american", markets=MARKETS)
            except RuntimeError as err:
                print(f"{r['game_id']}: {err}", flush=True)
                continue
            with gzip.open(path, "wt") as f:
                json.dump({"game_id": r["game_id"], "kickoff": fmt(kickoff(r)), **body}, f, separators=(",", ":"))
            done += 1
            spent += int(hdr.get("x-requests-last", hdr.get("X-Requests-Last", 0)) or 0)
            if done % 25 == 0:
                print(f"{done} games, {spent} credits this run, {hdr.get('x-requests-remaining', hdr.get('X-Requests-Remaining'))} left ({r['game_id']})", flush=True)
            time.sleep(0.15)
    print(f"finished: {done} games, {spent} credits this run", flush=True)


if __name__ == "__main__":
    main()

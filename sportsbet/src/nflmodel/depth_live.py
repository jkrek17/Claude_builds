"""Today's expected starting quarterbacks from ESPN's depth charts and injury lists.

nflverse rosters and injury reports are refreshed once a day, overnight. ESPN's team depth chart
and injury list are current, so they are the first choice for the week about to be played; the
roster rule in `qb_depth.expected_starters` is the fallback when ESPN cannot be reached or a name
cannot be matched. The starter is the first quarterback on the depth chart who is not listed out.

No key is needed. Every failure is swallowed: a missing answer means "no opinion".
"""
from __future__ import annotations

import json
import re
import urllib.request

import pandas as pd

API = "https://site.api.espn.com/apis/site/v2/sports/football/nfl"
TEAM_MAP = {"LAR": "LA", "WSH": "WAS"}            # ESPN abbreviation -> nflverse
OUT = {"out", "doubtful", "injured reserve", "suspension", "physically unable to perform",
       "non-football injury", "reserve"}


def _get(url: str):
    with urllib.request.urlopen(url, timeout=20) as resp:
        return json.load(resp)


def _key(name: str) -> str:
    """Name with case, punctuation and suffixes removed, for matching across sources."""
    name = re.sub(r"[^a-z ]", "", str(name).lower())
    return " ".join(w for w in name.split() if w not in {"jr", "sr", "ii", "iii", "iv", "v"})


def is_out(status: str) -> bool:
    s = str(status or "").strip().lower()
    return s in OUT or "reserve" in s


def pick(depth_chart: list[str], statuses: dict) -> str | None:
    """First quarterback on the depth chart who is not listed out. `statuses` maps name to status."""
    out = {_key(n) for n, s in statuses.items() if is_out(s)}
    for name in depth_chart:
        if _key(name) not in out:
            return name
    return None


def starters(dates: list[str], get=_get) -> dict:
    """{team: expected starting quarterback's name} for games on `dates` (YYYY-MM-DD) not yet started."""
    found: dict = {}
    for day in sorted(set(dates)):
        try:
            events = get(f"{API}/scoreboard?dates={day.replace('-', '')}").get("events", [])
        except Exception:
            continue
        for event in events:
            comp = event["competitions"][0]
            if comp.get("status", {}).get("type", {}).get("state") != "pre":
                continue
            try:
                injuries = get(f"{API}/summary?event={event['id']}").get("injuries", [])
            except Exception:
                continue
            status = {}
            for team in injuries:
                for item in team.get("injuries", []):
                    status[item.get("athlete", {}).get("displayName")] = item.get("status")
            for side in comp["competitors"]:
                abbr = side["team"]["abbreviation"]
                try:
                    charts = get(f"{API}/teams/{side['team']['id']}/depthcharts").get("depthchart", [])
                except Exception:
                    continue
                for chart in charts:
                    qbs = chart.get("positions", {}).get("qb", {}).get("athletes", [])
                    name = pick([a.get("displayName") for a in qbs], status)
                    if name:
                        found[TEAM_MAP.get(abbr, abbr)] = name
                        break
    return found


def apply(games: pd.DataFrame, season: int, week: int, live: dict, ids: dict) -> tuple[pd.DataFrame, list[dict]]:
    """Set the starter of each unplayed game of the week to ESPN's, where the name maps to a player id.

    `live` is `starters(...)`; `ids` maps a quarterback's name to his nflverse id (see `name_ids`).
    Returns the new games and the changes made ({game_id, team, was, now, was_name, now_name})."""
    ids = {_key(k): v for k, v in ids.items()}
    g = games.copy()
    changes = []
    for i in g.index[(g["season"] == season) & (g["week"] == week) & g["home_score"].isna()]:
        for s in ("home", "away"):
            team = g.at[i, f"{s}_team"]
            name = live.get(team)
            new = ids.get(_key(name)) if name else None
            old = g.at[i, f"{s}_qb_id"]
            if new is None or new == old:
                continue
            was_name = g.at[i, f"{s}_qb_name"] if f"{s}_qb_name" in g.columns else None
            changes.append({"game_id": g.at[i, "game_id"], "team": team, "was": old if isinstance(old, str) else None,
                            "now": new, "was_name": was_name if isinstance(was_name, str) else "the listed starter",
                            "now_name": name})
            g.at[i, f"{s}_qb_id"] = new
            if f"{s}_qb_name" in g.columns:
                g.at[i, f"{s}_qb_name"] = name
    return g, changes


def name_ids(data_dir: str) -> dict:
    """{quarterback name: nflverse id} from the cached players.csv; empty if the file is missing."""
    try:
        p = pd.read_csv(f"{data_dir}/players.csv", usecols=["gsis_id", "display_name", "position", "last_season"],
                        low_memory=False)
    except (FileNotFoundError, ValueError):
        return {}
    p = p[(p["position"] == "QB") & p["gsis_id"].notna()].sort_values("last_season")
    return dict(zip(p["display_name"], p["gsis_id"]))   # the most recent player wins a shared name

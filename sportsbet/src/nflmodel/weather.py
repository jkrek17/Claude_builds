"""Kickoff wind forecasts for upcoming outdoor games, from the US National Weather Service API."""
from __future__ import annotations

import datetime as dt
import json
import re
import urllib.request
from zoneinfo import ZoneInfo

import pandas as pd

API = "https://api.weather.gov"
EASTERN = ZoneInfo("America/New_York")
GAME_HOURS = 3

# Home stadium (latitude, longitude) for teams that play outdoors. Teams with a dome or a retractable
# roof are left out: the totals model treats their home games as indoor.
STADIUMS = {
    "BAL": (39.278, -76.623), "BUF": (42.774, -78.787), "CAR": (35.226, -80.853),
    "CHI": (41.862, -87.617), "CIN": (39.095, -84.516), "CLE": (41.506, -81.699),
    "DEN": (39.744, -105.020), "GB": (44.501, -88.062), "JAX": (30.324, -81.637),
    "KC": (39.049, -94.484), "MIA": (25.958, -80.239), "NE": (42.091, -71.264),
    "NYG": (40.813, -74.074), "NYJ": (40.813, -74.074), "PHI": (39.901, -75.167),
    "PIT": (40.447, -80.016), "SEA": (47.595, -122.332), "SF": (37.403, -121.970),
    "TB": (27.976, -82.503), "TEN": (36.166, -86.771), "WAS": (38.908, -76.864),
}


def _get(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "nflmodel-cli", "Accept": "application/geo+json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def _mph(text: str | None) -> float | None:
    """'5 mph' -> 5.0, '10 to 15 mph' -> 12.5."""
    nums = [float(x) for x in re.findall(r"\d+(?:\.\d+)?", text or "")]
    return sum(nums) / len(nums) if nums else None


def kickoff(gameday: str, gametime: str | None) -> dt.datetime | None:
    """Kickoff as an aware datetime; nflverse gives the time in US Eastern."""
    if not isinstance(gametime, str) or ":" not in gametime:
        return None
    hour, minute = (int(x) for x in gametime.split(":")[:2])
    return dt.datetime.fromisoformat(gameday).replace(hour=hour, minute=minute, tzinfo=EASTERN)


def wind_at(periods: list[dict], start: dt.datetime, hours: int = GAME_HOURS) -> float | None:
    """Mean sustained wind (mph) over the hourly forecast periods that overlap the game."""
    end = start + dt.timedelta(hours=hours)
    speeds = []
    for p in periods:
        t = dt.datetime.fromisoformat(p["startTime"])
        if start - dt.timedelta(minutes=59) <= t < end:
            mph = _mph(p.get("windSpeed"))
            if mph is not None:
                speeds.append(mph)
    return sum(speeds) / len(speeds) if speeds else None


def forecast_winds(games: pd.DataFrame, fetch=_get) -> dict[str, float]:
    """{game_id: forecast wind mph} for unplayed games at outdoor home stadiums within forecast range.

    Games at neutral sites, indoors, without a kickoff time or beyond the roughly 7-day hourly
    forecast are skipped, as is any game whose forecast cannot be fetched. The caller falls back to
    climatology for those.
    """
    todo = games[games["result"].isna() & (games["location"] == "Home")
                 & games["home_team"].isin(list(STADIUMS))]
    grid: dict[str, list[dict]] = {}
    out: dict[str, float] = {}
    for _, g in todo.iterrows():
        start = kickoff(str(g["gameday"]), g.get("gametime"))
        if start is None or not dt.timedelta(hours=-3) < start - dt.datetime.now(EASTERN) < dt.timedelta(days=7):
            continue
        team = g["home_team"]
        try:
            if team not in grid:
                lat, lon = STADIUMS[team]
                hourly = fetch(f"{API}/points/{lat},{lon}")["properties"]["forecastHourly"]
                grid[team] = fetch(hourly)["properties"]["periods"]
            mph = wind_at(grid[team], start)
        except Exception:  # network trouble or an unexpected payload: fall back to climatology
            grid.setdefault(team, [])
            continue
        if mph is not None:
            out[g["game_id"]] = mph
    return out

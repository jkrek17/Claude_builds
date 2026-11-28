"""This week's FBS schedule, scores and lines from ESPN's keyless scoreboard.

``espn_week`` reads one week of
``https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard`` with
``groups=80`` (games with an FBS team) and gives one row per game. ESPN event ids equal the
play-by-play ``game_id`` and CollegeFootballData game ids, and ESPN team ids equal CFBD team ids.

Lines: ESPN lists one sportsbook per game (``competitions[0].odds[0]``, DraftKings in 2026) and only
before kickoff; finished games come back without odds. The home spread is read from
``pointSpread.home.close.line``, else the ``spread`` field (home perspective, negative = home
favored), else ``details`` ("ND -25.5") matched against the teams' abbreviations. The last pregame
line seen for each game is kept in ``data_dir/espn/lines_<season>.json`` so a final keeps its line
(``remember_lines``).

Responses are cached in ``data_dir/espn/`` for ``MAX_AGE_S`` (30 minutes); a week that ended more
than a day ago is kept for ``PAST_MAX_AGE_S``.
"""
from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

import numpy as np
import pandas as pd

SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard"
FBS_GROUP = 80
REGULAR, POSTSEASON = 2, 3
MAX_AGE_S = 30 * 60
PAST_MAX_AGE_S = 7 * 24 * 3600
TIMEOUT_S = 30

# ESPN group ids of FBS conferences (team.conferenceId on the scoreboard).
CONFERENCES = {
    "1": "ACC", "4": "Big 12", "5": "Big Ten", "8": "SEC", "9": "Pac-12", "12": "Conference USA",
    "15": "Mid-American", "17": "Mountain West", "18": "FBS Independents", "37": "Sun Belt",
    "151": "American Athletic",
}

COLUMNS = ["game_id", "season", "week", "season_type", "start_date", "home", "away", "home_id", "away_id",
           "home_abbr", "away_abbr", "home_conf", "away_conf", "neutral", "completed", "state",
           "home_points", "away_points", "spread_line", "total_line", "line_provider"]


# ---------------------------------------------------------------------------------------------
# Fetch and cache
# ---------------------------------------------------------------------------------------------

def _url(params: dict) -> str:
    return SCOREBOARD_URL + "?" + urllib.parse.urlencode(params)


def _fetch(params: dict) -> dict:
    req = urllib.request.Request(_url(params), headers={"User-Agent": "cfbmodel"})
    with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
        return json.loads(resp.read().decode("utf-8"))


def scoreboard(params: dict, data_dir: Path, max_age: float = MAX_AGE_S) -> dict:
    """The scoreboard response for ``params`` (``groups`` and ``limit`` added), cached on disk."""
    full = {"groups": FBS_GROUP, "limit": 300, **params}
    path = _cache_path(params, data_dir)
    if path.exists() and time.time() - path.stat().st_mtime < max_age:
        return json.loads(path.read_text())
    data = _fetch(full)
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_name(path.name + ".part")
    part.write_text(json.dumps(data))
    part.replace(path)
    return data


# ---------------------------------------------------------------------------------------------
# Parse
# ---------------------------------------------------------------------------------------------

def _float(x) -> float:
    if x is None:
        return np.nan
    if isinstance(x, (int, float)):
        return float(x)
    s = str(x).strip().lower().lstrip("ou")
    if s in ("pk", "even", "ev"):
        return 0.0
    try:
        return float(s)
    except ValueError:
        return np.nan


def parse_odds(comp: dict, home_abbr: str | None, away_abbr: str | None) -> tuple[float, float, str | None]:
    """(home spread, total, provider name) from a competition's first odds entry; NaN when absent."""
    odds = comp.get("odds") or []
    if not odds:
        return np.nan, np.nan, None
    o = odds[0] or {}
    provider = (o.get("provider") or {}).get("name")
    spread = _float(((o.get("pointSpread") or {}).get("home") or {}).get("close", {}).get("line"))
    if not np.isfinite(spread):
        spread = _float(o.get("spread"))
    if not np.isfinite(spread):
        details = str(o.get("details") or "").strip()
        if details.upper() in ("EVEN", "PK", "PICK"):
            spread = 0.0
        elif " " in details:
            abbr, _, num = details.rpartition(" ")
            val = _float(num)
            if np.isfinite(val):
                if abbr == home_abbr:
                    spread = -abs(val)
                elif abbr == away_abbr:
                    spread = abs(val)
    total = _float(o.get("overUnder"))
    if not np.isfinite(total):
        total = _float(((o.get("total") or {}).get("over") or {}).get("close", {}).get("line"))
    if not (np.isfinite(spread) or np.isfinite(total)):
        provider = None
    return spread, total, provider


def _conf(team: dict) -> str | None:
    cid = team.get("conferenceId")
    return CONFERENCES.get(str(cid)) if cid is not None else None


def _score(comp: dict) -> float:
    try:
        return float(comp.get("score"))
    except (TypeError, ValueError):
        return np.nan


def parse(data: dict, season: int | None = None, week: int | None = None) -> pd.DataFrame:
    """One row per game (``COLUMNS``) from a scoreboard response."""
    s_info = data.get("season") or {}
    season = season if season is not None else s_info.get("year")
    stype = s_info.get("type", REGULAR)
    week = week if week is not None else (data.get("week") or {}).get("number")
    rows = []
    for ev in data.get("events") or []:
        comps = ev.get("competitions") or []
        if not comps:
            continue
        comp = comps[0]
        teams = {c.get("homeAway"): c for c in comp.get("competitors") or []}
        if "home" not in teams or "away" not in teams:
            continue
        h, a = teams["home"], teams["away"]
        ht, at = h.get("team") or {}, a.get("team") or {}
        status = ((comp.get("status") or ev.get("status") or {}).get("type") or {})
        completed = bool(status.get("completed"))
        spread, total, provider = parse_odds(comp, ht.get("abbreviation"), at.get("abbreviation"))
        ev_season = (ev.get("season") or {})
        rows.append({
            "game_id": int(ev["id"]),
            "season": int(ev_season.get("year") or season),
            "week": int((ev.get("week") or {}).get("number") or week),
            "season_type": int(ev_season.get("type") or stype),
            "start_date": comp.get("date") or ev.get("date"),
            "home": ht.get("location") or ht.get("shortDisplayName"),
            "away": at.get("location") or at.get("shortDisplayName"),
            "home_id": int(ht["id"]), "away_id": int(at["id"]),
            "home_abbr": ht.get("abbreviation"), "away_abbr": at.get("abbreviation"),
            "home_conf": _conf(ht), "away_conf": _conf(at),
            "neutral": bool(comp.get("neutralSite")),
            "completed": completed,
            "state": status.get("state"),
            "home_points": _score(h) if completed else np.nan,
            "away_points": _score(a) if completed else np.nan,
            "spread_line": spread, "total_line": total, "line_provider": provider,
        })
    out = pd.DataFrame(rows, columns=COLUMNS)
    out["start_date"] = pd.to_datetime(out["start_date"], utc=True)
    for c in ("home_points", "away_points", "spread_line", "total_line"):
        out[c] = out[c].astype(float)
    for c in ("home_conf", "away_conf", "line_provider"):
        out[c] = out[c].astype(object).where(out[c].notna(), None)
    out = out.drop_duplicates("game_id")
    return out.sort_values(["start_date", "game_id"], kind="stable").reset_index(drop=True)


# ---------------------------------------------------------------------------------------------
# Public helpers
# ---------------------------------------------------------------------------------------------

def calendar(data: dict, season_type: int = REGULAR) -> list[dict]:
    """The calendar entries (label, value, startDate, endDate) of one season type."""
    for league in data.get("leagues") or []:
        for part in league.get("calendar") or []:
            if isinstance(part, dict) and str(part.get("value")) == str(season_type):
                return list(part.get("entries") or [])
    return []


def _cache_path(params: dict, data_dir: Path) -> Path:
    full = {"groups": FBS_GROUP, "limit": 300, **params}
    return Path(data_dir) / "espn" / ("_".join(f"{k}-{full[k]}" for k in sorted(full)) + ".json")


def _week_ended(data: dict, week: int, now: pd.Timestamp) -> bool:
    """True when the calendar says ``week`` ended more than a day before ``now``."""
    for e in calendar(data):
        if str(e.get("value")) == str(week):
            end = pd.to_datetime(e.get("endDate"), utc=True, errors="coerce")
            return not pd.isna(end) and end + pd.Timedelta(days=1) < now
    return False


def espn_week(season: int, week: int | None, data_dir: Path, season_type: int = REGULAR) -> pd.DataFrame:
    """Every game of one week with an FBS team: ``COLUMNS``, start_date in UTC.

    ``week=None`` asks ESPN for the current week. Spread is home perspective (negative = home
    favored); scores are NaN until a game is final."""
    params = {"dates": season, "seasontype": season_type}
    if week is not None:
        params["week"] = week
    max_age = MAX_AGE_S
    cached = _cache_path(params, data_dir)
    if week is not None and cached.exists():
        try:
            if _week_ended(json.loads(cached.read_text()), week, pd.Timestamp.now(tz="UTC")):
                max_age = PAST_MAX_AGE_S  # a finished week no longer changes
        except ValueError:
            pass
    return remember_lines(parse(scoreboard(params, data_dir, max_age), season, week), season, data_dir)


def _lines_path(season: int, data_dir: Path) -> Path:
    return Path(data_dir) / "espn" / f"lines_{season}.json"


def remember_lines(games: pd.DataFrame, season: int, data_dir: Path) -> pd.DataFrame:
    """Keep the last pregame line seen for each game, and give it back once ESPN drops it.

    ESPN lists odds only before kickoff. Each line seen on a game not yet final is saved in
    ``data_dir/espn/lines_<season>.json``; a game that comes back without one (in progress or final)
    takes the saved line, so a final keeps the line it closed at as of the last run before kickoff."""
    path = _lines_path(season, data_dir)
    try:
        store = json.loads(path.read_text()) if path.exists() else {}
    except ValueError:
        store = {}
    changed = False
    games = games.copy()
    for i, r in games.iterrows():
        key = str(int(r["game_id"]))
        has = np.isfinite(r["spread_line"]) or np.isfinite(r["total_line"])
        if has and not r["completed"]:
            entry = {"spread": None if np.isnan(r["spread_line"]) else float(r["spread_line"]),
                     "total": None if np.isnan(r["total_line"]) else float(r["total_line"]),
                     "provider": r["line_provider"]}
            if store.get(key) != entry:
                store[key] = entry
                changed = True
        elif not has and key in store:
            saved = store[key]
            games.at[i, "spread_line"] = np.nan if saved.get("spread") is None else saved["spread"]
            games.at[i, "total_line"] = np.nan if saved.get("total") is None else saved["total"]
            games.at[i, "line_provider"] = saved.get("provider")
    if changed:
        path.parent.mkdir(parents=True, exist_ok=True)
        part = path.with_name(path.name + ".part")
        part.write_text(json.dumps(store, sort_keys=True))
        part.replace(path)
    return games


def season_info(season: int, data_dir: Path) -> dict:
    """``{"week": int, "season_type": int, "weeks": [regular-season week numbers]}`` for a season.

    The current season reports ESPN's current week; a finished or postseason season reports its last
    regular-season week."""
    data = scoreboard({}, data_dir)
    league = (data.get("leagues") or [{}])[0]
    league_season = league.get("season") or {}
    year = int(league_season.get("year") or 0)
    stype = int(((league_season.get("type") or {}).get("type")) or REGULAR)
    if year != int(season):  # another season: read its calendar from its week 1
        data = scoreboard({"dates": season, "seasontype": REGULAR, "week": 1}, data_dir, PAST_MAX_AGE_S)
    weeks = sorted(int(e["value"]) for e in calendar(data) if str(e.get("value", "")).isdigit())
    number = int((data.get("week") or {}).get("number") or 0)
    if year == int(season) and stype == REGULAR and number:
        week = number
    elif year < int(season) or (year == int(season) and stype < REGULAR):
        week = weeks[0] if weeks else 1  # not started: week 1 is next
    else:
        week = weeks[-1] if weeks else max(number, 1)  # postseason or a finished season
    return {"week": week, "season_type": stype if year == int(season) else REGULAR, "weeks": weeks}


def current_week(season: int, data_dir: Path) -> int:
    """The regular-season week being played (from the scoreboard's ``week.number`` and season type);
    the last regular-season week once the regular season is over."""
    return season_info(season, data_dir)["week"]

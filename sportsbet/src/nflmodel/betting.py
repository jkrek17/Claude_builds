"""This week's bets under the rulebook in research/system/RULEBOOK.md.

    nflprops --json props.json
    ODDS_API_KEY=... nflbets props.json [--placed N] [--within MINUTES]

The rules (chosen on 2023 and 2024, scored once on 2025 and 2026; see research/system/README.md):

1. FanDuel main line, receiving yards and receptions, the under only.
2. The model's edge at the posted price is at least 10%.
3. The edge after mixing the model with the book's own chance is at least 3.1%.
4. At most one bet per player and two per game.
5. Take them in kickoff order, $5 flat, until 10 are placed in the week.

Prices are read live from The Odds API (about 2 credits a game). The key is read from the environment
and never written anywhere. ``--odds-file`` reads saved event responses instead.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from zoneinfo import ZoneInfo

BOOK = "fanduel"
MARKETS = {"player_reception_yds": "rec_yds", "player_receptions": "receptions"}
MIN_EDGE = 0.10          # rule 2
MIN_BLEND_EDGE = 0.031   # rule 3
BLEND_WEIGHT = 0.34      # weight on the model when mixing with the book, fitted on 2023 and 2024
PER_GAME = 2             # rule 4
WEEK_BETS = 10           # rule 5
STAKE = 5.0
LABEL = {"rec_yds": "receiving yards", "receptions": "receptions"}
BASE = "https://api.the-odds-api.com/v4/sports/americanfootball_nfl"
TEAMS = {"Arizona Cardinals": "ARI", "Atlanta Falcons": "ATL", "Baltimore Ravens": "BAL", "Buffalo Bills": "BUF",
         "Carolina Panthers": "CAR", "Chicago Bears": "CHI", "Cincinnati Bengals": "CIN", "Cleveland Browns": "CLE",
         "Dallas Cowboys": "DAL", "Denver Broncos": "DEN", "Detroit Lions": "DET", "Green Bay Packers": "GB",
         "Houston Texans": "HOU", "Indianapolis Colts": "IND", "Jacksonville Jaguars": "JAX", "Kansas City Chiefs": "KC",
         "Las Vegas Raiders": "LV", "Los Angeles Chargers": "LAC", "Los Angeles Rams": "LA", "Miami Dolphins": "MIA",
         "Minnesota Vikings": "MIN", "New England Patriots": "NE", "New Orleans Saints": "NO", "New York Giants": "NYG",
         "New York Jets": "NYJ", "Philadelphia Eagles": "PHI", "Pittsburgh Steelers": "PIT", "San Francisco 49ers": "SF",
         "Seattle Seahawks": "SEA", "Tampa Bay Buccaneers": "TB", "Tennessee Titans": "TEN", "Washington Commanders": "WAS"}
ET = ZoneInfo("America/New_York")


def norm(name: str) -> str:
    """Lower-case name without punctuation or generational suffixes, for matching across sources."""
    s = re.sub(r"[^a-z ]", "", str(name).lower().replace("-", " "))
    return " ".join(w for w in s.split() if w not in {"jr", "sr", "ii", "iii", "iv", "v"})


def decimal(american: float) -> float:
    return 1 + american / 100 if american > 0 else 1 + 100 / abs(american)


def american(dec: float) -> str:
    a = (dec - 1) * 100 if dec >= 2 else -100 / (dec - 1)
    return f"{a:+.0f}"


def _logit(p: float) -> float:
    return math.log(p / (1 - p))


def blend(p_model: float, p_book: float, weight: float = BLEND_WEIGHT) -> float:
    """The book's chance moved part of the way toward the model's, on the log-odds scale."""
    return 1 / (1 + math.exp(-(_logit(p_book) + weight * (_logit(p_model) - _logit(p_book)))))


def prob_under(row: dict, line: float, probs: list[float]) -> float:
    """The model's chance that the player finishes under the line."""
    if row.get("pmf"):
        over = sum(row["pmf"][int(math.floor(line)) + 1:])
    else:
        q = row["q"]
        if line <= q[0]:
            cdf = 0.0
        elif line >= q[-1]:
            cdf = 1.0
        else:
            i = next(k for k in range(1, len(q)) if q[k] >= line)
            span = q[i] - q[i - 1]
            cdf = probs[i - 1] + (probs[i] - probs[i - 1]) * ((line - q[i - 1]) / span if span else 1.0)
        over = 1 - cdf
    return min(max(1 - over, 0.005), 0.995)


def main_lines(event: dict, book: str = BOOK) -> list[dict]:
    """One line per player and prop from an Odds API event: two-sided and the most evenly priced."""
    best: dict[tuple, dict] = {}
    for bk in event.get("bookmakers", []):
        if bk["key"] != book:
            continue
        for m in bk["markets"]:
            prop = MARKETS.get(m["key"])
            if prop is None:
                continue
            sides: dict[tuple, dict] = {}
            for o in m["outcomes"]:
                sides.setdefault((o.get("description"), o.get("point")), {})[o["name"]] = o["price"]
            for (player, point), s in sides.items():
                if "Over" not in s or "Under" not in s or point is None:
                    continue
                over, under = decimal(s["Over"]), decimal(s["Under"])
                row = {"player": player, "name_key": norm(player), "prop": prop, "line": float(point),
                       "dec_under": under, "p_book_under": (1 / under) / (1 / over + 1 / under),
                       "skew": abs(1 / over - 1 / under)}
                key = (row["name_key"], prop)
                if key not in best or row["skew"] < best[key]["skew"]:
                    best[key] = row
    return list(best.values())


def candidates(props: dict, events: list[dict]) -> list[dict]:
    """Every FanDuel under that passes rules 1 to 3, with the worst price still worth taking."""
    model = {(r["team"], norm(r["player"]), r["prop"]): r for r in props["rows"] if r["prop"] in LABEL}
    out = []
    for ev in events:
        teams = (TEAMS.get(ev.get("home_team")), TEAMS.get(ev.get("away_team")))
        for ln in main_lines(ev):
            row = next((model[(t, ln["name_key"], ln["prop"])] for t in teams
                        if (t, ln["name_key"], ln["prop"]) in model), None)
            if row is None:
                continue
            p_model = prob_under(row, ln["line"], props["probs"])
            p_blend = blend(p_model, ln["p_book_under"])
            edge, bedge = p_model * ln["dec_under"] - 1, p_blend * ln["dec_under"] - 1
            if edge < MIN_EDGE or bedge < MIN_BLEND_EDGE:
                continue
            floor = max((1 + MIN_EDGE) / p_model, (1 + MIN_BLEND_EDGE) / p_blend)
            out.append({"game": f"{teams[1]} at {teams[0]}", "game_key": ev.get("id") or f"{teams[1]}@{teams[0]}",
                        "kickoff": ev.get("commence_time", ""), "player": row["player"], "team": row["team"],
                        "name_key": ln["name_key"], "prop": ln["prop"], "line": ln["line"],
                        "price": ln["dec_under"], "worst_price": floor, "p_model": p_model,
                        "p_book": ln["p_book_under"], "edge": edge, "score": bedge})
    return out


def select(cands: list[dict], placed: int = 0) -> list[dict]:
    """Rules 4 and 5: one per player, two per game, kickoff order, up to the weekly cap."""
    per_game: dict[str, int] = {}
    seen: set[str] = set()
    spread = []
    for c in sorted(cands, key=lambda c: -c["score"]):
        if c["name_key"] in seen or per_game.get(c["game_key"], 0) >= PER_GAME:
            continue
        seen.add(c["name_key"])
        per_game[c["game_key"]] = per_game.get(c["game_key"], 0) + 1
        spread.append(c)
    spread.sort(key=lambda c: (c["kickoff"], -c["score"]))
    return spread[:max(0, WEEK_BETS - placed)]


def render(picks: list[dict], placed: int) -> str:
    if not picks:
        return "No bets pass the rules right now." if placed < WEEK_BETS else "Weekly cap reached. No more bets."
    lines = [f"{len(picks)} bets at ${STAKE:.0f} each (${len(picks) * STAKE:.0f}); "
             f"{placed} already placed this week, cap {WEEK_BETS}.", ""]
    for c in picks:
        when = ""
        if c["kickoff"]:
            t = dt.datetime.fromisoformat(c["kickoff"].replace("Z", "+00:00")).astimezone(ET)
            when = t.strftime("%a %-I:%M %p ET")
        lines.append(f"{c['player']} ({c['team']}) UNDER {c['line']:g} {LABEL[c['prop']]} at {american(c['price'])}"
                     f"  [{c['game']}, {when}]")
        lines.append(f"    model {c['p_model']:.0%}, book {c['p_book']:.0%}; skip if the price is worse than "
                     f"{american(c['worst_price'])} or the line is lower than {c['line']:g}")
    lines += ["", "FanDuel only. Bet from the day before kickoff, after the final injury report.",
              "The edge behind these rules is small and not proven; expect losing weeks."]
    return "\n".join(lines)


def _get(path: str, **params) -> object:
    params["apiKey"] = os.environ["ODDS_API_KEY"]
    try:
        with urllib.request.urlopen(f"{BASE}{path}?{urllib.parse.urlencode(params)}", timeout=60) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as err:   # never echo the URL (it holds the key)
        raise SystemExit(f"The Odds API answered HTTP {err.code}") from None


def fetch_events(within_minutes: float) -> list[dict]:
    now = dt.datetime.now(dt.timezone.utc)
    out = []
    for ev in _get("/events"):
        start = dt.datetime.fromisoformat(ev["commence_time"].replace("Z", "+00:00"))
        if start <= now or (within_minutes and start > now + dt.timedelta(minutes=within_minutes)):
            continue
        out.append(_get(f"/events/{ev['id']}/odds", regions="us", bookmakers=BOOK, oddsFormat="american",
                        markets=",".join(MARKETS)))
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="nflbets", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("props", help="predictions written by `nflprops --json`")
    parser.add_argument("--placed", type=int, default=0, help="bets already placed this week")
    parser.add_argument("--within", type=float, default=60,
                        help="only games kicking off in the next MINUTES (0 = every game not yet started)")
    parser.add_argument("--odds-file", help="read saved Odds API event responses (a JSON list) instead of the live API")
    args = parser.parse_args(argv)
    props = json.loads(Path(args.props).read_text())
    if args.odds_file:
        events = json.loads(Path(args.odds_file).read_text())
    elif "ODDS_API_KEY" not in os.environ:
        parser.error("set ODDS_API_KEY or pass --odds-file")
    else:
        events = fetch_events(args.within)
    print(render(select(candidates(props, events), args.placed), args.placed))
    return 0


if __name__ == "__main__":
    sys.exit(main())

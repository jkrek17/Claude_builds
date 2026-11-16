"""Top plays for the web page: this week's FanDuel unders that pass the betting rules, best first.

    nflprops --json props.json
    ODDS_API_KEY=... python -m nflmodel.plays props.json plays.json

Uses the rules in ``betting.py`` (rules 1 to 4 of research/system/RULEBOOK.md) and ranks by the edge
after mixing the model with the book. The weekly cap and kickoff order (rule 5) are left to the reader,
because the page does not know which bets were placed. Only this week's games that have not kicked off
are priced, at about 2 credits a game. The key is read from the environment and never written anywhere.
``--odds-file`` reads saved Odds API event responses instead.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
from pathlib import Path

from . import betting as B


def week_games(props: dict) -> dict[tuple, str]:
    """(home, away) -> game id for the games in the props file. Game ids read season_week_away_home."""
    out = {}
    for gid in {r["game_id"] for r in props["rows"]}:
        _, _, away, home = gid.split("_")
        out[(home, away)] = gid
    return out


def fetch(props: dict, now: dt.datetime | None = None) -> list[dict]:
    """Live FanDuel prices for this week's games that have not started."""
    now = now or dt.datetime.now(dt.timezone.utc)
    games = week_games(props)
    out = []
    for ev in B._get("/events"):
        start = dt.datetime.fromisoformat(ev["commence_time"].replace("Z", "+00:00"))
        if start <= now or (B.TEAMS.get(ev.get("home_team")), B.TEAMS.get(ev.get("away_team"))) not in games:
            continue
        out.append(B._get(f"/events/{ev['id']}/odds", regions="us", bookmakers=B.BOOK, oddsFormat="american",
                          markets=",".join(B.MARKETS)))
    return out


def build(props: dict, events: list[dict], now: dt.datetime | None = None) -> dict:
    """The page's data: plays that pass rules 1 to 4, ranked by blended edge."""
    now = now or dt.datetime.now(dt.timezone.utc)
    games = week_games(props)
    rows = {(r["team"], B.norm(r["player"]), r["prop"]): r for r in props["rows"] if r["prop"] in B.LABEL}
    events = [ev for ev in events
              if (B.TEAMS.get(ev.get("home_team")), B.TEAMS.get(ev.get("away_team"))) in games
              and dt.datetime.fromisoformat(ev["commence_time"].replace("Z", "+00:00")) > now]
    priced = sum(1 for ev in events if B.main_lines(ev))
    plays, seen, per_game = [], set(), {}
    for c in sorted(B.candidates(props, events), key=lambda c: -c["score"]):
        if c["name_key"] in seen or per_game.get(c["game_key"], 0) >= B.PER_GAME:   # rule 4
            continue
        seen.add(c["name_key"])
        per_game[c["game_key"]] = per_game.get(c["game_key"], 0) + 1
        row = rows[(c["team"], c["name_key"], c["prop"])]
        plays.append({"player_id": row["player_id"], "player": row["player"], "team": row["team"],
                      "opponent": row["opponent"], "position": row["position"], "game_id": row["game_id"],
                      "kickoff": c["kickoff"], "prop": c["prop"], "side": "under", "line": c["line"],
                      "price": B.american(c["price"]), "worst_price": B.american(c["worst_price"]),
                      "p_model": round(c["p_model"], 4), "p_book": round(c["p_book"], 4),
                      "model_edge": round(c["edge"], 4), "edge": round(c["score"], 4)})
    return {"generated_at": now.strftime("%Y-%m-%dT%H:%M:%SZ"), "season": props["season"], "week": props["week"],
            "book": "FanDuel", "games_open": len(events), "games_priced": priced,
            "rules": {"min_model_edge": B.MIN_EDGE, "min_edge": B.MIN_BLEND_EDGE, "per_game": B.PER_GAME,
                      "week_bets": B.WEEK_BETS},
            "plays": plays}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="nflmodel.plays", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("props", help="predictions written by `nflprops --json`")
    parser.add_argument("out", help="where to write the page's plays file")
    parser.add_argument("--odds-file", help="read saved Odds API event responses (a JSON list) instead of the live API")
    args = parser.parse_args(argv)
    props = json.loads(Path(args.props).read_text())
    if args.odds_file:
        events = json.loads(Path(args.odds_file).read_text())
    elif not os.environ.get("ODDS_API_KEY"):
        parser.error("set ODDS_API_KEY or pass --odds-file")
    else:
        events = fetch(props)
    result = build(props, events)
    Path(args.out).write_text(json.dumps(result))
    print(f"{len(result['plays'])} plays from {result['games_priced']} of {result['games_open']} games with FanDuel lines")
    return 0


if __name__ == "__main__":
    sys.exit(main())

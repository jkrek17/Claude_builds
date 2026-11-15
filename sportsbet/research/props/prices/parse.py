"""Turn the raw price snapshots into one row per book, market, player and line.

Usage: python research/props/prices/parse.py ODDS_DIR  ->  ODDS_DIR/lines_{minutes}.csv.gz
"""
from __future__ import annotations

import glob
import gzip
import json
import re
import sys
from pathlib import Path

import pandas as pd

PROP = {"player_pass_yds": "pass_yds", "player_pass_tds": "pass_tds", "player_rush_yds": "rush_yds",
        "player_reception_yds": "rec_yds", "player_receptions": "receptions", "player_anytime_td": "any_td"}


def norm(name: str) -> str:
    """Lower-case name without punctuation or generational suffixes, for matching across sources."""
    s = re.sub(r"[^a-z ]", "", str(name).lower().replace("-", " "))
    return " ".join(w for w in s.split() if w not in {"jr", "sr", "ii", "iii", "iv", "v"})


def parse(path: str) -> list[dict]:
    with gzip.open(path, "rt") as f:
        d = json.load(f)
    rows = []
    for book in (d.get("data") or {}).get("bookmakers", []):
        for m in book["markets"]:
            prop = PROP.get(m["key"])
            if prop is None:
                continue
            sides: dict[tuple, dict] = {}
            for o in m["outcomes"]:
                sides.setdefault((o.get("description"), o.get("point")), {})[o["name"]] = o["price"]
            for (player, point), s in sides.items():
                rows.append({"game_id": d["game_id"], "snapshot": d.get("timestamp"), "kickoff": d.get("kickoff"),
                             "book": book["key"], "prop": prop, "player": player, "name_key": norm(player),
                             "line": 0.5 if prop == "any_td" else point,
                             "over_price": s.get("Over", s.get("Yes")), "under_price": s.get("Under", s.get("No")),
                             "updated": m.get("last_update")})
    return rows


if __name__ == "__main__":
    root = Path(sys.argv[1])
    minutes = sys.argv[2] if len(sys.argv) > 2 else "30"
    rows = [r for f in sorted(glob.glob(str(root / "raw" / f"*_{minutes}.json.gz"))) for r in parse(f)]
    df = pd.DataFrame(rows)
    df.to_csv(root / f"lines_{minutes}.csv.gz", index=False)
    print(len(df), "lines from", df["game_id"].nunique(), "games")
    print(df.groupby(["prop", "book"]).size().unstack(fill_value=0).to_string())

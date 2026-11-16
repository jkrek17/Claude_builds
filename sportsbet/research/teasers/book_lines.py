"""Classic teaser legs on each book's own spread: python3 research/teasers/book_lines.py GAMES_CSV LINES_CSV

LINES_CSV is lines_30.csv.gz from research/system/pull_game_lines.py: FanDuel and Fanatics spreads 30
minutes before each kickoff window, 2025 and 2026. It is paid data and is not in the repo. This is a
check of the closing-line backtest, not a new rule: two seasons are far too few legs to choose anything.
"""
from __future__ import annotations

import sys

import numpy as np
import pandas as pd

from nflmodel import teasers as tz

ABBR = {"Arizona Cardinals": "ARI", "Atlanta Falcons": "ATL", "Baltimore Ravens": "BAL", "Buffalo Bills": "BUF",
        "Carolina Panthers": "CAR", "Chicago Bears": "CHI", "Cincinnati Bengals": "CIN", "Cleveland Browns": "CLE",
        "Dallas Cowboys": "DAL", "Denver Broncos": "DEN", "Detroit Lions": "DET", "Green Bay Packers": "GB",
        "Houston Texans": "HOU", "Indianapolis Colts": "IND", "Jacksonville Jaguars": "JAX", "Kansas City Chiefs": "KC",
        "Las Vegas Raiders": "LV", "Los Angeles Chargers": "LAC", "Los Angeles Rams": "LA", "Miami Dolphins": "MIA",
        "Minnesota Vikings": "MIN", "New England Patriots": "NE", "New Orleans Saints": "NO", "New York Giants": "NYG",
        "New York Jets": "NYJ", "Philadelphia Eagles": "PHI", "Pittsburgh Steelers": "PIT", "San Francisco 49ers": "SF",
        "Seattle Seahawks": "SEA", "Tampa Bay Buccaneers": "TB", "Tennessee Titans": "TEN", "Washington Commanders": "WAS"}


def rec(res):
    res = np.asarray(res, dtype=float)
    w, l, p = int((res > 0).sum()), int((res < 0).sum()), int((res == 0).sum())
    if w + l == 0:
        return "no legs"
    r = w / (w + l)
    return f"{w}-{l}" + (f"-{p}" if p else "") + f"  {r:.1%} (±{np.sqrt(r * (1 - r) / (w + l)):.1%})"


def load(games_csv: str, lines_csv: str) -> pd.DataFrame:
    """One row per team per game: closing line, each book's line and price, and the team's margin."""
    lines = pd.read_csv(lines_csv)
    sp = lines[lines.market == "spreads"].copy()
    sp["team"] = sp.name.map(ABBR)
    sp["home_abbr"], sp["away_abbr"] = sp.home.map(ABBR), sp.away.map(ABBR)
    kick = pd.to_datetime(sp.kickoff, utc=True)
    sp["season"] = np.where(kick.dt.month >= 3, kick.dt.year, kick.dt.year - 1)
    wide = sp.pivot_table(index=["season", "home_abbr", "away_abbr", "kickoff", "team"], columns="book",
                          values=["point", "price"], aggfunc="first")
    wide.columns = [f"{b}_{'line' if v == 'point' else 'price'}" for v, b in wide.columns]
    wide = wide.reset_index()
    games = pd.read_csv(games_csv)
    games = games[games.result.notna() & games.season.isin(wide.season.unique())]
    rows = tz.team_rows(games)
    key = games[["game_id", "home_team", "away_team", "gameday"]]
    rows = rows.merge(key, on=["game_id", "gameday"])
    wide["day"] = pd.to_datetime(wide.kickoff, utc=True).dt.tz_convert("America/New_York").dt.strftime("%Y-%m-%d")
    out = rows.merge(wide, left_on=["season", "home_team", "away_team", "team"],
                     right_on=["season", "home_abbr", "away_abbr", "team"])
    # a pair of teams can meet twice in a season (and again in the playoffs): keep the matching date
    return out[out.gameday == out.day].reset_index(drop=True)


def main(games_csv: str, lines_csv: str) -> None:
    d = load(games_csv, lines_csv)
    print(f"Team-games with a closing line and both books: {len(d)} ({d.game_id.nunique()} games, "
          f"seasons {int(d.season.min())}-{int(d.season.max())})")
    for b in ("fanduel", "fanatics"):
        diff = d[f"{b}_line"] - d.line
        print(f"  {b}: same as the nflverse close {np.mean(diff == 0):.1%}, within half a point {np.mean(diff.abs() <= 0.5):.1%}, "
              f"mean absolute difference {diff.abs().mean():.2f}")
    print(f"  the two books differ in {np.mean(d.fanduel_line != d.fanatics_line):.1%} of team-games")

    print("\nCLASSIC LEGS BY WHOSE LINE IS USED (6 points; leg must qualify on that line)")
    close = d[d.kind != ""]
    print(f"  nflverse closing line   {rec(close.res)}")
    for b in ("fanduel", "fanatics"):
        q = d[tz.kind(d[f"{b}_line"]) != ""]
        print(f"  {b:<22}  {rec(tz.leg_result(q.margin, q[f'{b}_line']))}")
    d["best_line"] = d[["fanduel_line", "fanatics_line"]].max(axis=1)   # the more generous number for the team
    q = d[tz.kind(d.best_line) != ""]
    print(f"  better of the two       {rec(tz.leg_result(q.margin, q.best_line))}")
    either = d[(tz.kind(d.fanduel_line) != "") | (tz.kind(d.fanatics_line) != "")]
    print(f"  legs that qualify at either book: {len(either)}; at both: "
          f"{int(((tz.kind(d.fanduel_line) != '') & (tz.kind(d.fanatics_line) != '')).sum())}; on the close: {len(close)}")

    print("\nSAME GAMES, DIFFERENT LINES (legs that qualify on the closing line)")
    for b in ("fanduel", "fanatics"):
        r = tz.leg_result(close.margin, close[f"{b}_line"])
        flipped = int((np.sign(r) != np.sign(close.res)).sum())
        print(f"  scored on {b}'s number: {rec(r)}   ({flipped} legs change result)")

    print("\nDOES THE BOOK SHADE THESE LEGS? (break-even chance of the plain spread bet at the posted price; -110 is 52.4%)")
    for b in ("fanduel", "fanatics"):
        k = tz.kind(d[f"{b}_line"])
        implied = 1 / (1 + d[f"{b}_price"].map(tz.payout))
        for name in ("dog", "fav", ""):
            print(f"  {b:<9} {name or 'other':<6} n={int((k == name).sum()):4d}  average {implied[k == name].mean():.1%}")
    half = (d.fanduel_line * 2 % 2 == 1), (d.fanatics_line * 2 % 2 == 1)
    print(f"  lines on a half point: fanduel {half[0].mean():.1%}, fanatics {half[1].mean():.1%}, nflverse close {(d.line * 2 % 2 == 1).mean():.1%}")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

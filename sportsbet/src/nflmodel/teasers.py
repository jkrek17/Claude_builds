"""Two-team, 6-point NFL teasers: which legs qualify, what price they need, and this week's list.

A teaser moves both spreads 6 points the bettor's way and both legs must win. The classic rule keeps
only legs whose move crosses 3 and 7: underdogs of +1.5 to +2.5 and favourites of -7.5 to -8.5. The
rules, the backtest and the honest result are in research/teasers.
"""
from __future__ import annotations

import argparse
import datetime as dt
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

POINTS = 6.0
DOG_RANGE = (1.5, 2.5)     # team's own spread: an underdog getting 1.5 to 2.5
FAV_RANGE = (-8.5, -7.5)   # a favourite laying 7.5 to 8.5
GATE_MARGIN = 0.01         # the price must need each leg to win this much less than the past record
MAX_TEASERS = 2
STAKE = 5.0
MODEL_FIRST = 2010         # the game model's predictions are honest from this season


def payout(price: float) -> float:
    """Profit per 1 staked at an American price."""
    return price / 100 if price > 0 else 100 / -price


def break_even_leg(price: float, legs: int = 2) -> float:
    """Win rate each leg needs for a teaser of independent, equal legs to break even."""
    return (1 / (1 + payout(price))) ** (1 / legs)


def price_needed(leg_rate: float, legs: int = 2) -> float:
    """The American price at which legs winning at `leg_rate` break even."""
    p = leg_rate ** legs
    return -100 * p / (1 - p) if p >= 0.5 else 100 * (1 - p) / p


def kind(line) -> np.ndarray:
    """'dog', 'fav' or '' for each team spread (negative = favourite)."""
    line = np.asarray(line, dtype=float)
    dog = (line >= DOG_RANGE[0] - 1e-9) & (line <= DOG_RANGE[1] + 1e-9)
    fav = (line >= FAV_RANGE[0] - 1e-9) & (line <= FAV_RANGE[1] + 1e-9)
    return np.where(dog, "dog", np.where(fav, "fav", ""))


def leg_result(margin, line, points: float = POINTS) -> np.ndarray:
    """+1 win, 0 push, -1 loss for a team that won by `margin`, teased from `line` by `points`."""
    return np.sign(np.asarray(margin, dtype=float) + np.asarray(line, dtype=float) + points)


def team_rows(games: pd.DataFrame) -> pd.DataFrame:
    """One row per team per game with a spread. `line` is the team's own spread, `margin` its winning
    margin (missing until played), `gap` how much more the model likes the team than the line does
    (missing without a pred_margin column). spread_line in games is positive when the home team is favoured."""
    g = games[games["spread_line"].notna()]
    has_model = "pred_margin" in g.columns
    out = []
    for side, other, s in (("home", "away", 1), ("away", "home", -1)):
        out.append(pd.DataFrame({
            "season": g["season"], "week": g["week"], "game_id": g["game_id"], "gameday": g["gameday"],
            "team": g[f"{side}_team"], "opp": g[f"{other}_team"], "home": s == 1,
            "line": -s * g["spread_line"], "margin": s * g["result"], "total_line": g["total_line"],
            "div_game": g["div_game"] if "div_game" in g.columns else np.nan,
            "gap": s * (g["pred_margin"] - g["spread_line"]) if has_model else np.nan,
        }))
    rows = pd.concat(out, ignore_index=True)
    rows["kind"] = kind(rows["line"])
    rows["res"] = leg_result(rows["margin"], rows["line"])
    return rows


def qualifying(rows: pd.DataFrame) -> pd.DataFrame:
    return rows[rows["kind"] != ""]


def past_rate(rows: pd.DataFrame, season: int) -> tuple[float, int]:
    """Win rate of the classic legs in every season before `season` (pushes left out), and the count."""
    q = qualifying(rows)
    q = q[(q["season"] < season) & q["margin"].notna() & (q["res"] != 0)]
    return (float((q["res"] > 0).mean()) if len(q) else float("nan")), len(q)


def gate(price: float, rate: float) -> bool:
    """Bet only when the price needs each leg to win at least GATE_MARGIN less than the past record."""
    return break_even_leg(price) <= rate - GATE_MARGIN + 1e-12


def pair_up(legs: pd.DataFrame, max_teasers: int = MAX_TEASERS) -> list[tuple[int, int]]:
    """The live rule: best leg per game, ranked by gap (then by line), paired 1st-2nd, 3rd-4th.

    Returns index pairs into `legs`."""
    ranked = legs.assign(_gap=legs["gap"].fillna(0.0)).sort_values(
        ["_gap", "line", "game_id", "team"], ascending=[False, False, True, True])
    ranked = ranked.drop_duplicates("game_id")
    idx = list(ranked.index)
    return [(idx[i], idx[i + 1]) for i in range(0, len(idx) - 1, 2)][:max_teasers]


def settle(res_a: float, res_b: float, price: float) -> float:
    """Profit per 1 staked: both legs win pays, a pushed leg refunds, anything else loses."""
    if res_a == 0 or res_b == 0:
        return 0.0
    return payout(price) if res_a > 0 and res_b > 0 else -1.0


def _fmt_line(x: float) -> str:
    return f"{x:+g}"


def render(legs: pd.DataFrame, pairs: list[tuple[int, int]], season: int, week: int, rate: float, n: int,
           prices: dict[str, float]) -> str:
    out = [f"NFL {season} week {week}: 6-point teaser legs", ""]
    if legs.empty:
        out.append("No game has a spread in the classic ranges (underdog +1.5 to +2.5, favourite -7.5 to -8.5).")
    for _, r in legs.sort_values(["gameday", "game_id"]).iterrows():
        day = dt.date.fromisoformat(str(r["gameday"])).strftime("%a %b %d")
        where = "vs" if r["home"] else "at"
        model = "" if pd.isna(r["gap"]) else f"   model {'likes' if r['gap'] >= 0 else 'dislikes'} this side by {abs(r['gap']):.1f} pts"
        out.append(f"  {r['team']:<3} {_fmt_line(r['line']):>5} -> {_fmt_line(r['line'] + POINTS):>5}  {where} {r['opp']:<3} ({day}){model}")
    out.append("")
    if pairs:
        out.append("Teasers under the pairing rule (best-rated legs first, one leg per game):")
        for a, b in pairs:
            x, y = legs.loc[a], legs.loc[b]
            out.append(f"  {x['team']} {_fmt_line(x['line'] + POINTS)}  +  {y['team']} {_fmt_line(y['line'] + POINTS)}")
    elif not legs.empty:
        out.append("Fewer than two qualifying games, so no teaser this week.")
    out += ["", f"These legs have won {rate:.1%} of the time ({n} legs, 2003 to {season - 1}), "
                f"which breaks even at a price of {price_needed(rate):+.0f}."]
    need = rate - GATE_MARGIN
    out.append(f"Rule: bet only when the price needs {need:.1%} per leg or less, about {price_needed(need):+.0f} or better.")
    for book, price in prices.items():
        b = break_even_leg(price)
        verdict = f"BET (${STAKE:.0f} each)" if gate(price, rate) and pairs else "NO BET"
        out.append(f"  {book} at {price:+.0f}: each leg must win {b:.1%}  ->  {verdict}")
    if not prices:
        out.append("  Give the app's 2-team 6-point price with --fanduel and --fanatics to get a verdict.")
    out += ["", "Check that the line in your app is still inside the range before betting: half a point",
            "is the whole edge. The model's opinion is shown for interest; it did not pick better legs in",
            "testing. Bet only what you can afford to lose. If gambling stops being fun, call 1-800-GAMBLER."]
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    from . import data
    from .card import next_week, predict_all

    parser = argparse.ArgumentParser(prog="nflteasers", description=__doc__)
    parser.add_argument("--data-dir", default=str(data.DEFAULT_DIR), help="where nflverse files are cached")
    parser.add_argument("--season", type=int)
    parser.add_argument("--week", type=int)
    parser.add_argument("--fanduel", type=float, help="FanDuel's price for a 2-team 6-point teaser, e.g. -134")
    parser.add_argument("--fanatics", type=float, help="Fanatics' price for a 2-team 6-point teaser")
    parser.add_argument("--offline", action="store_true", help="use the cached files without downloading")
    parser.add_argument("--no-model", action="store_true", help="skip the game model (lines only, much faster)")
    args = parser.parse_args(argv)

    data_dir = Path(args.data_dir)
    if not args.offline:
        today = dt.date.today()
        data.refresh(data_dir, today.year if today.month >= 9 else today.year - 1)
    games = pd.read_csv(data_dir / "games.csv")
    season, week = next_week(games)
    season, week = args.season or season, args.week or week
    if not args.no_model:
        games = predict_all(games, str(data_dir))
    rows = team_rows(games[games["season"] >= 2003])
    rate, n = past_rate(rows, season)
    this_week = rows[(rows["season"] == season) & (rows["week"] == week)]
    if this_week["margin"].isna().any():  # the week has started: a teaser can only use games still to come
        this_week = this_week[this_week["margin"].isna()]
    legs = qualifying(this_week)
    prices = {k: v for k, v in (("FanDuel", args.fanduel), ("Fanatics", args.fanatics)) if v is not None}
    print(render(legs, pair_up(legs), season, week, rate, n, prices))
    return 0


if __name__ == "__main__":
    sys.exit(main())

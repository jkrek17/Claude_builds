"""Command line entry point: print the model's predictions for an NFL week."""
from __future__ import annotations

import argparse
import datetime as dt
import sys
from pathlib import Path

import pandas as pd

from . import data
from .card import BREAK_EVEN, next_week, predict_all, season_record, week_card

NOTE = ("How to read this: the model was tested on 4,363 games from 2010 to 2025 without ever seeing a\n"
        "betting line. It picked 49.4% against the spread and 51.4% on totals, below the 52.4% needed to\n"
        "break even at -110. The closing spread was the better forecast in 15 of 16 seasons and the closing\n"
        "total in 12 of 16. The market's win chance is also more accurate than the model's, so a gap between\n"
        "them is not an edge. 'Chance' on a spread or total pick allows for the line being the better\n"
        "forecast; values this close to 50% are not statistically different from a coin flip. Treat these\n"
        "as informed predictions, not bets with an edge. Bet only what you can afford to lose. If gambling\n"
        "stops being fun, call 1-800-GAMBLER.")


def _pct(x: float | None) -> str:
    return "  n/a" if x is None else f"{x:5.1%}"


def render(cards: list[dict], season: int, week: int, record: dict) -> str:
    out = [f"NFL {season} week {week}: model predictions", ""]
    for c in cards:
        day = dt.date.fromisoformat(c["gameday"]).strftime("%a %b %d")
        out.append(f"{c['away']} @ {c['home']}  ({day})"
                   + ("   [starting QB not announced; last starter assumed]" if c["qb_assumed"] else ""))
        out.append(f"  Predicted score   {c['away']} {c['pred_away_score']:.1f}, {c['home']} {c['pred_home_score']:.1f}"
                   f"   (margin {c['home']} {c['pred_margin']:+.1f}, total {c['pred_total']:.1f})")
        market = c["market_home_prob"]
        if market is not None and c["ml_pick"] != c["home"]:
            market = 1 - market
        out.append(f"  Moneyline         {c['ml_pick']:<14} model win chance {_pct(c['ml_pick_prob'])}, market {_pct(market)}")
        if c["ats_pick"]:
            out.append(f"  Spread            {c['ats_pick']:<14} model differs from line by {c['ats_gap']:.1f} pts, chance {_pct(c['ats_prob'])}")
        if c["ou_pick"]:
            out.append(f"  Total             {c['ou_pick']:<14} model differs from line by {c['ou_gap']:.1f} pts, chance {_pct(c['ou_prob'])}")
        out.append("")
    playable = [c for c in cards for k in ("ats_prob", "ou_prob") if c[k] is not None and c[k] > BREAK_EVEN]
    out.append(f"Picks whose estimated chance beats the {BREAK_EVEN:.1%} break-even: {len(playable)}")
    if record["games"]:
        (su, su_n), (ats, ats_n), (ou, ou_n) = record["su"], record["ats"], record["ou"]
        out.append(f"Model record in {season} so far: winners {su}-{su_n - su}, "
                   f"against the spread {ats}-{ats_n - ats}, totals {ou}-{ou_n - ou}")
    out += ["", NOTE]
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="nflmodel", description=__doc__)
    parser.add_argument("--data-dir", default=str(data.DEFAULT_DIR), help="where nflverse files are cached")
    parser.add_argument("--season", type=int, help="season to predict (default: next unplayed week)")
    parser.add_argument("--week", type=int, help="week to predict (default: next unplayed week)")
    parser.add_argument("--offline", action="store_true", help="use the cached files without downloading")
    parser.add_argument("--csv", help="also write the week's predictions to this CSV file")
    args = parser.parse_args(argv)

    data_dir = Path(args.data_dir)
    if not args.offline:
        today = dt.date.today()
        data.refresh(data_dir, today.year if today.month >= 9 else today.year - 1)
    games = pd.read_csv(data_dir / "games.csv")
    season, week = next_week(games)
    season, week = args.season or season, args.week or week
    preds = predict_all(games, str(data_dir))
    cards = week_card(preds, season, week)
    if not cards:
        print(f"No games found for {season} week {week}.", file=sys.stderr)
        return 1
    print(render(cards, season, week, season_record(preds, season)))
    if args.csv:
        pd.DataFrame(cards).to_csv(args.csv, index=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())

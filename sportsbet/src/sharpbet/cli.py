"""Command line entry point: scan for plays, settle bets, report the bankroll."""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from importlib import resources
from pathlib import Path

from . import ledger
from .api import OddsApiError, fetch_odds
from .odds_math import decimal_to_american
from .scan import MARKETS, find_plays, parse_time
from .sizing import select_and_size

DEFAULT_SPORTS = ["americanfootball_nfl", "americanfootball_ncaaf", "basketball_nba",
                  "basketball_ncaab", "icehockey_nhl"]
DEFAULT_BOOKS = ["fanduel", "fanatics"]
DISCLAIMER = ("These are prices that beat the sharp market's fair line, not predictions. Positive expected "
              "value still loses often, and a small bankroll swings a lot. Bet only what you can afford to "
              "lose. If gambling stops being fun, call 1-800-GAMBLER.")


def _american(price: float) -> str:
    return f"{decimal_to_american(price):+d}"


def _csv(value: str) -> list[str]:
    return [v.strip() for v in value.split(",") if v.strip()]


def cmd_scan(args: argparse.Namespace) -> int:
    path = Path(args.ledger)
    if args.sample:
        data = json.loads(resources.files("sharpbet").joinpath("sample_odds.json").read_text())
        now, events = parse_time(data["now"]), data["events"]
        print("SAMPLE DATA: made-up odds to show how the tool works. Do not bet these.\n")
    else:
        key = os.environ.get("ODDS_API_KEY")
        if not key:
            print("Set ODDS_API_KEY (free key at https://the-odds-api.com), or try: sharpbet scan --sample",
                  file=sys.stderr)
            return 2
        now, events, remaining = datetime.now(timezone.utc), [], None
        for sport in args.sports:
            try:
                got, remaining = fetch_odds(key, sport, [args.sharp_book, *args.books], list(MARKETS))
                events += got
            except OddsApiError as err:
                print(f"warning: {err}", file=sys.stderr)
        if remaining is not None:
            print(f"Odds API requests left this month: {remaining}\n")

    rows = ledger.load(path)
    bankroll = args.bankroll + ledger.settled_pnl(rows)
    staked_today = ledger.staked_on(rows, datetime.now().astimezone().date())
    plays = find_plays(events, now, sharp_book=args.sharp_book, books=set(args.books),
                       min_ev=args.min_ev / 100, max_ev=args.max_ev / 100,
                       max_price=args.max_price, max_age_minutes=args.max_age)
    picks = select_and_size(plays, bankroll, top_n=args.top, kelly_multiplier=args.kelly,
                            max_bet_frac=args.max_bet / 100, daily_cap_frac=args.daily_cap / 100,
                            staked_today=staked_today,
                            skip_events={r["event_id"] for r in ledger.open_rows(rows)})

    print(f"Bankroll ${bankroll:.2f} | staked today ${staked_today:.2f} of "
          f"${bankroll * args.daily_cap / 100:.2f} cap | {len(events)} games scanned, "
          f"{len(plays)} prices with {args.min_ev:g}%+ edge")
    if not picks:
        print("\nNo plays qualify right now. Passing is a position too; try again closer to game time.")
        print(f"\n{DISCLAIMER}")
        return 0
    for i, pick in enumerate(picks, 1):
        p = pick.play
        local = p.commence.astimezone().strftime("%a %b %d %I:%M %p")
        print(f"\n#{i}  {p.selection}  ({p.market})  at {p.book}  {_american(p.price)}  [{p.price:.2f}]")
        print(f"    {p.sport}: {p.matchup}, {local}")
        print(f"    Fair win chance {p.fair_prob:.1%} (fair price {_american(1 / p.fair_prob)}, "
              f"{args.sharp_book} {_american(p.sharp_price)})")
        print(f"    Edge {p.ev:+.1%} | full Kelly {p.kelly:.1%} of bankroll | "
              f"BET ${pick.stake:.2f} to win ${pick.stake * (p.price - 1):.2f}")
    total = sum(pick.stake for pick in picks)
    print(f"\nTotal risk ${total:.2f} ({total / bankroll:.1%} of bankroll). "
          "Check the price is still there before betting; skip it if the line has moved.")
    if args.log and not args.sample:
        for row in ledger.record(path, picks, now):
            print(f"Logged bet #{row['id']} to {path}")
    print(f"\n{DISCLAIMER}")
    return 0


def cmd_settle(args: argparse.Namespace) -> int:
    try:
        row = ledger.settle(Path(args.ledger), args.id, args.result)
    except KeyError as err:
        print(err.args[0], file=sys.stderr)
        return 1
    print(f"Bet #{row['id']} {row['selection']}: {row['status']}, P/L ${float(row['pnl']):+.2f}")
    return 0


def cmd_report(args: argparse.Namespace) -> int:
    rows = ledger.load(Path(args.ledger))
    done = [r for r in rows if r["status"] in ledger.RESULTS]
    pnl, staked = ledger.settled_pnl(rows), sum(float(r["stake"]) for r in done)
    wins, losses = (sum(r["status"] == s for r in done) for s in ("win", "loss"))
    print(f"Bankroll ${args.bankroll + pnl:.2f} (started ${args.bankroll:.2f}, P/L ${pnl:+.2f})")
    print(f"Settled {len(done)} bets: {wins} won, {losses} lost, {len(done) - wins - losses} pushed. "
          f"ROI {pnl / staked:+.1%}" if staked else "No settled bets yet.")
    for r in ledger.open_rows(rows):
        print(f"  open #{r['id']}: {r['selection']} ({r['market']}) at {r['book']} "
              f"{_american(float(r['price']))}, ${r['stake']}, {r['matchup']}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="sharpbet", description=__doc__)
    parser.add_argument("--ledger", default=os.environ.get("SHARPBET_LEDGER", "picks.csv"),
                        help="CSV file of bets placed (default picks.csv)")
    parser.add_argument("--bankroll", type=float, default=100.0, help="starting bankroll in dollars")
    sub = parser.add_subparsers(dest="command", required=True)

    scan = sub.add_parser("scan", help="find, rank and size the top plays")
    scan.add_argument("--sample", action="store_true", help="use bundled made-up odds, no API key needed")
    scan.add_argument("--log", action="store_true", help="record the picks in the ledger as placed bets")
    scan.add_argument("--sports", type=_csv, default=DEFAULT_SPORTS, help="comma-separated Odds API sport keys")
    scan.add_argument("--books", type=_csv, default=DEFAULT_BOOKS, help="comma-separated books you can bet at")
    scan.add_argument("--sharp-book", default="pinnacle", help="book whose line is treated as sharp")
    scan.add_argument("--top", type=int, default=3, help="number of picks")
    scan.add_argument("--min-ev", type=float, default=2.0, help="minimum edge, percent")
    scan.add_argument("--max-ev", type=float, default=15.0, help="edges above this are treated as bad data, percent")
    scan.add_argument("--max-price", type=float, default=6.0, help="longest decimal odds to consider")
    scan.add_argument("--max-age", type=float, default=20.0, help="ignore quotes older than this many minutes")
    scan.add_argument("--kelly", type=float, default=0.25, help="Kelly multiplier (0.25 = quarter Kelly)")
    scan.add_argument("--max-bet", type=float, default=5.0, help="cap per bet, percent of bankroll")
    scan.add_argument("--daily-cap", type=float, default=15.0, help="cap on total staked per day, percent of bankroll")
    scan.set_defaults(func=cmd_scan)

    settle = sub.add_parser("settle", help="record a bet's result")
    settle.add_argument("id", type=int)
    settle.add_argument("result", choices=ledger.RESULTS)
    settle.set_defaults(func=cmd_settle)

    report = sub.add_parser("report", help="show bankroll, record and open bets")
    report.set_defaults(func=cmd_report)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

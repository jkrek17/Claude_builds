"""Command line entry point for cfbmodel."""
from __future__ import annotations

import argparse
import datetime as dt
import sys
from pathlib import Path

from . import cfbd

# Endpoints cached per season by `cfbmodel fetch`. Later modules must call cfbd.get with these
# exact params so they hit the same cache files. 8 calls a season: 2013-2026 is 112 calls.
FETCH_PLAN: list[tuple[str, dict]] = [
    ("games", {"seasonType": "both"}),
    ("lines", {}),
    ("teams/fbs", {}),
    ("talent", {}),
    ("player/returning", {}),
    ("recruiting/teams", {}),
    ("player/portal", {}),
    ("coaches", {}),
]


def parse_seasons(text: str) -> list[int]:
    """'2013-2026' -> [2013, ..., 2026]; '2023' -> [2023]."""
    lo, _, hi = text.partition("-")
    first, last = int(lo), int(hi or lo)
    if last < first:
        raise ValueError(f"season range {text!r} runs backwards")
    return list(range(first, last + 1))


def current_season(today: dt.date | None = None) -> int:
    """The season still being played: January bowls belong to the previous year's season."""
    today = today or dt.date.today()
    return today.year if today.month >= 7 else today.year - 1


def _seasons_arg(text: str) -> list[int]:
    try:
        return parse_seasons(text)
    except ValueError as e:
        raise argparse.ArgumentTypeError(str(e)) from None


def cmd_fetch(args: argparse.Namespace) -> int:
    live_season = current_season()
    before = cfbd.calls_made()
    for season in args.seasons:
        for endpoint, params in FETCH_PLAN:
            cfbd.get(endpoint, {**params, "year": season}, args.data_dir, live=season >= live_season)
    made = cfbd.calls_made() - before
    print(f"Cached CFBD data for {args.seasons[0]}-{args.seasons[-1]} in {args.data_dir / 'cfbd'} "
          f"({made} API call{'s' if made != 1 else ''} made)")
    return 0


def cmd_predict(args: argparse.Namespace) -> int:
    print("cfbmodel predict: not built yet")
    return 1


def cmd_site(args: argparse.Namespace) -> int:
    print("cfbmodel site: not built yet")
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cfbmodel", description="College football efficiency model.")
    sub = parser.add_subparsers(dest="command", required=True)

    fetch = sub.add_parser("fetch", help="download and cache CollegeFootballData tables (needs CFBD_API_KEY)")
    fetch.add_argument("--seasons", type=_seasons_arg, default=parse_seasons(f"2013-{current_season()}"),
                       help="season or range, e.g. 2013-2026 (default: 2013 to the current season)")
    fetch.add_argument("--data-dir", type=Path, default=cfbd.DEFAULT_DATA_DIR,
                       help=f"cache folder (default: {cfbd.DEFAULT_DATA_DIR})")
    fetch.set_defaults(func=cmd_fetch)

    sub.add_parser("predict", help="weekly card (not built yet)").set_defaults(func=cmd_predict)
    sub.add_parser("site", help="build the website data (not built yet)").set_defaults(func=cmd_site)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except cfbd.CFBDError as e:
        print(f"cfbmodel: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

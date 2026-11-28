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


def _season_week(args: argparse.Namespace) -> tuple[int, int]:
    from . import schedule

    season = args.season or current_season()
    week = args.week if args.week is not None else schedule.current_week(season, args.data_dir)
    return season, week


def _fmt(x, spec: str = "+.1f") -> str:
    return "-" if x is None else format(x, spec)


def print_card(games: list[dict], season: int, week: int, status: dict, out=None) -> None:
    """A readable table of one week's card."""
    out = out or sys.stdout
    head = ("Research only: passed the backtest, not proven live" if status.get("passed") is True
            else "Research only: did not pass the backtest")
    print(head, file=out)
    print(f"College football {season}, week {week}: {len(games)} games "
          "(spread from the home side, negative = home favored)", file=out)
    print(f"{'Kickoff (UTC)':<17} {'Away':<20} {'Home':<23} {'Line':>6} {'Model':>6} {'Cover':>6} "
          f"{'Pick':<5} {'O/U':>6} {'Model':>6} {'Over':>6} {'Pick':<5} {'Bet':<7} Result", file=out)
    mark = {True: "hit", False: "miss", None: "-"}
    for g in games:
        start = (g.get("start") or "")[:16].replace("T", " ")
        bet = ",".join(m for m, k in (("ATS", "bet_spread"), ("O/U", "bet_total")) if g.get(k))
        model_line = None if g["model_margin"] is None else -g["model_margin"]
        cover = "-" if g["home_cover_prob"] is None else f"{100 * g['home_cover_prob']:.0f}%"
        over = "-" if g["over_prob"] is None else f"{100 * g['over_prob']:.0f}%"
        result = ""
        if g.get("final"):
            result = (f"{g['away_score']}-{g['home_score']} ATS {mark[g['ats_hit']]}, "
                      f"O/U {mark[g['ou_hit']]}")
        home = ("vs " if g["neutral"] else "@ ") + str(g["home"])[:20]
        print(f"{start:<17} {str(g['away'])[:20]:<20} {home:<23} "
              f"{_fmt(g['spread_line']):>6} {_fmt(model_line):>6} {cover:>6} {g['pick_spread'] or '-':<5} "
              f"{_fmt(g['total_line'], '.1f'):>6} {_fmt(g['model_total'], '.1f'):>6} {over:>6} "
              f"{g['pick_total'] or '-':<5} {bet:<7} {result}", file=out)
    providers = sorted({g["line_provider"] for g in games if g.get("line_provider")})
    print(("Lines: " + ", ".join(providers) + ". " if providers else "No lines yet. ")
          + "Model is its margin written as a home line; Cover and Over are the home cover and over chances.",
          file=out)


def cmd_predict(args: argparse.Namespace) -> int:
    import json

    from . import card, site

    season, week = _season_week(args)
    result = site.load_result(args.result)
    games = card.week_card(season, week, args.data_dir, rulebook=site.rulebook(result))
    status = site.status(result)
    if args.json is not None:
        data = site.clean({"season": season, "week": week, "status": status, "games": games})
        text = json.dumps(data, allow_nan=False, indent=1)
        if args.json == "-":
            print(text)
        else:
            Path(args.json).write_text(text)
            print(f"Wrote {len(games)} games to {args.json}")
        return 0
    print_card(games, season, week, status)
    return 0


def cmd_site(args: argparse.Namespace) -> int:
    from . import site

    season = args.season or current_season()
    data = site.build(season, args.week, args.data_dir, result_path=args.result)
    site.write(data, args.json)
    now = [g for g in data["games"] if g["week"] == data["week"]]
    lines = sum(1 for g in now if g["spread_line"] is not None)
    print(f"Wrote {args.json}: {season} week {data['week']}, {len(now)} games ({lines} with a line); "
          f"{len(data['games'])} games over weeks {data['weeks'][0]}-{data['weeks'][-1]}; "
          f"{len(data['rankings'])} teams ranked")
    return 0


def _card_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--season", type=int, default=None, help="season (default: the current one)")
    p.add_argument("--week", type=int, default=None, help="regular-season week (default: ESPN's current week)")
    p.add_argument("--data-dir", type=Path, default=cfbd.DEFAULT_DATA_DIR,
                   help=f"cache folder (default: {cfbd.DEFAULT_DATA_DIR})")
    p.add_argument("--result", type=Path, default=None,
                   help="backtest RESULT.json (default: research/cfb/RESULT.json if present)")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cfbmodel", description="College football efficiency model.")
    sub = parser.add_subparsers(dest="command", required=True)

    fetch = sub.add_parser("fetch", help="download and cache CollegeFootballData tables (needs CFBD_API_KEY)")
    fetch.add_argument("--seasons", type=_seasons_arg, default=parse_seasons(f"2013-{current_season()}"),
                       help="season or range, e.g. 2013-2026 (default: 2013 to the current season)")
    fetch.add_argument("--data-dir", type=Path, default=cfbd.DEFAULT_DATA_DIR,
                       help=f"cache folder (default: {cfbd.DEFAULT_DATA_DIR})")
    fetch.set_defaults(func=cmd_fetch)

    predict = sub.add_parser("predict", help="print a week's card: model margin and total, lines, chances, picks")
    _card_args(predict)
    predict.add_argument("--json", nargs="?", const="-", default=None, metavar="PATH",
                         help="write the card as JSON to PATH (or stdout) instead of a table")
    predict.set_defaults(func=cmd_predict)

    site = sub.add_parser("site", help="write the college page's data (cfb.json); no CFBD key needed")
    site.add_argument("--json", type=Path, required=True, metavar="PATH", help="where to write cfb.json")
    _card_args(site)
    site.set_defaults(func=cmd_site)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except cfbd.CFBDError as e:
        print(f"cfbmodel: {e}", file=sys.stderr)
        return 1
    except OSError as e:  # ESPN or play-by-play download failed (urllib errors are OSErrors)
        print(f"cfbmodel: download failed: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

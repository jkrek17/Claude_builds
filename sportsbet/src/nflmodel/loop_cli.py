"""Command line entry point for the weekly loop: freeze picks, grade them, write the report."""
from __future__ import annotations

import argparse
import datetime as dt
import os
import sys
from pathlib import Path

import pandas as pd

from . import data, loop, weather
from .card import predict_all


def _now(text: str | None) -> dt.datetime:
    if not text:
        return dt.datetime.now(dt.timezone.utc)
    when = dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
    return when if when.tzinfo else when.replace(tzinfo=dt.timezone.utc)


def _predictions(args, now: dt.datetime) -> tuple[pd.DataFrame, pd.DataFrame, str]:
    data_dir = Path(args.data_dir)
    if not args.offline:
        data.refresh(data_dir, now.year if now.month >= 9 else now.year - 1)
    games = pd.read_csv(data_dir / "games.csv")
    winds = {} if args.offline else weather.forecast_winds(games)
    return games, predict_all(games, str(data_dir), wind_forecast=winds), loop.file_sha256(data_dir / "games.csv")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="nflmodel-loop", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name, text in (("run", "check, freeze, grade and report; safe to repeat"),
                       ("freeze", "only freeze picks for games that have not kicked off"),
                       ("grade", "only rebuild the grades and the report"),
                       ("status", "print what the last run did")):
        p = sub.add_parser(name, help=text)
        p.add_argument("--state-dir", required=True, help="directory holding picks.csv and the reports")
        if name == "status":
            p.add_argument("--oneline", action="store_true", help="one line, for a commit message")
            continue
        p.add_argument("--data-dir", default=str(data.DEFAULT_DIR), help="where nflverse files are cached")
        p.add_argument("--offline", action="store_true", help="use cached files; no downloads or forecasts")
        p.add_argument("--now", help="pretend it is this UTC time (YYYY-MM-DDTHH:MM), for testing")
        p.add_argument("--model-commit", default=os.environ.get("GITHUB_SHA", ""), help="code version to record")
        p.add_argument("--run-url", default="", help="link to the run that produced this state")
    args = parser.parse_args(argv)
    state_dir = Path(args.state_dir)

    if args.command == "status":
        status = loop.read_json(state_dir, "status.json")
        if status is None:
            print("weekly loop: no runs yet")
            return 0
        if args.oneline:
            print(f"sportsbet loop: {status['summary']}")
        else:
            print(f"Last run {status['updated_at']}: {status['summary']}")
            for line in status["red"]:
                print(f"  FAILED CHECK: {line}")
            for line in status["amber"]:
                print(f"  note: {line}")
        return 0

    now = _now(args.now)
    games, preds, games_sha = _predictions(args, now)
    version = loop.model_version(args.model_commit)
    if args.command == "freeze":
        rows = loop.freeze(state_dir, preds, now, version, games_sha)
        print(f"froze {len(rows)} pick(s)")
        return 0
    if args.command == "grade":
        grades = loop.grade(state_dir, preds)
        report = loop.build_report(state_dir, preds, grades, now, loop.check_after(grades))
        loop.write_report(state_dir, report)
        print(f"graded {len(grades)} pick(s)")
        return 0
    try:
        status = loop.run(state_dir, preds, now, version, games_sha, args.run_url, games=games, data_dir=args.data_dir)
    except loop.LedgerError as err:
        print(f"error: {err}", file=sys.stderr)
        return 2
    print(status["summary"])
    for line in status["red"]:
        print(f"FAILED CHECK: {line}")
    if os.environ.get("GITHUB_OUTPUT"):   # tells the workflow whether to open a pull request
        with open(os.environ["GITHUB_OUTPUT"], "a") as fh:
            fh.write(f"promote={'true' if status['promote'] else 'false'}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""The college page's data: ``build`` returns the cfb.json dict (docs/superpowers/plans/cfb-site-json.md).

``weeks`` holds every regular-season week from week 1 through the current one, and ``games`` every
game of those weeks, so past weeks show their finals and the model's record.

The backtest verdict comes from ``research/cfb/RESULT.json`` when it exists (written by the scoring
run). Its shape::

    {"passed": true | false,
     "note": "one line for the page (optional)",
     "rulebook": {"spread": {"edge_min": 0.05, "segment": "all"}, "total": {...}},
     "backtest": {...the contract's backtest block...}}

Without the file the page says research only (``status.passed`` null) and no game is flagged as a
bet. No CollegeFootballData key is needed: games, lines and scores come from ESPN and the
play-by-play, and the prior is ``predict.carryover_prior``.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import pandas as pd

from . import card, schedule

RESULT_PATH = Path(__file__).resolve().parents[2] / "research" / "cfb" / "RESULT.json"
RESEARCH_NOTE = "Research only: the model has not passed its backtest."
FAILED_NOTE = "Research only: the college model did not pass its backtest, so no pick here is a proven edge."
PASSED_NOTE = "The college model passed its backtest on held-out seasons. It is still unproven live."
TUNE_SEASONS = "2014-2019, 2021-2022"
SCORED_SEASONS = "2023-2025"


def _market_block() -> dict:
    return {"n": 0, "win_rate": None, "roi": None, "clv_mean": None, "clv_t": None, "by_season": []}


def empty_backtest() -> dict:
    return {"tune_seasons": TUNE_SEASONS, "scored_seasons": SCORED_SEASONS, "passed": None,
            "markets": {"spread": _market_block(), "total": _market_block()}}


def load_result(path: Path | None = None) -> dict | None:
    """RESULT.json as a dict, or None when it does not exist."""
    path = Path(path) if path is not None else RESULT_PATH
    if not path.exists():
        return None
    return json.loads(path.read_text())


def status(result: dict | None) -> dict:
    passed = None if result is None else result.get("passed")
    passed = passed if passed in (True, False) else None
    default = PASSED_NOTE if passed is True else FAILED_NOTE if passed is False else RESEARCH_NOTE
    note = (result or {}).get("note") or default
    return {"passed": passed, "note": note}


def backtest_block(result: dict | None) -> dict:
    out = empty_backtest()
    if result is None:
        return out
    b = result.get("backtest") or {}
    out.update({k: v for k, v in b.items() if k != "markets"})
    for m in ("spread", "total"):
        out["markets"][m].update((b.get("markets") or {}).get(m) or {})
    out["passed"] = status(result)["passed"]
    return out


def rulebook(result: dict | None) -> dict | None:
    """The frozen betting configuration with the verdict, or None (no bets) without a result."""
    if result is None:
        return None
    return {**(result.get("rulebook") or {}), "passed": status(result)["passed"]}


def clean(obj):
    """NaN and infinities to None, numpy scalars to Python, recursively."""
    if isinstance(obj, dict):
        return {k: clean(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [clean(v) for v in obj]
    if hasattr(obj, "item") and not isinstance(obj, (str, bytes)):
        obj = obj.item()
    if isinstance(obj, float) and not math.isfinite(obj):
        return None
    return obj


def build(season: int, week: int | None, data_dir: Path, params: dict | None = None, prior_fn=None,
          result_path: Path | None = None, ctx: dict | None = None) -> dict:
    """The cfb.json dict for ``season`` with ``week`` as the current week (default ESPN's)."""
    info = schedule.season_info(season, data_dir)
    week = int(week if week is not None else info["week"])
    weeks = [w for w in info["weeks"] if w <= week] or [week]
    if week not in weeks:
        weeks.append(week)
    result = load_result(result_path)
    rules = rulebook(result)
    ctx = ctx or card.context(season, data_dir, params, prior_fn)
    games = []
    for w in sorted(weeks):
        games.extend(card.week_card(season, w, data_dir, rulebook=rules, ctx=ctx))
    played = {w for w in weeks if any(g["week"] == w for g in games)}
    weeks = sorted(w for w in weeks if w in played or w == week)
    data = {
        "generated_at": pd.Timestamp.now(tz="UTC").strftime("%Y-%m-%dT%H:%M:%SZ"),
        "season": int(season), "week": week, "weeks": weeks,
        "status": status(result),
        "games": games,
        "rankings": card.rankings(season, data_dir, ctx=ctx),
        "backtest": backtest_block(result),
    }
    return clean(data)


def write(data: dict, path: Path) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_name(path.name + ".part")
    part.write_text(json.dumps(data, allow_nan=False, separators=(",", ":")))
    part.replace(path)

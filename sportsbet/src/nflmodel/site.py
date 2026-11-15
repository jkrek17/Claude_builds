"""Build the JSON file the web page in site/ reads: every week's card, the season record and the backtest."""
from __future__ import annotations

import datetime as dt
import json
import math
from pathlib import Path

import pandas as pd

from .card import BREAK_EVEN, WINDY_MPH, season_record, week_card

BACKTEST_FIRST = 2010  # first season scored in the walk-forward backtest


def _clean(value):
    """Make a value JSON-safe: NaN becomes null and numpy scalars become plain Python."""
    if isinstance(value, dict):
        return {k: _clean(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_clean(v) for v in value]
    if hasattr(value, "item"):
        value = value.item()
    if isinstance(value, float):
        return None if math.isnan(value) else round(value, 4)
    return value


def _hit(pred: float, line, actual) -> bool | None:
    """Whether a pick against a line won. None when there is no line, no result, or a push."""
    if pd.isna(line) or pd.isna(actual) or actual == line or pred == line:
        return None
    return bool((pred > line) == (actual > line))


def _with_result(card: dict, row: pd.Series) -> dict:
    """Add kickoff details and, for a finished game, the final score and how each pick did."""
    card = dict(card, weekday=row.get("weekday"), gametime=row.get("gametime"),
                final=False, home_score=None, away_score=None, ml_hit=None, ats_hit=None, ou_hit=None)
    if pd.isna(row.get("result")):
        return card
    card.update(final=True, home_score=int(row["home_score"]), away_score=int(row["away_score"]),
                ml_hit=_hit(row["pred_margin"], 0.0, row["result"]),
                ats_hit=_hit(row["pred_margin"], row.get("spread_line"), row["result"]),
                ou_hit=_hit(row["pred_total"], row.get("total_line"), row["total"]))
    return card


def _mae(pred: pd.Series, actual: pd.Series) -> float:
    return float((actual - pred).abs().mean())


def backtest(preds: pd.DataFrame, last_season: int, first_season: int = BACKTEST_FIRST) -> dict:
    """Walk-forward results for every finished season from first_season to last_season."""
    done = preds[(preds["season"] >= first_season) & (preds["season"] <= last_season) & preds["result"].notna()]
    seasons = []
    for season in sorted(done["season"].unique()):
        rec = season_record(done, int(season))
        seasons.append({"season": int(season), "su": rec["su"], "ats": rec["ats"], "ou": rec["ou"]})
    decided = done[done["result"] != 0]
    lined = decided[decided["spread_line"] != 0]

    def total(key: str) -> list[int]:
        return [sum(s[key][0] for s in seasons), sum(s[key][1] for s in seasons)]

    return {
        "first": first_season, "last": last_season, "games": len(done),
        "margin_mae": {"model": _mae(done["pred_margin"], done["result"]),
                       "line": _mae(done["spread_line"], done["result"])},
        "total_mae": {"model": _mae(done["pred_total"], done["total"]),
                      "line": _mae(done["total_line"], done["total"])},
        "su_model": float(((decided["pred_margin"] > 0) == (decided["result"] > 0)).mean()),
        "su_line": float(((lined["spread_line"] > 0) == (lined["result"] > 0)).mean()),
        "su": total("su"), "ats": total("ats"), "ou": total("ou"), "seasons": seasons,
    }


def build(preds: pd.DataFrame, season: int, week: int, generated_at: dt.datetime | None = None,
          backtest_first: int = BACKTEST_FIRST) -> dict:
    """Everything the page shows. `preds` is card.predict_all output; `week` is the week shown first."""
    this = preds[preds["season"] == season]
    rows = this.set_index("game_id")
    weeks, by_week = {}, []
    for wk in sorted(int(w) for w in this["week"].unique()):
        cards = [_with_result(c, rows.loc[c["game_id"]]) for c in week_card(preds, season, wk)]
        if not cards:
            continue
        weeks[str(wk)] = cards
        if any(c["final"] for c in cards):
            rec = season_record(this[this["week"] == wk], season)
            by_week.append({"week": wk, "su": rec["su"], "ats": rec["ats"], "ou": rec["ou"]})
    generated_at = generated_at or dt.datetime.now(dt.timezone.utc)
    return _clean({
        "generated_at": generated_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "season": season, "week": week, "break_even": BREAK_EVEN, "windy_mph": WINDY_MPH,
        "weeks": weeks, "record": season_record(preds, season), "by_week": by_week,
        "backtest": backtest(preds, season - 1, backtest_first),
    })


def write(path: str | Path, preds: pd.DataFrame, season: int, week: int) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(build(preds, season, week), allow_nan=False, separators=(",", ":")))

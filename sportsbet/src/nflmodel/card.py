"""Turn the margin and total models into moneyline, spread and total predictions for a week."""
from __future__ import annotations

import math

import pandas as pd

from . import margin_model, params, qb_form, scoredist, totals_model

# How much of the gap between model and betting line survives when both are used to predict the result.
# From the 2010-2025 backtest, regressing (result - line) on (model - line) gives 0.107 (SE 0.073) for
# margins and 0.094 (SE 0.081) for totals. Neither is statistically different from zero, both were fit
# on the test window, and both were near or below zero in 2018-2025, so the resulting chances are, if
# anything, optimistic.
MARGIN_WEIGHT = params.DEFAULT["card"]["margin_weight"]
TOTAL_WEIGHT = params.DEFAULT["card"]["total_weight"]
LINE_SD_MARGIN = 13.0   # SD of the result around the closing spread
LINE_SD_TOTAL = 13.2    # SD of the total around the closing total
BREAK_EVEN = 110 / 210  # win rate needed at -110
WINDY_MPH = 15.0        # forecast wind at which a game is flagged on the card


def norm_cdf(x: float) -> float:
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def _implied(american: float) -> float:
    return 100 / (american + 100) if american > 0 else -american / (-american + 100)


def market_home_prob(home_ml: float, away_ml: float) -> float | None:
    """Home win probability from the two moneylines with the vig removed."""
    if pd.isna(home_ml) or pd.isna(away_ml):
        return None
    h, a = _implied(home_ml), _implied(away_ml)
    return h / (h + a)


def predict_all(games: pd.DataFrame, data_dir: str, wind_forecast: dict | None = None) -> pd.DataFrame:
    """games plus pred_margin, pred_total and the wind used for each total, for 2003 onward."""
    margins = margin_model.predict_margins(games, margin_model.load_team_stats(data_dir),
                                           qb_form.load_player_stats(data_dir))
    totals = totals_model.predict_totals(games, totals_model.load_team_stats(data_dir),
                                         wind_forecast=wind_forecast)
    out = games.merge(margins[["game_id", "pred_margin", "qb_assumed"]], on="game_id")
    return out.merge(totals[["game_id", "pred_total", "wind_mph", "wind_source"]], on="game_id")


def fit_key_factors(games: pd.DataFrame, before: str | None = None) -> dict:
    """Key-number factors for margins and totals from finished games, measured around the closing line.

    `before` limits the fit to games played before that date (YYYY-MM-DD)."""
    done = games[games["result"].notna()]
    if before is not None:
        done = done[done["gameday"].astype(str) < before]
    lo, hi = scoredist.MARGIN_RANGE
    tlo, thi = scoredist.TOTAL_RANGE
    return {
        "margin": scoredist.key_factors(done["spread_line"], done["result"], LINE_SD_MARGIN, lo, hi, True),
        "total": scoredist.key_factors(done["total_line"], done["total"], LINE_SD_TOTAL, tlo, thi, False),
    }


def _bet_probs(center: float, line: float, over: bool, sd: float, factors, rng) -> tuple[float, float]:
    """(chance the bet wins given it does not push, chance of a push) for the over/home side or its opposite."""
    if factors is None:
        return norm_cdf(abs(center - line) / sd), 0.0
    center += scoredist.balanced_center(line, sd, factors, *rng) - line  # start from a fair line
    above, push, below = scoredist.line_probs(scoredist.keyed_pmf(center, sd, factors, *rng), line, rng[0])
    win = above if over else below
    return win / (above + below), push


def game_card(row: pd.Series, factors: dict | None = None) -> dict:
    """Predictions and picks for one game. spread_line is positive when the home team is favored.

    With `factors` from fit_key_factors, spread and total chances come from a distribution that knows
    about key numbers and separates out the chance of a push; without it, from a plain normal curve."""
    factors = factors or {}
    home, away = row["home_team"], row["away_team"]
    margin, total = float(row["pred_margin"]), float(row["pred_total"])
    home_prob = norm_cdf(margin / margin_model.RESID_SD)
    card = {
        "game_id": row["game_id"], "gameday": row["gameday"], "away": away, "home": home,
        "pred_margin": margin, "pred_total": total,
        "pred_home_score": (total + margin) / 2, "pred_away_score": (total - margin) / 2,
        "home_win_prob": home_prob,
        "market_home_prob": market_home_prob(row.get("home_moneyline"), row.get("away_moneyline")),
        "ml_pick": home if home_prob >= 0.5 else away,
        "ml_pick_prob": max(home_prob, 1 - home_prob),
        "qb_assumed": bool(row.get("qb_assumed", 0)),
        "spread_line": None, "ats_pick": None, "ats_gap": None, "ats_prob": None, "ats_push": None,
        "total_line": None, "ou_pick": None, "ou_gap": None, "ou_prob": None, "ou_push": None,
        "wind_mph": None if pd.isna(row.get("wind_mph")) else float(row["wind_mph"]),
        "wind_source": row.get("wind_source"),
    }
    card["windy"] = bool(card["wind_source"] == "forecast" and card["wind_mph"] >= WINDY_MPH)
    line = row.get("spread_line")
    if not pd.isna(line):
        gap = margin - float(line)
        if gap >= 0:  # model likes the home team more than the line does
            pick = f"{home} {-line:+g}"
        else:
            pick = f"{away} {line:+g}"
        prob, push = _bet_probs(float(line) + MARGIN_WEIGHT * gap, float(line), gap >= 0, LINE_SD_MARGIN,
                                factors.get("margin"), scoredist.MARGIN_RANGE)
        card.update(spread_line=float(line), ats_pick=pick, ats_gap=abs(gap), ats_prob=prob, ats_push=push)
    tline = row.get("total_line")
    if not pd.isna(tline):
        gap = total - float(tline)
        prob, push = _bet_probs(float(tline) + TOTAL_WEIGHT * gap, float(tline), gap >= 0, LINE_SD_TOTAL,
                                factors.get("total"), scoredist.TOTAL_RANGE)
        card.update(total_line=float(tline), ou_pick=f"{'Over' if gap >= 0 else 'Under'} {tline:g}",
                    ou_gap=abs(gap), ou_prob=prob, ou_push=push)
    return card


def week_card(preds: pd.DataFrame, season: int, week: int) -> list[dict]:
    rows = preds[(preds["season"] == season) & (preds["week"] == week)].sort_values(["gameday", "game_id"])
    if rows.empty:
        return []
    factors = fit_key_factors(preds, before=str(rows["gameday"].min()))
    return [game_card(r, factors) for _, r in rows.iterrows()]


def next_week(games: pd.DataFrame) -> tuple[int, int]:
    """Season and week of the earliest game without a final score."""
    todo = games[games["result"].isna()].sort_values(["gameday", "game_id"])
    if todo.empty:
        last = games.sort_values(["gameday", "game_id"]).iloc[-1]
        return int(last["season"]), int(last["week"])
    return int(todo.iloc[0]["season"]), int(todo.iloc[0]["week"])


def season_record(preds: pd.DataFrame, season: int) -> dict:
    """How the model's picks have done in finished games of one season (straight up, ATS, totals)."""
    done = preds[(preds["season"] == season) & preds["result"].notna()]
    su = ats = ou = su_n = ats_n = ou_n = 0
    for _, r in done.iterrows():
        if r["result"] != 0:
            su_n += 1
            su += (r["pred_margin"] > 0) == (r["result"] > 0)
        if not pd.isna(r["spread_line"]) and r["result"] != r["spread_line"] and r["pred_margin"] != r["spread_line"]:
            ats_n += 1
            ats += (r["pred_margin"] > r["spread_line"]) == (r["result"] > r["spread_line"])
        if not pd.isna(r["total_line"]) and r["total"] != r["total_line"] and r["pred_total"] != r["total_line"]:
            ou_n += 1
            ou += (r["pred_total"] > r["total_line"]) == (r["total"] > r["total_line"])
    return {"games": len(done), "su": (int(su), su_n), "ats": (int(ats), ats_n), "ou": (int(ou), ou_n)}

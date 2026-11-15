"""Turn the margin and total models into moneyline, spread and total predictions for a week."""
from __future__ import annotations

import math

import pandas as pd

from . import margin_model, totals_model

# How much of the gap between model and betting line survives when both are used to predict the result.
# From the 2010-2025 backtest: regressing (result - line) on (model - line) gives 0.107 (SE 0.073) for
# margins and about 0.08 (SE 0.09) for totals. Neither is statistically different from zero.
MARGIN_WEIGHT = 0.10
TOTAL_WEIGHT = 0.08
LINE_SD_MARGIN = 13.0   # SD of the result around the closing spread
LINE_SD_TOTAL = 13.2    # SD of the total around the closing total
BREAK_EVEN = 110 / 210  # win rate needed at -110


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


def predict_all(games: pd.DataFrame, data_dir: str) -> pd.DataFrame:
    """games plus pred_margin, pred_total and model home win probability, for 2003 onward."""
    margins = margin_model.predict_margins(games, margin_model.load_team_stats(data_dir))
    totals = totals_model.predict_totals(games, totals_model.load_team_stats(data_dir))
    out = games.merge(margins[["game_id", "pred_margin", "qb_assumed"]], on="game_id")
    return out.merge(totals[["game_id", "pred_total"]], on="game_id")


def game_card(row: pd.Series) -> dict:
    """Predictions and picks for one game. spread_line is positive when the home team is favored."""
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
        "spread_line": None, "ats_pick": None, "ats_gap": None, "ats_prob": None,
        "total_line": None, "ou_pick": None, "ou_gap": None, "ou_prob": None,
    }
    line = row.get("spread_line")
    if not pd.isna(line):
        gap = margin - float(line)
        if gap >= 0:  # model likes the home team more than the line does
            pick = f"{home} {-line:+g}"
        else:
            pick = f"{away} {line:+g}"
        card.update(spread_line=float(line), ats_pick=pick, ats_gap=abs(gap),
                    ats_prob=norm_cdf(MARGIN_WEIGHT * abs(gap) / LINE_SD_MARGIN))
    tline = row.get("total_line")
    if not pd.isna(tline):
        gap = total - float(tline)
        card.update(total_line=float(tline), ou_pick=f"{'Over' if gap >= 0 else 'Under'} {tline:g}",
                    ou_gap=abs(gap), ou_prob=norm_cdf(TOTAL_WEIGHT * abs(gap) / LINE_SD_TOTAL))
    return card


def week_card(preds: pd.DataFrame, season: int, week: int) -> list[dict]:
    rows = preds[(preds["season"] == season) & (preds["week"] == week)].sort_values(["gameday", "game_id"])
    return [game_card(r) for _, r in rows.iterrows()]


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

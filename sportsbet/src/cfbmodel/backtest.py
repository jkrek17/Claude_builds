"""Backtest bets, their metrics, and a check that openers are not stale.

`bets` turns model predictions into at most one bet per game per market at the closing line, `summary`
scores them (win rate, ROI at the real price and at -110, line value with a t-stat clustered by
season-week), and `opener_staleness` tests whether openers predate last week's results, in which
case opener-based results are not trustworthy.

Spreads are from the home side: negative means the home team is favoured, and the home side covers
when (home points - away points) + spread > 0.
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from cfbmodel import dist

SEGMENTS = ("all", "p4", "g5", "early")
MARKETS = ("spread", "total")
EARLY_WEEK = 4
DEFAULT_PRICE = -110.0
POWER4 = frozenset({"SEC", "Big Ten", "Big 12", "ACC"})
POWER4_TEAMS = frozenset({"Notre Dame"})
PAC12_LAST_P4_SEASON = 2023


def decimal(american: float) -> float:
    return 1 + american / 100 if american > 0 else 1 + 100 / abs(american)


def is_power4(conf, team, season: int) -> bool:
    """Power 4 membership for one team in one season (Pac-12 counts through 2023, Notre Dame always)."""
    if team in POWER4_TEAMS:
        return True
    if not isinstance(conf, str):
        return False
    return conf in POWER4 or (conf == "Pac-12" and season <= PAC12_LAST_P4_SEASON)


def _segment_mask(g: pd.DataFrame, segment: str) -> pd.Series:
    if segment == "all":
        return pd.Series(True, index=g.index)
    if segment == "early":
        return g["week"] <= EARLY_WEEK
    home_p4 = np.array([is_power4(c, t, s) for c, t, s in zip(g["home_conf"], g["home"], g["season"])],
                       dtype=bool)
    away_p4 = np.array([is_power4(c, t, s) for c, t, s in zip(g["away_conf"], g["away"], g["season"])],
                       dtype=bool)
    both = pd.Series(home_p4 & away_p4, index=g.index)
    if segment == "p4":
        return both
    # g5: at least one FBS team outside the Power 4; every team in the table needs a conference
    known = g["home_conf"].notna() & g["away_conf"].notna()
    return known & ~both


def bets(preds: pd.DataFrame, games: pd.DataFrame, market: str, edge_min: float, segment: str,
         dist_fit: dict) -> pd.DataFrame:
    """One row per bet at the closing line, at most one per game: the side with the larger edge.

    Columns: game_id, season, week, market, side (home/away or over/under), line (from the bet side:
    the away side gets -spread_close), price (American), price_assumed (True when no price was
    stored and -110 was used), prob (win probability from `dist`), edge (prob x decimal price - 1),
    result (1 win, 0 loss, NaN push) and clv_points (how far the line moved toward our side from open
    to close, NaN without an opener). Games without a closing line, prediction or final score are
    skipped."""
    if market not in MARKETS:
        raise ValueError(f"market must be one of {MARKETS}, got {market!r}")
    if segment not in SEGMENTS:
        raise ValueError(f"segment must be one of {SEGMENTS}, got {segment!r}")
    g = games.merge(preds[["game_id", "margin", "total"]], on="game_id", how="inner")
    if market == "spread":
        line_col, pred_col, open_col = "spread_close", "margin", "spread_open"
        sides, price_cols = ("home", "away"), ("spread_price_home", "spread_price_away")
    else:
        line_col, pred_col, open_col = "total_close", "total", "total_open"
        sides, price_cols = ("over", "under"), ("total_price_over", "total_price_under")
    g = g[g[line_col].notna() & g[pred_col].notna() & g["home_points"].notna()
          & g["away_points"].notna()]
    g = g[_segment_mask(g, segment)]

    rows = []
    for r in g.itertuples(index=False):
        line = float(getattr(r, line_col))
        pred = float(getattr(r, pred_col))
        if market == "spread":
            p_a, _, p_b = dist.cover_prob(pred, line, dist_fit)
            score = (r.home_points - r.away_points) + line  # > 0 home covers
        else:
            p_a, _, p_b = dist.over_prob(pred, line, dist_fit)
            score = (r.home_points + r.away_points) - line  # > 0 over
        opener = getattr(r, open_col)
        move = np.nan if pd.isna(opener) else float(opener) - line  # home/under gain when > 0
        options = []
        for k, (side, prob) in enumerate(zip(sides, (p_a, p_b))):
            raw = getattr(r, price_cols[k], np.nan)
            assumed = pd.isna(raw)
            price = DEFAULT_PRICE if assumed else float(raw)
            edge = prob * decimal(price) - 1
            sign = 1 if k == 0 else -1
            outcome = sign * score
            result = np.nan if outcome == 0 else float(outcome > 0)
            if market == "spread":
                bet_line, clv = (line, move) if k == 0 else (-line, -move)
            else:
                bet_line, clv = line, (-move if k == 0 else move)
            options.append({"game_id": r.game_id, "season": r.season, "week": r.week,
                            "market": market, "side": side, "line": bet_line, "price": price,
                            "price_assumed": bool(assumed), "prob": float(prob), "edge": float(edge),
                            "result": result, "clv_points": clv})
        if abs(options[0]["edge"] - options[1]["edge"]) < 1e-9:  # coin flip: the model's lean
            best = options[0] if (pred + line if market == "spread" else pred - line) >= 0 else options[1]
        else:
            best = max(options, key=lambda o: o["edge"])
        if best["edge"] >= edge_min:
            rows.append(best)
    cols = ["game_id", "season", "week", "market", "side", "line", "price", "price_assumed", "prob",
            "edge", "result", "clv_points"]
    return pd.DataFrame(rows, columns=cols)


def _profit(result: np.ndarray, dec: np.ndarray) -> np.ndarray:
    return np.where(np.isnan(result), 0.0, np.where(result == 1, dec - 1, -1.0))


def _record(b: pd.DataFrame) -> dict:
    result = b["result"].to_numpy(float)
    dec = np.array([decimal(p) for p in b["price"]], dtype=float)
    n = len(b)
    wins = int(np.sum(result == 1))
    losses = int(np.sum(result == 0))
    pushes = int(np.sum(np.isnan(result)))
    decided = wins + losses
    return {
        "n": n, "wins": wins, "losses": losses, "pushes": pushes,
        "win_rate": wins / decided if decided else math.nan,
        "roi": float(_profit(result, dec).sum() / n) if n else math.nan,
        "roi_110": float(_profit(result, np.full(n, decimal(DEFAULT_PRICE))).sum() / n) if n else math.nan,
    }


def clustered_mean_se(values, clusters) -> tuple[float, float, float]:
    """(mean, clustered se, naive se) of `values`; clusters sum their residuals before squaring."""
    x = np.asarray(values, dtype=float)
    keys = np.asarray(list(clusters), dtype=object)
    ok = np.isfinite(x)
    x, keys = x[ok], keys[ok]
    n = len(x)
    if n < 2:
        return (float(x.mean()) if n else math.nan), math.nan, math.nan
    mean = float(x.mean())
    resid = pd.Series(x - mean)
    sums = resid.groupby(keys).sum().to_numpy()
    n_clusters = len(sums)
    corr = n_clusters / (n_clusters - 1) if n_clusters > 1 else math.nan
    se = math.sqrt(corr * np.sum(sums ** 2)) / n
    naive = float(x.std(ddof=1) / math.sqrt(n))
    return mean, se, naive


def summary(bets: pd.DataFrame) -> dict:
    """Record and returns for a set of bets.

    Keys: n, wins, losses, pushes, win_rate (pushes excluded), roi (profit per bet at the real
    price, pushes return 0), roi_110 (same at -110), clv_mean, clv_se (clustered by season-week),
    clv_se_naive, clv_t (clv_mean / clv_se), n_clv (bets with an opener) and by_season (a list of
    dicts with season and the record keys, sorted by season)."""
    out = _record(bets)
    clusters = [f"{s}-{w}" for s, w in zip(bets["season"], bets["week"])]
    mean, se, naive = clustered_mean_se(bets["clv_points"].to_numpy(float), clusters)
    out.update({
        "clv_mean": mean, "clv_se": se, "clv_se_naive": naive,
        "clv_t": mean / se if se and np.isfinite(se) and se > 0 else math.nan,
        "n_clv": int(bets["clv_points"].notna().sum()),
        "by_season": [{"season": int(s), **_record(grp)}
                      for s, grp in bets.groupby("season", sort=True)],
    })
    return out


def holm(pvalues) -> list[float]:
    """Holm step-down adjusted p-values, in the input order."""
    p = np.asarray(pvalues, dtype=float)
    m = len(p)
    order = np.argsort(p)
    adj = np.empty(m)
    running = 0.0
    for rank, i in enumerate(order):
        running = max(running, (m - rank) * p[i])
        adj[i] = min(running, 1.0)
    return adj.tolist()


def opener_staleness(games: pd.DataFrame) -> float:
    """t-stat of the open-to-close line move on the team's previous-game surprise.

    For every team-game (both sides of each game), the surprise is the team's previous game in the
    same season of actual margin minus closing-line margin, and the move is the change in that
    team's line margin from open to close. If openers were set before last week's game, the close
    absorbs last week's surprise and the slope is strongly positive; |t| > 3 means opener-based
    results should be dropped. The standard error is clustered by game, since each game appears
    once for each team."""
    g = games.sort_values(["start_date", "game_id"])
    margin = (g["home_points"] - g["away_points"]).to_numpy(float)
    close_margin = -g["spread_close"].to_numpy(float)
    move_home = (g["spread_open"] - g["spread_close"]).to_numpy(float)
    team_rows = pd.concat([
        pd.DataFrame({"game_id": g["game_id"].to_numpy(), "season": g["season"].to_numpy(),
                      "start_date": g["start_date"].to_numpy(), "team": g[t].to_numpy(),
                      "surprise_here": sign * (margin - close_margin), "move": sign * move_home})
        for t, sign in (("home", 1.0), ("away", -1.0))
    ], ignore_index=True).sort_values(["team", "season", "start_date", "game_id"])
    team_rows["surprise"] = team_rows.groupby(["team", "season"])["surprise_here"].shift(1)
    d = team_rows[np.isfinite(team_rows["surprise"]) & np.isfinite(team_rows["move"])]
    if len(d) < 3:
        return math.nan
    x = d["surprise"].to_numpy(float)
    y = d["move"].to_numpy(float)
    X = np.column_stack([np.ones_like(x), x])
    bread = np.linalg.inv(X.T @ X)
    beta = bread @ X.T @ y
    resid = y - X @ beta
    scores = pd.DataFrame(X * resid[:, None]).groupby(d["game_id"].to_numpy()).sum().to_numpy()
    n_clusters = len(scores)
    meat = scores.T @ scores * (n_clusters / (n_clusters - 1))
    se = math.sqrt((bread @ meat @ bread)[1, 1])
    return float(beta[1] / se) if se > 0 else math.nan

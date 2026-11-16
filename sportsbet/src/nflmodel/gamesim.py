"""Monte Carlo game simulator on top of the Bayesian ratings.

Three layers, each walk-forward:

1. `bayes_ratings` gives every offence-against-defence matchup an expected value and an uncertainty.
2. A score model turns a matchup into each team's expected points: a ridge regression on the three
   rating channels plus the existing Elo, quarterback, rest, pace, roof and wind features. Its weights
   for season S are fit on earlier seasons only.
3. The simulator plays the game many times. Each run first draws both teams' expected points from
   their uncertainty, then builds each score from touchdowns (6, 7 or 8 points), field goals and the
   odd safety, drawn drive by drive, and settles ties in overtime. Because scores are built from
   real scoring plays, margins pile up on 3 and 7 by themselves.

The runs give a full distribution of the margin and the total, so the chance of a win, of covering
any spread and of going over any total all come from one consistent picture.
"""
from __future__ import annotations

import glob
import os
import zlib

import numpy as np
import pandas as pd

from . import bayes_ratings as br, margin_model, qb_form, scoredist, totals_model

FIRST_MEAN_SEASON = 2003   # first season with a score-model prediction
FIRST_SIM_SEASON = 2006    # first season simulated (needs earlier predictions to fit the score shape)
FIT_START = 2000
RIDGE = 0.02               # per observation, on standardised features
SHAPE_SEASONS = 8          # trailing seasons used to fit how points are built from scoring plays
N_SIMS = 10_000
MARGIN_LO, MARGIN_HI = scoredist.MARGIN_RANGE
TOTAL_LO, TOTAL_HI = scoredist.TOTAL_RANGE

RATING_FEATURES = ["pts_match", "pass_match", "rush_match"]
FEATURES = RATING_FEATURES + ["elo", "qb_rel", "qb_form", "qb_backup", "qb_second", "qb_third",
                              "home", "rest", "indoor", "wind_x", "pace", "div_game"]
_SCORING_COLS = ["game_id", "team", "passing_tds", "rushing_tds", "special_teams_tds", "def_tds",
                 "fumble_recovery_tds", "fg_made", "pat_made", "passing_2pt_conversions",
                 "rushing_2pt_conversions", "def_safeties"]


def load_scoring(data_dir: str) -> pd.DataFrame:
    """Touchdowns, field goals, extra points and safeties per team-game from nflverse team stats."""
    files = sorted(glob.glob(os.path.join(data_dir, "stats_team_week_*.csv")))
    if not files:
        raise FileNotFoundError(f"no stats_team_week_*.csv in {data_dir}")
    s = pd.concat([pd.read_csv(f, usecols=_SCORING_COLS, low_memory=False) for f in files], ignore_index=True)
    s = s[s["team"].notna()].fillna(0)
    s["team"] = s["team"].replace(br.TEAM_MAP)
    out = pd.DataFrame({"game_id": s["game_id"], "team": s["team"]})
    out["td"] = (s["passing_tds"] + s["rushing_tds"] + s["special_teams_tds"] + s["def_tds"]
                 + s["fumble_recovery_tds"])
    out["fg"] = s["fg_made"]
    out["pat"] = s["pat_made"]
    out["two"] = s["passing_2pt_conversions"] + s["rushing_2pt_conversions"]
    out["safety"] = s["def_safeties"]
    return out.drop_duplicates(["game_id", "team"])


def team_rows(games: pd.DataFrame, team_stats: pd.DataFrame, player_stats: pd.DataFrame,
              totals_stats: pd.DataFrame, scoring: pd.DataFrame | None = None,
              filter_params: dict | None = None, state: dict | None = None) -> pd.DataFrame:
    """Two rows per game (one per team) with pre-game features, rating uncertainty and the outcome."""
    ratings = br.run_filter(games, team_stats, params=filter_params, state=state)
    mf = margin_model.compute_features(games, team_stats)
    mf = mf.merge(qb_form.qb_form_features(games, player_stats), on="game_id", how="left")
    tf = totals_model.build_features(games, totals_stats)
    g = games[["game_id", "season", "week", "gameday", "home_team", "away_team", "home_score", "away_score",
               "location"]].copy()
    g["gameday"] = g["gameday"].astype(str)
    g = g.merge(ratings.drop(columns="season"), on="game_id").merge(
        mf[["game_id", "elo_diff", "qb_home", "qb_away", "qb_form_diff", "qb_backup_diff", "rest_diff"]], on="game_id"
    ).merge(tf[["game_id", "indoor", "wind_x", "clim_wind_x", "pace", "div_game"]], on="game_id")
    neutral = g["location"].astype(str).str.lower() == "neutral"
    parts = []
    for side, other, sign in (("home", "away", 1.0), ("away", "home", -1.0)):
        r = pd.DataFrame({
            "game_id": g["game_id"], "season": g["season"], "week": g["week"], "gameday": g["gameday"],
            "side": side, "team": g[f"{side}_team"].replace(br.TEAM_MAP),
            "points": pd.to_numeric(g[f"{side}_score"], errors="coerce"),
            "elo": sign * g["elo_diff"], "qb_rel": g[f"qb_{side}"],
            "qb_form": sign * g["qb_form_diff"], "qb_backup": sign * g["qb_backup_diff"],
            "qb_second": (g[f"{side}_qb_string"] == 2).astype(float),
            "qb_third": (g[f"{side}_qb_string"] >= 3).astype(float),
            "qb_changed": g[f"{side}_qb_changed"], "qb_string": g[f"{side}_qb_string"],
            "home": np.where(neutral, 0.0, sign), "rest": sign * g["rest_diff"],
            "indoor": g["indoor"], "wind_x": g["wind_x"], "wind_x_pred": g["clim_wind_x"],
            "pace": g["pace"], "div_game": g["div_game"],
        })
        for c in br.CHANNELS:
            r[f"{c}_match"] = g[f"{c}_{side}"]
            r[f"{c}_var"] = g[f"{c}_{side}_var"]
        parts.append(r)
    rows = pd.concat(parts, ignore_index=True)
    if scoring is not None:
        rows = rows.merge(scoring, on=["game_id", "team"], how="left")
    return rows


def fit_means(rows: pd.DataFrame, features=None, ridge: float = RIDGE) -> pd.DataFrame:
    """Add `mu` (expected points) and `mu_var` (its variance from rating uncertainty), walk-forward."""
    features = list(features or FEATURES)
    rows = rows.copy()
    X = rows[features].to_numpy(dtype=float)
    Xp = rows[[("wind_x_pred" if f == "wind_x" else f) for f in features]].to_numpy(dtype=float)
    y = rows["points"].to_numpy(dtype=float)
    seasons = rows["season"].to_numpy()
    ok = np.isfinite(y) & np.isfinite(X).all(1)
    mu = np.full(len(rows), np.nan)
    mu_var = np.full(len(rows), np.nan)
    coefs = {}
    var_cols = [f"{f.split('_')[0]}_var" for f in features if f in RATING_FEATURES]
    var_idx = [features.index(f) for f in features if f in RATING_FEATURES]
    V = rows[var_cols].to_numpy(dtype=float) if var_cols else np.zeros((len(rows), 0))
    for s in sorted(set(seasons[seasons >= FIRST_MEAN_SEASON])):
        tr = ok & (seasons >= FIT_START) & (seasons < s)
        c = totals_model._fit(X[tr], y[tr], ridge)
        te = seasons == s
        mu[te] = c[0] + Xp[te] @ c[1:]
        mu_var[te] = V[te] @ (c[1:][var_idx] ** 2) if var_idx else 0.0
        coefs[int(s)] = dict(zip(["intercept"] + features, c))
    rows["mu"] = mu
    rows["mu_var"] = mu_var
    rows.attrs["coefs"] = coefs
    return rows


def game_table(rows: pd.DataFrame) -> pd.DataFrame:
    """One row per game: both teams' expected points and uncertainty."""
    h = rows[rows["side"] == "home"].set_index("game_id")
    a = rows[rows["side"] == "away"].set_index("game_id")
    t = pd.DataFrame({"season": h["season"], "week": h["week"], "gameday": h["gameday"],
                      "mu_home": h["mu"], "mu_away": a["mu"].reindex(h.index),
                      "var_home": h["mu_var"], "var_away": a["mu_var"].reindex(h.index),
                      "home_points": h["points"], "away_points": a["points"].reindex(h.index)})
    return t.reset_index()


# ------------------------------------------------------------------ score shape
def fit_shape(rows: pd.DataFrame, games: pd.DataFrame, season: int) -> dict | None:
    """How points are built from scoring plays, from the `SHAPE_SEASONS` seasons before `season`.

    Uses only finished games of earlier seasons and their own walk-forward predictions."""
    w = rows[(rows["season"] < season) & (rows["season"] >= season - SHAPE_SEASONS)
             & rows["points"].notna() & rows["mu"].notna() & rows["td"].notna()]
    if len(w) < 1500:
        return None
    td = w["td"].sum()
    p7, p8 = w["pat"].sum() / td, w["two"].sum() / td
    per_td = 6 + p7 + 2 * p8
    var_td_pts = (p7 + 4 * p8) - (p7 + 2 * p8) ** 2
    slope, intercept = np.polyfit(w["mu"], w["fg"], 1)        # field goals barely move with team strength
    safety = w["safety"].mean()
    resid = (w["points"] - w["mu"]).to_numpy()
    resid_var = float(resid.var())
    # correlation between the two teams' surprises in the same game
    pair = w.pivot(index="game_id", columns="side", values=["points", "mu"]).dropna()
    cov = float(np.cov(pair["points"]["home"] - pair["mu"]["home"], pair["points"]["away"] - pair["mu"]["away"])[0, 1])
    game_var = abs(cov)
    # number of drives: chosen so a simulated team score has the same spread as real ones
    lam_fg = np.clip(intercept + slope * w["mu"], 0.3, None).to_numpy()
    lam_td = np.clip((w["mu"] - 3 * lam_fg - 2 * safety) / per_td, 0.2, None).to_numpy()
    extra = float(w["mu_var"].mean()) + game_var
    best, best_gap = 12, np.inf
    for n in range(7, 41):
        p_td = np.minimum(lam_td / n, 0.9)
        p_fg = np.minimum(lam_fg / np.maximum(n - lam_td, 1.0), 0.9)
        v = (per_td ** 2 * n * p_td * (1 - p_td) + var_td_pts * lam_td
             + 9 * (n - lam_td) * p_fg * (1 - p_fg) + 9 * p_fg ** 2 * n * p_td * (1 - p_td)
             - 2 * per_td * 3 * p_fg * n * p_td * (1 - p_td) + 4 * safety).mean() + extra
        if abs(v - resid_var) < best_gap:
            best, best_gap = n, abs(v - resid_var)
    done = games[(games["season"] < season) & (games["season"] >= max(season - 2 * SHAPE_SEASONS, 2012))
                 & (games["overtime"] == 1) & games["result"].notna()]
    m = done["result"].abs()
    k = 20.0  # prior games at the long-run split, so a thin sample cannot swing the shares
    ot = {"tie": ((m == 0).sum() + 0.035 * k) / (len(m) + k), "three": ((m == 3).sum() + 0.65 * k) / (len(m) + k),
          "six": ((m == 6).sum() + 0.28 * k) / (len(m) + k)}
    return {"drives": best, "p7": float(p7), "p8": float(p8), "fg_intercept": float(intercept),
            "fg_slope": float(slope), "safety": float(safety), "cov": cov, "game_var": game_var,
            "resid_sd": resid_var ** 0.5, "ot": ot}


def simulate(mu_home: float, mu_away: float, var_home: float, var_away: float, shape: dict,
             n: int = N_SIMS, seed: int = 0, uncertainty: bool = True) -> tuple[np.ndarray, np.ndarray]:
    """Play one game `n` times. Returns (home scores, away scores) as whole numbers."""
    rng = np.random.default_rng(seed)
    vh = (var_home if uncertainty else 0.0) + shape["game_var"]
    va = (var_away if uncertainty else 0.0) + shape["game_var"]
    cov = np.array([[vh + 1e-9, shape["cov"]], [shape["cov"], va + 1e-9]])  # the sliver keeps it invertible
    draw = rng.multivariate_normal([mu_home, mu_away], cov, size=n, method="cholesky")
    per_td = 6 + shape["p7"] + 2 * shape["p8"]
    nd = shape["drives"]
    scores = []
    for k in range(2):
        mu = np.clip(draw[:, k], 3.0, 60.0)
        lam_fg = np.clip(shape["fg_intercept"] + shape["fg_slope"] * mu, 0.3, None)
        lam_td = np.clip((mu - 3 * lam_fg - 2 * shape["safety"]) / per_td, 0.05, None)
        td = rng.binomial(nd, np.minimum(lam_td / nd, 0.9))
        fg = rng.binomial(nd - td, np.minimum(lam_fg / np.maximum(nd - lam_td, 1.0), 0.9))
        seven = rng.binomial(td, shape["p7"])
        eight = rng.binomial(td - seven, min(shape["p8"] / max(1 - shape["p7"], 1e-9), 1.0))
        safety = rng.binomial(1, shape["safety"], size=n)
        scores.append(6 * td + seven + 2 * eight + 3 * fg + 2 * safety)
    home, away = scores
    # overtime: most end on a field goal or a touchdown; the better team wins a little more often
    tied = np.flatnonzero(home == away)
    if len(tied):
        not_tied = max(n - len(tied), 1)
        p_home = 0.5 + 0.5 * ((home > away).sum() / not_tied - 0.5)
        ot = shape["ot"]
        u = rng.random(len(tied))
        pts = np.where(u < ot["tie"], 0, np.where(u < ot["tie"] + ot["three"], 3,
                       np.where(u < ot["tie"] + ot["three"] + ot["six"], 6, 7)))
        home_wins = rng.random(len(tied)) < p_home
        home[tied] += np.where(home_wins, pts, 0)
        away[tied] += np.where(home_wins, 0, pts)
    return home, away


def pmfs(home: np.ndarray, away: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Margin (home minus away) and total distributions from simulated scores."""
    m = np.clip(home - away, MARGIN_LO, MARGIN_HI) - MARGIN_LO
    t = np.clip(home + away, TOTAL_LO, TOTAL_HI) - TOTAL_LO
    return (np.bincount(m, minlength=MARGIN_HI - MARGIN_LO + 1) / len(m),
            np.bincount(t, minlength=TOTAL_HI - TOTAL_LO + 1) / len(t))


def simulate_table(table: pd.DataFrame, rows: pd.DataFrame, games: pd.DataFrame, n: int = N_SIMS,
                   uncertainty: bool = True, seasons=None) -> dict:
    """Simulate every game in `table` from FIRST_SIM_SEASON on.

    Returns {"game_id": [...], "margin": array (games x margins), "total": array (games x totals),
    "shapes": {season: shape}}. Each game has its own fixed random seed, so results repeat exactly."""
    ids, margin, total, shapes = [], [], [], {}
    for season in sorted(set(table["season"])):
        if season < FIRST_SIM_SEASON or (seasons is not None and season not in seasons):
            continue
        shape = fit_shape(rows, games, season)
        if shape is None:
            continue
        shapes[int(season)] = shape
        for r in table[table["season"] == season].itertuples(index=False):
            if not (np.isfinite(r.mu_home) and np.isfinite(r.mu_away)):
                continue
            h, a = simulate(r.mu_home, r.mu_away, r.var_home, r.var_away, shape, n=n,
                            seed=zlib.crc32(r.game_id.encode()), uncertainty=uncertainty)
            pm, pt = pmfs(h, a)
            ids.append(r.game_id)
            margin.append(pm)
            total.append(pt)
    return {"game_id": ids, "margin": np.array(margin), "total": np.array(total), "shapes": shapes}


# ------------------------------------------------------------------ calibration
def calibration_factors(pmf_rows: np.ndarray, outcomes: np.ndarray, lo: int, symmetric: bool,
                        kappa: float = 25.0) -> np.ndarray:
    """How much more or less often each whole number came up than the simulation said it would."""
    expected = pmf_rows.sum(0)
    idx = (np.clip(np.rint(outcomes), lo, lo + pmf_rows.shape[1] - 1) - lo).astype(int)
    observed = np.bincount(idx, minlength=pmf_rows.shape[1]).astype(float)
    if symmetric:
        observed, expected = observed + observed[::-1], expected + expected[::-1]
    return (observed + kappa) / (expected + kappa)


def apply_factors(pmf_rows: np.ndarray, factors: np.ndarray) -> np.ndarray:
    p = pmf_rows * factors
    return p / p.sum(-1, keepdims=True)


def line_probs(pmf: np.ndarray, line: float, lo: int) -> tuple[float, float, float]:
    """P(outcome > line), P(outcome == line), P(outcome < line)."""
    return scoredist.line_probs(pmf, line, lo)

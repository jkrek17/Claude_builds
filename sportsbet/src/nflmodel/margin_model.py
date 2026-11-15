"""Market-free NFL margin-of-victory model (home score minus away score).

Pipeline (everything is strictly sequential / walk-forward):

1. One chronological pass over the games, batched by calendar date.  For each
   date, features for ALL games that day are read from the rating state as it
   stood before that day; only afterwards are that day's played games used to
   update the state.  Unplayed games never update anything.
     * margin-of-victory Elo (autocorrelation-corrected MOV multiplier,
       between-season regression to the mean)
     * opponent-adjusted pass / rush EPA ratings for offence and defence
       (online updates, between-season regression)
     * starting-QB value (opponent-adjusted team passing EPA in his starts,
       exponentially decayed, shrunk to a running "new starter" prior),
       expressed relative to his team's current pass-offence rating
     * drifting home-field advantage (slow EWMA of home residuals)
     * rest differential
2. A no-intercept ridge regression blends the features into a predicted
   margin.  The weights used for season S are fit only on seasons < S.

The betting line is never read.  Columns used from `games`: game_id, season,
gameday, home_team, away_team, home_score, away_score (or result), location,
home_rest, away_rest, home_qb_id, away_qb_id.
"""
from __future__ import annotations

import glob
import math
import os

import numpy as np
import pandas as pd

# Residual SD of (actual margin - pred_margin), out of sample 2010-2025
# (see backtest.py / REPORT.md).  Use: P(home win) = Phi(pred_margin / RESID_SD),
# P(home covers spread s) = Phi((pred_margin - s) / RESID_SD).
RESID_SD = 13.3

FIRST_PRED_SEASON = 2003   # first season returned by predict_margins
FIRST_FIT_SEASON = 2000    # 1999 is burn-in for the ratings

TEAM_MAP = {"OAK": "LV", "SD": "LAC", "STL": "LA", "JAC": "JAX", "SL": "LA",
            "LAR": "LA", "WSH": "WAS"}

# Hyper-parameters.  Tuned ONLY on seasons 2003-2009 (see backtest.py --tune);
# 2010+ was never used to choose them.
PARAMS = dict(
    elo_k=28.0,         # Elo K factor
    elo_regress=0.40,   # fraction regressed to 1500 between seasons
    elo_per_pt=25.0,    # Elo points per point of margin
    elo_autocorr=True,  # 2.2/(0.001*winner_elo_diff+2.2) correction on the MOV multiplier
    epa_k=0.02,         # online learning rate of the EPA ratings
    epa_regress=0.30,   # fraction regressed to 0 between seasons
    lg_k=0.01,          # learning rate of the running league-mean EPA
    qb_decay=0.90,      # per-start decay of a QB's history
    qb_n0=8.0,          # prior weight (in starts) on the new-starter prior
    qb_prior0=-2.0,     # initial new-starter prior (EPA/game vs league mean)
    qb_prior_k=0.05,    # learning rate of the new-starter prior
    hfa0=2.6,           # initial home-field advantage (points)
    hfa_k=0.001,        # learning rate of the drifting HFA
    ridge=200.0,          # ridge penalty of the blend
)

FEATURES = ["elo_diff", "pass_diff", "rush_diff", "qb_diff", "hfa", "rest_diff"]


def load_team_stats(data_dir: str) -> pd.DataFrame:
    """Load and concatenate nflverse stats_team_week_*.csv files."""
    cols = ["season", "week", "team", "opponent_team", "game_id",
            "passing_epa", "rushing_epa"]
    files = sorted(glob.glob(os.path.join(data_dir, "stats_team_week_*.csv")))
    if not files:
        raise FileNotFoundError(f"no stats_team_week_*.csv in {data_dir}")
    return pd.concat([pd.read_csv(f, usecols=cols) for f in files],
                     ignore_index=True)


def _norm_team(s: pd.Series) -> pd.Series:
    return s.replace(TEAM_MAP)


def norm_cdf(x):
    """Standard normal CDF (no scipy)."""
    x = np.asarray(x, dtype=float)
    return 0.5 * (1.0 + np.vectorize(math.erf)(x / math.sqrt(2.0)))


def compute_features(games: pd.DataFrame, team_stats: pd.DataFrame,
                     params: dict | None = None) -> pd.DataFrame:
    """Pre-game features for every game in `games` (all seasons), one row per
    game_id.  Row i only depends on games played on earlier calendar dates."""
    p = dict(PARAMS)
    if params:
        p.update(params)

    g = games.copy()
    g["home_team"] = _norm_team(g["home_team"])
    g["away_team"] = _norm_team(g["away_team"])
    if "result" in g.columns:
        res = pd.to_numeric(g["result"], errors="coerce")
    else:
        res = pd.Series(np.nan, index=g.index)
    if "home_score" in g.columns and "away_score" in g.columns:
        res = res.fillna(pd.to_numeric(g["home_score"], errors="coerce")
                         - pd.to_numeric(g["away_score"], errors="coerce"))
    g["_res"] = res
    g["gameday"] = g["gameday"].astype(str)
    g = g.sort_values(["gameday", "game_id"], kind="mergesort").reset_index(drop=True)

    # stats lookup: game_id -> {team: (passing_epa, rushing_epa)}
    ts = team_stats.dropna(subset=["team", "passing_epa", "rushing_epa"]).copy()
    ts["team"] = _norm_team(ts["team"])
    stats: dict = {}
    for gid, tm, pe, re_ in zip(ts["game_id"], ts["team"], ts["passing_epa"],
                                ts["rushing_epa"]):
        stats.setdefault(gid, {})[tm] = (float(pe), float(re_))

    elo: dict = {}
    op: dict = {}; orr: dict = {}; dp: dict = {}; dr: dict = {}
    last_qb: dict = {}
    qb_sw: dict = {}; qb_swx: dict = {}; qb_n: dict = {}
    lg_pass, lg_rush = 0.0, -2.0
    qb_prior = p["qb_prior0"]
    hfa = p["hfa0"]
    cur_season = None

    def qb_value(q):
        if q is None:
            return qb_prior
        return (qb_swx.get(q, 0.0) + p["qb_n0"] * qb_prior) / (qb_sw.get(q, 0.0) + p["qb_n0"])

    n = len(g)
    out = {k: np.full(n, np.nan) for k in
           ["elo_home", "elo_away", "elo_diff", "pass_diff", "rush_diff",
            "qb_home", "qb_away", "qb_diff", "hfa", "rest_diff"]}
    qb_assumed = np.zeros(n, dtype=bool)

    gid_a = g["game_id"].to_numpy()
    season_a = g["season"].to_numpy()
    day_a = g["gameday"].to_numpy()
    h_a = g["home_team"].to_numpy(); a_a = g["away_team"].to_numpy()
    res_a = g["_res"].to_numpy(dtype=float)
    neutral_a = (g["location"].astype(str).str.lower() == "neutral").to_numpy()
    hrest = pd.to_numeric(g["home_rest"], errors="coerce").fillna(7).to_numpy()
    arest = pd.to_numeric(g["away_rest"], errors="coerce").fillna(7).to_numpy()
    hq_a = g["home_qb_id"].to_numpy(dtype=object)
    aq_a = g["away_qb_id"].to_numpy(dtype=object)

    i = 0
    while i < n:
        j = i
        while j < n and day_a[j] == day_a[i]:
            j += 1
        # ---- between-season regression (triggered by the first row of a season)
        if cur_season is None or season_a[i] != cur_season:
            if cur_season is not None:
                for t in elo:
                    elo[t] = 1500.0 + (1 - p["elo_regress"]) * (elo[t] - 1500.0)
                for d in (op, orr, dp, dr):
                    for t in d:
                        d[t] *= (1 - p["epa_regress"])
            cur_season = season_a[i]
        # ---- 1) features for every game on this date, from the pre-date state
        cache = []
        for k in range(i, j):
            h, a = h_a[k], a_a[k]
            for t in (h, a):
                if t not in elo:
                    elo[t] = 1500.0; op[t] = orr[t] = dp[t] = dr[t] = 0.0
            hq, aq = hq_a[k], aq_a[k]
            assumed = False
            if not isinstance(hq, str):
                hq = last_qb.get(h); assumed = True
            if not isinstance(aq, str):
                aq = last_qb.get(a); assumed = True
            qb_assumed[k] = assumed
            h_here = 0.0 if neutral_a[k] else hfa
            ed = (elo[h] - elo[a]) / p["elo_per_pt"]
            qh = qb_value(hq) - op[h]
            qa = qb_value(aq) - op[a]
            out["elo_home"][k] = elo[h]; out["elo_away"][k] = elo[a]
            out["elo_diff"][k] = ed
            out["pass_diff"][k] = (op[h] + dp[a]) - (op[a] + dp[h])
            out["rush_diff"][k] = (orr[h] + dr[a]) - (orr[a] + dr[h])
            out["qb_home"][k] = qh; out["qb_away"][k] = qa
            out["qb_diff"][k] = qh - qa
            out["hfa"][k] = h_here
            out["rest_diff"][k] = min(max(hrest[k] - arest[k], -7.0), 7.0)
            cache.append((k, h, a, hq, aq, h_here, ed))
        # ---- 2) update the state with the games actually played on this date
        upd = []
        for (k, h, a, hq, aq, h_here, ed) in cache:
            m = res_a[k]
            if np.isnan(m):
                continue
            # Elo
            dlt = (elo[h] - elo[a]) + h_here * p["elo_per_pt"]
            exp_h = 1.0 / (1.0 + 10 ** (-dlt / 400.0))
            s = 1.0 if m > 0 else (0.0 if m < 0 else 0.5)
            wdiff = dlt if m > 0 else (-dlt if m < 0 else 0.0)
            mult = math.log(abs(m) + 1.0)
            if p["elo_autocorr"]:
                mult *= 2.2 / (0.001 * wdiff + 2.2)
            d_elo = p["elo_k"] * mult * (s - exp_h)
            # HFA drift (only real home games)
            d_hfa = 0.0 if neutral_a[k] else p["hfa_k"] * (m - (ed + h_here))
            # EPA / QB
            st = stats.get(gid_a[k], {})
            epa_upd = []
            for off, dfn, q in ((h, a, hq), (a, h, aq)):
                if off not in st:
                    continue
                pe, re_ = st[off]
                x_p = pe - lg_pass; x_r = re_ - lg_rush
                e_p = x_p - (op[off] + dp[dfn])
                e_r = x_r - (orr[off] + dr[dfn])
                epa_upd.append((off, dfn, q, pe, re_, e_p, e_r, x_p - dp[dfn]))
            upd.append((k, h, a, hq, aq, d_elo, d_hfa, epa_upd))
        for (k, h, a, hq, aq, d_elo, d_hfa, epa_upd) in upd:
            elo[h] += d_elo; elo[a] -= d_elo
            hfa += d_hfa
            for (off, dfn, q, pe, re_, e_p, e_r, qobs) in epa_upd:
                op[off] += p["epa_k"] * e_p; dp[dfn] += p["epa_k"] * e_p
                orr[off] += p["epa_k"] * e_r; dr[dfn] += p["epa_k"] * e_r
                if q is not None:
                    if qb_n.get(q, 0) < 8:
                        qb_prior += p["qb_prior_k"] * (qobs - qb_prior)
                    qb_sw[q] = qb_sw.get(q, 0.0) * p["qb_decay"] + 1.0
                    qb_swx[q] = qb_swx.get(q, 0.0) * p["qb_decay"] + qobs
                    qb_n[q] = qb_n.get(q, 0) + 1
                lg_pass += p["lg_k"] * (pe - lg_pass)
                lg_rush += p["lg_k"] * (re_ - lg_rush)
            if isinstance(hq, str):
                last_qb[h] = hq
            if isinstance(aq, str):
                last_qb[a] = aq
        i = j

    f = pd.DataFrame({"game_id": gid_a, "season": season_a, "gameday": day_a,
                      "result": res_a})
    for k, v in out.items():
        f[k] = v
    f["qb_assumed"] = qb_assumed
    return f


def _ridge(X: np.ndarray, y: np.ndarray, lam: float) -> np.ndarray:
    A = X.T @ X + lam * np.eye(X.shape[1])
    return np.linalg.solve(A, X.T @ y)


def blend_walk_forward(feats: pd.DataFrame, features=None, ridge=None,
                       first_fit_season: int = FIRST_FIT_SEASON,
                       first_pred_season: int = FIRST_PRED_SEASON):
    """Predicted margin per row; weights for season S fit on played games of
    seasons first_fit_season..S-1 only.  Returns (pred array, {season: weights})."""
    features = list(features or FEATURES)
    lam = PARAMS["ridge"] if ridge is None else ridge
    X = feats[features].to_numpy(dtype=float)
    y = feats["result"].to_numpy(dtype=float)
    seas = feats["season"].to_numpy()
    pred = np.full(len(feats), np.nan)
    weights = {}
    for s in sorted(set(seas)):
        if s < first_pred_season:
            continue
        tr = (seas >= first_fit_season) & (seas < s) & ~np.isnan(y)
        if tr.sum() < 200:
            continue
        w = _ridge(X[tr], y[tr], lam)
        weights[int(s)] = dict(zip(features, w))
        te = seas == s
        pred[te] = X[te] @ w
    return pred, weights


def predict_margins(games: pd.DataFrame, team_stats: pd.DataFrame,
                    player_stats: pd.DataFrame | None = None) -> pd.DataFrame:
    """One row per game_id for every game from 2003 on (played or not) with
    `pred_margin` (home minus away, points) and its component features.

    Each prediction uses only games played on earlier dates plus schedule
    facts (site, rest, announced starting QB; if the QB id is missing the
    team's most recent starter is assumed and `qb_assumed` is True).

    With `player_stats` (quarterback rows of nflverse weekly player stats) the
    blend also uses each starter's own rolling efficiency and an in-season
    backup flag, see `qb_form`."""
    feats = compute_features(games, team_stats)
    features = list(FEATURES)
    if player_stats is not None:
        from . import qb_form
        feats = feats.merge(qb_form.qb_form_features(games, player_stats), on="game_id", how="left")
        features += qb_form.FEATURES
    pred, _ = blend_walk_forward(feats, features=features)
    feats["pred_margin"] = pred
    feats["home_win_prob"] = norm_cdf(pred / RESID_SD)
    out = feats[feats["season"] >= FIRST_PRED_SEASON]
    cols = ["game_id", "pred_margin", "home_win_prob"] + features + \
           ["elo_home", "elo_away", "qb_home", "qb_away", "qb_assumed"]
    return out[cols].reset_index(drop=True)


if __name__ == "__main__":
    import sys
    d = sys.argv[1] if len(sys.argv) > 1 else "."
    gm = pd.read_csv(os.path.join(d, "games.csv"))
    pm = predict_margins(gm, load_team_stats(d))
    print(pm.tail(20).to_string())

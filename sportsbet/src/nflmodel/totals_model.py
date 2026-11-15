"""Market-free NFL game-totals model (numpy + pandas only).

Public API
----------
load_team_stats(data_dir)            -> concatenated nflverse weekly team stats
build_features(games, team_stats)    -> one row per game, pre-kickoff features only
predict_totals(games, team_stats)    -> game_id, pred_total + component features (2003+)
over_probability(pred, line)         -> normal-approximation P(total > line)
RESID_SD                             -> residual SD of actual total around pred_total

No-look-ahead design
--------------------
* Games are processed one calendar day at a time.  Every feature of a game on
  day D is read from state that was last updated with games played on days < D.
* The closing total / spread, observed temp / wind of the game itself, and the
  game's own score or stats are never read when building its features.
* The final linear layer is refit once per season S on games of seasons
  [FIT_START, S-1] only, so coefficients used in season S never see season S.
* Observed wind of PAST games is used only as a stadium-by-month climatology
  (games of earlier seasons only).

Hyper-parameters in PARAMS were tuned on seasons <= 2009 only (backtest.py --tune),
except the league-average learning rate km, which was set by judgement (see
REPORT.md).  The out-of-sample report covers 2010-2025.

Unplayed games whose starting QB is not yet listed use each team's most recent
starter (column qb_known = 0).
"""
from __future__ import annotations

import glob
import math
import os

import numpy as np
import pandas as pd

# Residual SD of (actual total - pred_total), walk-forward 2010-2025 (backtest.py).
RESID_SD = 13.4

FIRST_PRED_SEASON = 2003   # first season returned by predict_totals
FIT_START = 2000           # first season used to fit the linear layer (1999 = burn-in)

TEAM_MAP = {"OAK": "LV", "SD": "LAC", "STL": "LA"}

# Rating-system hyper-parameters, tuned on seasons <= 2009 only.
#   k   : per-game learning rate of team offence / defence ratings
#   kq  : per-game learning rate of the starting-QB rating
#   r   : fraction of a team rating kept across an off-season
#   rq  : fraction of a QB rating kept across an off-season
#   q0  : rating given to a QB with no previous start
#   km  : per-team-game learning rate of the league average
PARAMS = {
    "pts":   dict(k=0.020, kq=0.020, r=0.90, rq=0.85, q0=0.0, km=0.002),
    "epa":   dict(k=0.030, kq=0.050, r=0.75, rq=1.00, q0=-0.04, km=0.002),
    "plays": dict(k=0.020, kq=0.020, r=0.75, rq=0.70, q0=0.0, km=0.002),
}
INIT_MU = {"pts": 20.8, "epa": -0.02, "plays": 64.0}   # rough late-1990s levels

WIND_SHRINK = 8.0      # pseudo-games shrinking stadium-month wind towards outdoor mean
WIND_PRIOR = 8.5       # mph, used before any outdoor game has been seen

# Features of the final linear layer (intercept added automatically).
FEATURES = ["base_pts", "off_pts", "def_pts", "off_epa", "def_epa", "pace",
            "indoor", "clim_wind_x", "div_game"]
RIDGE = 0.02               # ridge penalty (per observation, on standardised features)


# --------------------------------------------------------------------------- IO
def load_team_stats(data_dir: str) -> pd.DataFrame:
    """Load and concatenate stats_team_week_*.csv from `data_dir`."""
    files = sorted(glob.glob(os.path.join(data_dir, "stats_team_week_*.csv")))
    if not files:
        raise FileNotFoundError(f"no stats_team_week_*.csv in {data_dir}")
    cols = ["season", "week", "team", "game_id", "passing_epa", "rushing_epa",
            "attempts", "carries", "sacks_suffered"]
    out = pd.concat([pd.read_csv(f, usecols=cols, low_memory=False) for f in files],
                    ignore_index=True)
    return out[out["team"].notna()].reset_index(drop=True)


def _norm_team(s: pd.Series) -> pd.Series:
    return s.replace(TEAM_MAP)


def _prep(games: pd.DataFrame, team_stats: pd.DataFrame) -> pd.DataFrame:
    """Sort games chronologically and attach each side's offensive EPA/play and plays.

    Stats are attached only to games that have a final score."""
    g = games.copy()
    g["home"] = _norm_team(g["home_team"])
    g["away"] = _norm_team(g["away_team"])
    g["gameday"] = pd.to_datetime(g["gameday"])
    g = g.sort_values(["gameday", "game_id"], kind="mergesort").reset_index(drop=True)
    played = g["home_score"].notna() & g["away_score"].notna()

    s = team_stats[["game_id", "team", "passing_epa", "rushing_epa",
                    "attempts", "carries", "sacks_suffered"]].copy()
    s = s[s["team"].notna()]
    s["team"] = _norm_team(s["team"])
    s["plays"] = s["attempts"] + s["carries"] + s["sacks_suffered"]
    s["epa"] = (s["passing_epa"].fillna(0) + s["rushing_epa"].fillna(0)) / s["plays"].where(s["plays"] > 0)
    s = s.drop_duplicates(["game_id", "team"])[["game_id", "team", "epa", "plays"]]
    for side in ("home", "away"):
        m = g[["game_id", side]].merge(s, left_on=["game_id", side], right_on=["game_id", "team"],
                                       how="left")
        g[f"{side}_epa"] = np.where(played, m["epa"].to_numpy(), np.nan)
        g[f"{side}_plays"] = np.where(played, m["plays"].to_numpy(), np.nan)
    g["played"] = played
    return g


# ----------------------------------------------------------------- rating system
class _Rating:
    """Online opponent-adjusted offence/defence(/QB) ratings for one per-team-game metric.

    expected(team vs opp) = mu + O[team] + Q[qb] + D[opp]  (+/- home edge)
    After a game each term moves by its learning rate times the residual."""

    def __init__(self, name: str, p: dict):
        self.p = p
        self.mu = INIT_MU[name]
        self.hfa = 0.0
        self.O: dict = {}
        self.D: dict = {}
        self.Q: dict = {}

    def new_season(self):
        """Off-season: recentre team ratings and regress them (and QBs) to the mean."""
        r, rq, q0 = self.p["r"], self.p["rq"], self.p["q0"]
        for d in (self.O, self.D):
            if d:
                m = float(np.mean(list(d.values())))
                for t in d:
                    d[t] = (d[t] - m) * r
        for q in self.Q:
            self.Q[q] = q0 + (self.Q[q] - q0) * rq

    def q(self, qb):
        if self.p["kq"] <= 0 or qb is None:
            return 0.0
        return self.Q.get(qb, self.p["q0"])

    def parts(self, team, opp, qb):
        return self.O.get(team, 0.0) + self.q(qb), self.D.get(opp, 0.0)

    def residual(self, team, opp, qb, value, home_sign):
        """Observed minus expected value of the metric for `team` against `opp`."""
        o, d = self.parts(team, opp, qb)
        return value - (self.mu + o + d + home_sign * self.hfa)


def _nan(x):
    return x is None or (isinstance(x, float) and math.isnan(x))


def build_features(games: pd.DataFrame, team_stats: pd.DataFrame, params: dict | None = None
                   ) -> pd.DataFrame:
    """Pre-kickoff features for every game in `games` (all seasons, played or not)."""
    params = params or PARAMS
    g = _prep(games, team_stats)
    R = {m: _Rating(m, params[m]) for m in ("pts", "epa", "plays")}
    last_qb: dict = {}                      # team -> most recent starting QB
    wind_sum: dict = {}                     # (stadium, month) -> [sum, n], past seasons only
    wind_pending: list = []                 # current-season observations, released next season
    wind_all = [0.0, 0]
    cur_season = None

    n = len(g)
    out = {k: np.full(n, np.nan) for k in
           ["mu_pts", "off_pts", "def_pts", "mu_epa", "off_epa", "def_epa", "mu_plays", "pace",
            "qb_adj", "clim_wind", "home_pts_hat", "away_pts_hat"]}
    out["qb_known"] = np.zeros(n)

    col = {c: g[c].to_numpy() for c in
           ["season", "home", "away", "home_score", "away_score", "home_epa", "away_epa",
            "home_plays", "away_plays", "played", "home_qb_id", "away_qb_id", "roof",
            "stadium_id", "location", "wind"]}
    months = g["gameday"].dt.month.to_numpy()
    days = g["gameday"].to_numpy()

    i = 0
    while i < n:
        j = i
        while j < n and days[j] == days[i]:
            j += 1
        season = col["season"][i]
        if season != cur_season:
            for r in R.values():
                r.new_season()
            for key, w in wind_pending:      # release last season's wind observations
                a = wind_sum.setdefault(key, [0.0, 0])
                a[0] += w
                a[1] += 1
                wind_all[0] += w
                wind_all[1] += 1
            wind_pending = []
            cur_season = season

        # ---- features for all games of this day (state from earlier days only)
        day_qbs = []
        for x in range(i, j):
            h, a = col["home"][x], col["away"][x]
            hq, aq = col["home_qb_id"][x], col["away_qb_id"][x]
            known = not (_nan(hq) or _nan(aq))
            hq = last_qb.get(h) if _nan(hq) else hq     # unknown starter -> last starter
            aq = last_qb.get(a) if _nan(aq) else aq
            day_qbs.append((hq, aq))
            out["qb_known"][x] = float(known)
            for m, r in R.items():
                oh, dh_opp = r.parts(h, a, hq)          # home offence, away defence
                oa, da_opp = r.parts(a, h, aq)
                tag = "pace" if m == "plays" else f"off_{m}"
                out[f"mu_{m}"][x] = r.mu
                out[tag][x] = oh + oa
                if m != "plays":
                    out[f"def_{m}"][x] = dh_opp + da_opp
                else:
                    out[tag][x] += dh_opp + da_opp
                if m == "pts":
                    neutral = col["location"][x] == "Neutral"
                    hf = 0.0 if neutral else r.hfa
                    out["home_pts_hat"][x] = r.mu + oh + dh_opp + hf
                    out["away_pts_hat"][x] = r.mu + oa + da_opp - hf
                    # QB adjustment actually embedded in off_pts: today's starters vs last starters
                    out["qb_adj"][x] = (r.q(hq) - r.q(last_qb.get(h, hq))
                                        + r.q(aq) - r.q(last_qb.get(a, aq)))
            roof = col["roof"][x]
            if roof in ("outdoors", "open"):
                key = (col["stadium_id"][x], int(months[x]))
                s_, n_ = wind_sum.get(key, (0.0, 0))
                prior = wind_all[0] / wind_all[1] if wind_all[1] else WIND_PRIOR
                out["clim_wind"][x] = (s_ + WIND_SHRINK * prior) / (n_ + WIND_SHRINK)

        # ---- update state with this day's results
        deltas = []
        for x in range(i, j):
            if not col["played"][x]:
                continue
            h, a = col["home"][x], col["away"][x]
            hq, aq = day_qbs[x - i]
            hs = 0.0 if col["location"][x] == "Neutral" else 1.0
            vals = {"pts": (col["home_score"][x], col["away_score"][x]),
                    "epa": (col["home_epa"][x], col["away_epa"][x]),
                    "plays": (col["home_plays"][x], col["away_plays"][x])}
            for m, r in R.items():
                vh, va = vals[m]
                if _nan(float(vh)) or _nan(float(va)):
                    continue
                eh = r.residual(h, a, hq, vh, hs)
                ea = r.residual(a, h, aq, va, -hs)
                deltas.append((m, h, a, hq, aq, eh, ea, vh, va, hs))
            if hq is not None:
                last_qb[h] = hq
            if aq is not None:
                last_qb[a] = aq
            w = col["wind"][x]
            if col["roof"][x] in ("outdoors", "open") and not _nan(float(w)):
                wind_pending.append(((col["stadium_id"][x], int(months[x])), float(w)))
        for m, h, a, hq, aq, eh, ea, vh, va, hs in deltas:
            r = R[m]
            k, kq, km = r.p["k"], r.p["kq"], r.p["km"]
            r.O[h] = r.O.get(h, 0.0) + k * eh
            r.D[a] = r.D.get(a, 0.0) + k * eh
            r.O[a] = r.O.get(a, 0.0) + k * ea
            r.D[h] = r.D.get(h, 0.0) + k * ea
            if kq > 0:
                if hq is not None:
                    r.Q[hq] = r.Q.get(hq, r.p["q0"]) + kq * eh
                if aq is not None:
                    r.Q[aq] = r.Q.get(aq, r.p["q0"]) + kq * ea
            r.mu += km * ((vh - r.mu) + (va - r.mu))
            if hs:
                r.hfa += 0.002 * ((vh - va) / 2.0 - r.hfa)
        i = j

    f = g[["game_id", "season", "week", "game_type", "gameday", "played", "div_game"]].copy()
    for k, v in out.items():
        f[k] = v
    roof = g["roof"].fillna("outdoors")
    f["indoor"] = roof.isin(["dome", "closed"]).astype(float)
    outdoor = 1.0 - f["indoor"]
    month = g["gameday"].dt.month
    f["late_outdoor"] = outdoor * month.isin([12, 1, 2]).astype(float)   # tested, not used
    f["clim_wind_x"] = (f["clim_wind"].fillna(WIND_PRIOR) - WIND_PRIOR) * outdoor
    f["playoff"] = (g["game_type"] != "REG").astype(float)
    f["div_game"] = f["div_game"].fillna(0).astype(float)
    f["base_pts"] = 2.0 * f["mu_pts"]
    f["struct_total"] = f["home_pts_hat"] + f["away_pts_hat"]   # ratings-only prediction
    f["actual_total"] = np.where(g["played"], g["home_score"] + g["away_score"], np.nan)
    return f


# ------------------------------------------------------------------ linear layer
def _fit(X: np.ndarray, y: np.ndarray, ridge: float = 0.0) -> np.ndarray:
    mu, sd = X.mean(0), X.std(0)
    sd[sd == 0] = 1.0
    Z = (X - mu) / sd
    A = Z.T @ Z + ridge * len(y) * np.eye(Z.shape[1])
    b = np.linalg.solve(A, Z.T @ (y - y.mean()))
    coef = b / sd
    return np.concatenate([[y.mean() - mu @ coef], coef])


def walk_forward_predict(feat: pd.DataFrame, features=None, ridge: float | None = None,
                         first_season: int = FIRST_PRED_SEASON, return_coefs: bool = False):
    """Predict each season S with a linear model fit on played games of seasons
    FIT_START..S-1.  Returns a Series aligned to feat.index (NaN before first_season)."""
    features = features or FEATURES
    ridge = RIDGE if ridge is None else ridge
    pred = pd.Series(np.nan, index=feat.index)
    coefs = {}
    y = feat["actual_total"].to_numpy()
    X = feat[features].to_numpy(dtype=float)
    ok = feat["played"].to_numpy() & np.isfinite(X).all(1)
    seasons = feat["season"].to_numpy()
    for s in sorted(set(seasons[seasons >= first_season])):
        tr = ok & (seasons >= FIT_START) & (seasons < s)
        c = _fit(X[tr], y[tr], ridge)
        te = seasons == s
        pred[te] = c[0] + X[te] @ c[1:]
        coefs[int(s)] = c
    return (pred, coefs) if return_coefs else pred


def predict_totals(games: pd.DataFrame, team_stats: pd.DataFrame) -> pd.DataFrame:
    """One row per game_id for every game from 2003 on (played or not).

    pred_total uses only information available before that game's kickoff day."""
    feat = build_features(games, team_stats)
    feat["pred_total"] = walk_forward_predict(feat)
    cols = ["game_id", "season", "week", "pred_total", "struct_total", "base_pts", "off_pts",
            "def_pts", "off_epa", "def_epa", "pace", "qb_adj", "qb_known", "indoor",
            "clim_wind", "clim_wind_x", "div_game"]
    out = feat.loc[feat["season"] >= FIRST_PRED_SEASON, cols]
    # return in the caller's row order
    order = {gid: k for k, gid in enumerate(games["game_id"])}
    return out.iloc[np.argsort(out["game_id"].map(order).to_numpy(), kind="stable")].reset_index(drop=True)


def over_probability(pred_total, line, sd: float = RESID_SD):
    """Normal-approximation probability that the total exceeds `line`."""
    z = (np.asarray(pred_total, dtype=float) - np.asarray(line, dtype=float)) / (sd * math.sqrt(2))
    return 0.5 * (1.0 + np.vectorize(math.erf)(z))

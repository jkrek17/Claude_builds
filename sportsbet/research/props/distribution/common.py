"""Shared machinery for the distribution / calibration research (numpy + pandas only).

Nothing here edits the package: it re-implements the baseline stage 1 / stage 2 in vectorised form
(checked against research/props/backtest.py) and adds alternative mean models, distribution families
and scoring. All tests are walk-forward: fit on seasons < s, score season s.
"""
from __future__ import annotations

import math
import os
import pickle
import tempfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

from nflmodel import props as P

L = P.QUANTILES                      # 0.005 .. 0.995
NQ = len(L)
DATA = Path.home() / ".cache" / "nflmodel"
CACHE = Path(os.environ.get("PROPS_DIST_CACHE", os.path.join(tempfile.gettempdir(), "nfl_props_dist_cache")))
FIRST, LAST = 2012, 2026
ADOT_PRIOR = {"WR": 11.0, "TE": 7.9, "RB": 0.9, "QB": 0.0}   # league air yards per target, 2006-2011
ADOT_K = 15.0                                                 # targets of prior mixed in


# ----------------------------------------------------------------------------- panel

def get_panel() -> pd.DataFrame:
    """Baseline panel plus pre-game extras used only by the spread research (air-yard depth, own volatility)."""
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / "panel.pkl"
    if f.exists():
        return pd.read_pickle(f)
    games = pd.read_csv(DATA / "games.csv")
    panel = P.build_panel(P.load_offense(str(DATA)), games)
    parts = []
    for fn in sorted(DATA.glob("stats_player_week_*.csv")):
        if int(fn.name[-8:-4]) < 2006:
            continue
        parts.append(pd.read_csv(fn, usecols=["player_id", "game_id", "receiving_air_yards", "season_type"],
                                 low_memory=False))
    ex = pd.concat(parts, ignore_index=True).drop_duplicates(["player_id", "game_id"])
    ex["receiving_air_yards"] = pd.to_numeric(ex["receiving_air_yards"], errors="coerce").fillna(0.0)
    panel = panel.merge(ex, on=["player_id", "game_id"], how="left")
    d = panel.sort_values(["player_id", "gameday", "game_id"], kind="mergesort")
    keys = d["player_id"].to_numpy()
    st = d["starter"].to_numpy()
    cols = [d["receiving_air_yards"].to_numpy(float), d["targets"].to_numpy(float), np.ones(len(d)),
            d["receiving_yards"].to_numpy(float), d["receiving_yards"].to_numpy(float) ** 2,
            d["rushing_yards"].to_numpy(float), d["rushing_yards"].to_numpy(float) ** 2,
            d["passing_yards"].to_numpy(float) * st, (d["passing_yards"].to_numpy(float) * st) ** 2, st,
            d["receptions"].to_numpy(float), d["rushing_tds"].to_numpy(float), d["receiving_tds"].to_numpy(float)]
    s = P._decayed(keys, np.column_stack(cols), P.SLOW)
    prior = d["position"].map(ADOT_PRIOR).to_numpy(float)
    d["adot_slow"] = (s[:, 0] + ADOT_K * prior) / (s[:, 1] + ADOT_K)
    d["n_eff"] = s[:, 2]
    for name, a, b, n in (("rec", 3, 4, 2), ("rush", 5, 6, 2), ("pass", 7, 8, 9)):
        nn = np.maximum(s[:, n], 1e-9)
        m = s[:, a] / nn
        v = np.maximum(s[:, b] / nn - m ** 2, 0.0)
        cv = np.sqrt(v) / np.maximum(m, 1.0)
        d[f"cv_{name}"] = np.where(s[:, n] >= 4.0, cv, np.nan)      # needs ~4+ effective games
    d["catch_slow"] = (s[:, 10] + 20 * 0.65) / (s[:, 1] + 20)
    d["rush_tds_slow"] = s[:, 11] / (s[:, 2] + P.SHRINK)
    d["rec_tds_slow"] = s[:, 12] / (s[:, 2] + P.SHRINK)
    panel = d.sort_values(["gameday", "game_id", "player_id"], kind="mergesort").reset_index(drop=True)
    panel["early"] = (panel["week"] <= 4).astype(float)
    panel.to_pickle(f)
    return panel


# ----------------------------------------------------------------------------- small numerics

def lgam(x):
    """log Gamma for x > 0, vectorised (Stirling after shifting by 8)."""
    x = np.asarray(x, float)
    s = np.zeros_like(x)
    for i in range(8):
        s += np.log(x + i)
    z = x + 8.0
    return (z - 0.5) * np.log(z) - z + 0.9189385332046727 + 1 / (12 * z) - 1 / (360 * z ** 3) + 1 / (1260 * z ** 5) - s


def norm_cdf(z):
    z = np.asarray(z, float)
    t = 1.0 / (1.0 + 0.2316419 * np.abs(z))
    poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
    c = 1.0 - np.exp(-0.5 * z * z) / math.sqrt(2 * math.pi) * poly
    return np.where(z >= 0, c, 1.0 - c)


def norm_ppf(p):
    """Acklam's rational approximation (abs error < 1.2e-9)."""
    p = np.asarray(p, float)
    a = [-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
         -3.066479806614716e+01, 2.506628277459239e+00]
    b = [-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
         -1.328068155288572e+01]
    c = [-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
         4.374664141464968e+00, 2.938163982698783e+00]
    d = [7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00]
    out = np.empty_like(p)
    lo, hi = p < 0.02425, p > 1 - 0.02425
    mid = ~(lo | hi)
    q = np.sqrt(-2 * np.log(np.where(lo, p, 0.5)))
    out[lo] = ((((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
               ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1))[lo]
    q = np.sqrt(-2 * np.log(np.where(hi, 1 - p, 0.5)))
    out[hi] = -((((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1))[hi]
    q = np.where(mid, p, 0.5) - 0.5
    r = q * q
    out[mid] = ((((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1))[mid]
    return out


def nelder_mead(f, x0, step=0.3, iters=400, tol=1e-7):
    x0 = np.asarray(x0, float)
    n = len(x0)
    sim = np.vstack([x0] + [x0 + step * np.eye(n)[i] for i in range(n)])
    fs = np.array([f(x) for x in sim])
    for _ in range(iters):
        o = np.argsort(fs)
        sim, fs = sim[o], fs[o]
        if abs(fs[-1] - fs[0]) < tol * (abs(fs[0]) + 1e-12):
            break
        c = sim[:-1].mean(0)
        xr = c + (c - sim[-1])
        fr = f(xr)
        if fr < fs[0]:
            xe = c + 2 * (c - sim[-1])
            fe = f(xe)
            sim[-1], fs[-1] = (xe, fe) if fe < fr else (xr, fr)
        elif fr < fs[-2]:
            sim[-1], fs[-1] = xr, fr
        else:
            xc = c + 0.5 * (sim[-1] - c)
            fc = f(xc)
            if fc < fs[-1]:
                sim[-1], fs[-1] = xc, fc
            else:
                sim[1:] = sim[0] + 0.5 * (sim[1:] - sim[0])
                fs[1:] = [f(x) for x in sim[1:]]
    o = np.argmin(fs)
    return sim[o], fs[o]


def sigmoid(z):
    return 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))


def logistic_fit(x, y, lam=1.0, iters=30, w=None):
    """Ridge-penalised logistic regression by Newton steps. x gets an intercept added (unpenalised)."""
    X = np.column_stack([np.ones(len(x)), x])
    b = np.zeros(X.shape[1])
    pen = lam * np.eye(X.shape[1])
    pen[0, 0] = 0.0
    w = np.ones(len(y)) if w is None else w
    for _ in range(iters):
        p = sigmoid(X @ b)
        g = X.T @ (w * (y - p)) - pen @ b
        H = (X * (w * p * (1 - p))[:, None]).T @ X + pen
        step = np.linalg.solve(H, g)
        b += step
        if np.max(np.abs(step)) < 1e-8:
            break
    return b


# ----------------------------------------------------------------------------- stage 1 (expected value)

class Ridge:
    """The package's stage 1: ridge on standardised features, identity link, floored."""

    def __init__(self, lam=P.RIDGE):
        self.lam = lam

    def fit(self, x, y, floor):
        self.m, self.s = x.mean(0), x.std(0) + 1e-9
        z = (x - self.m) / self.s
        self.b = np.linalg.solve(z.T @ z + self.lam * np.eye(z.shape[1]), z.T @ (y - y.mean()))
        self.c, self.floor = y.mean(), floor
        return self

    def predict(self, x):
        return np.maximum(self.c + ((x - self.m) / self.s) @ self.b, self.floor)


class LogGLM:
    """Poisson-score (quasi-likelihood) GLM with log link, ridge penalty, by IRLS.

    ``logx``: features that are non-negative volumes are replaced by log(x + 0.25 * mean(x)) so the
    model is multiplicative in volumes (an elasticity model) rather than exponential in them.
    """

    def __init__(self, lam=P.RIDGE, logx=True, iters=40):
        self.lam, self.logx, self.iters = lam, logx, iters

    def _tx(self, x):
        if not self.logx:
            return x
        x = x.copy()
        x[:, self.pos] = np.log(np.maximum(x[:, self.pos], 0.0) + self.off)
        return x

    def fit(self, x, y, floor):
        if self.logx:
            self.pos = (x.min(0) >= 0) & (x.max(0) > 1.5) & (x.mean(0) > 0)
            self.off = 0.25 * x[:, self.pos].mean(0)
        x = self._tx(x)
        self.m, self.s = x.mean(0), x.std(0) + 1e-9
        Z = np.column_stack([np.ones(len(x)), (x - self.m) / self.s])
        pen = self.lam * np.eye(Z.shape[1])
        pen[0, 0] = 0.0
        yy = np.maximum(y, 0.0)                       # the handful of negative yardage games count as 0 for the fit
        b = np.zeros(Z.shape[1])
        b[0] = math.log(max(yy.mean(), 1e-6))
        for _ in range(self.iters):
            eta = np.clip(Z @ b, -12, 12)
            mu = np.exp(eta)
            g = Z.T @ (yy - mu) - pen @ b
            H = (Z * mu[:, None]).T @ Z + pen
            step = np.linalg.solve(H, g)
            b += step
            if np.max(np.abs(step)) < 1e-9:
                break
        self.b, self.floor = b, floor
        self.scale = y.mean() / np.exp(np.clip(Z @ b, -12, 12)).mean()   # exact mean match (negatives)
        return self

    def predict(self, x):
        x = self._tx(x)
        Z = np.column_stack([np.ones(len(x)), (x - self.m) / self.s])
        return np.maximum(self.scale * np.exp(np.clip(Z @ self.b, -12, 12)), self.floor)


@dataclass
class Fold:
    season: int
    tr: pd.DataFrame
    te: pd.DataFrame
    mu_tr: np.ndarray
    mu_te: np.ndarray
    y_tr: np.ndarray
    y_te: np.ndarray


def make_folds(panel, prop, mean=Ridge, first=FIRST, last=LAST, feats=None, target=None, window=None, **kw):
    """Walk-forward folds for one prop: per-position stage 1 fitted on seasons < s (in-sample mu on train)."""
    feats = feats or P.FEATURES[prop.key]
    target = target or prop.target
    el = panel[P.eligible(panel, prop)]
    seasons = el["season"].to_numpy()
    folds = []
    for s in range(first, last + 1):
        tr, te = el[(seasons < s) & (seasons >= s - (window or 99))], el[seasons == s]
        if te.empty:
            continue
        mu_tr, mu_te = np.full(len(tr), np.nan), np.full(len(te), np.nan)
        floor = 0.02 if prop.kind == "count" else 1.0
        for pos in prop.positions:
            a, b = (tr["position"] == pos).to_numpy(), (te["position"] == pos).to_numpy()
            m = mean(**kw).fit(tr.loc[a, feats].to_numpy(float), tr.loc[a, target].to_numpy(float), floor)
            mu_tr[a] = m.predict(tr.loc[a, feats].to_numpy(float))
            if b.any():
                mu_te[b] = m.predict(te.loc[b, feats].to_numpy(float))
        folds.append(Fold(s, tr, te, mu_tr, mu_te, tr[target].to_numpy(float), te[target].to_numpy(float)))
    return folds


# ----------------------------------------------------------------------------- distributions: quantile form

def cdf_at(Q, x, left=False):
    """F(x) for each row of a quantile matrix (levels L); ``left`` gives F(x-) at ties."""
    n = Q.shape[0]
    x = np.broadcast_to(np.asarray(x, float), (n,))
    r = np.arange(n)
    nle = (Q <= x[:, None]).sum(1)
    i0, i1 = np.clip(nle - 1, 0, NQ - 1), np.clip(nle, 0, NQ - 1)
    q0, q1 = Q[r, i0], Q[r, i1]
    F = L[i0] + (x - q0) / np.where(q1 > q0, q1 - q0, 1.0) * (L[i1] - L[i0])
    F = np.where(nle == 0, 0.0, np.where(nle == NQ, 1.0, F))
    if not left:
        return F
    nlt = (Q < x[:, None]).sum(1)
    Flo = np.where(nlt > 0, L[np.clip(nlt - 1, 0, NQ - 1)], 0.0)
    return np.where(nle > nlt, np.minimum(Flo, F), F)


def crps_q(Q, y):
    d = y[:, None] - Q
    return 2 * np.mean(np.where(d >= 0, L * d, (L - 1) * d), axis=1)


def pmf_to_q(pmf):
    """Integer-valued quantiles (at levels L) of count pmfs."""
    cdf = np.cumsum(pmf, axis=1)
    return (cdf[:, None, :] < L[None, :, None] - 1e-12).sum(2).astype(float)


class EmpRatio:
    """Baseline yardage spread: empirical quantiles of actual/expected in expected-value bins, blended.

    ``split``: optional column; the ratio table is then built separately for the terciles of that
    column inside each expected-value bin (a direct test of 'does the spread depend on X').
    """

    def __init__(self, nbins=P.N_BINS, split=None, nsplit=3, bypos=True):
        self.nbins, self.split, self.nsplit, self.bypos = nbins, split, nsplit, bypos

    def _table(self, mu, y):
        edges = np.quantile(mu, np.linspace(0, 1, self.nbins + 1)[1:-1])
        which = np.searchsorted(edges, mu)
        used = np.unique(which)                       # tied expected values can leave a bin empty
        centers = np.array([mu[which == b].mean() for b in used])
        ratios = np.vstack([np.quantile(y[which == b] / mu[which == b], L) for b in used])
        return centers, ratios

    def fit(self, tr, mu, y):
        self.m = {}
        pos = tr["position"].to_numpy() if self.bypos else np.full(len(tr), "ALL")
        for p in np.unique(pos):
            a = pos == p
            if self.split is None:
                self.m[p] = self._table(mu[a], y[a])
            else:
                v = tr[self.split].to_numpy(float)[a]
                ok = ~np.isnan(v)
                cuts = np.unique(np.quantile(v[ok], np.linspace(0, 1, self.nsplit + 1)[1:-1]))
                g = np.where(ok, np.searchsorted(cuts, v), -1)
                self.m[p] = {"cuts": cuts, "all": self._table(mu[a], y[a]),
                             **{k: self._table(mu[a][g == k], y[a][g == k]) for k in range(len(cuts) + 1)
                                if (g == k).sum() >= 20 * self.nbins}}
        return self

    @staticmethod
    def _apply(tab, mu):
        c, ratios = tab
        j = np.clip(np.searchsorted(c, mu), 1, len(c) - 1)
        w = np.clip((mu - c[j - 1]) / (c[j] - c[j - 1] + 1e-9), 0.0, 1.0)[:, None]
        return mu[:, None] * ((1 - w) * ratios[j - 1] + w * ratios[j])

    def predict(self, te, mu):
        Q = np.zeros((len(te), NQ))
        pos = te["position"].to_numpy() if self.bypos else np.full(len(te), "ALL")
        for p in np.unique(pos):
            a = pos == p
            if self.split is None:
                Q[a] = self._apply(self.m[p], mu[a])
            else:
                m = self.m[p]
                v = te[self.split].to_numpy(float)[a]
                g = np.where(np.isnan(v), -1, np.searchsorted(m["cuts"], v))
                q = self._apply(m["all"], mu[a])
                for k in range(len(m["cuts"]) + 1):
                    if k in m and (g == k).any():
                        q[g == k] = self._apply(m[k], mu[a][g == k])
                Q[a] = q
        return Q


# ----------------------------------------------------------------------------- distributions: counts

def nb_pmf(mu, alpha, K):
    """Rows of negative binomial pmfs on 0..K; mean mu, variance mu + alpha mu^2 (alpha may be a vector)."""
    mu = np.maximum(np.asarray(mu, float), 1e-6)[:, None]
    alpha = np.broadcast_to(np.asarray(alpha, float), (mu.shape[0],))[:, None]
    k = np.arange(K + 1)[None, :]
    r = 1.0 / np.maximum(alpha, 1e-6)
    lp_nb = lgam(k + r) - lgam(k + 1.0) - lgam(r) + r * np.log(r / (r + mu)) + k * np.log(mu / (r + mu))
    lp_po = k * np.log(mu) - mu - lgam(k + 1.0)
    p = np.exp(np.where(alpha < 1e-6, lp_po, lp_nb))
    return p / p.sum(1, keepdims=True)


def nb_ll(y, mu, alpha):
    mu = np.maximum(mu, 1e-6)
    if np.ndim(alpha) == 0 and alpha < 1e-6:
        return y * np.log(mu) - mu - lgam(y + 1.0)
    r = 1.0 / np.maximum(alpha, 1e-6)
    return lgam(y + r) - lgam(y + 1.0) - lgam(r) + r * np.log(r / (r + mu)) + y * np.log(mu / (r + mu))


def crps_pmf(pmf, y):
    cdf = np.cumsum(pmf, axis=1)
    return np.sum((cdf - (np.arange(pmf.shape[1])[None, :] >= y[:, None])) ** 2, axis=1)


class NBGrid:
    """Baseline count spread: one alpha per position by ML on the package's grid."""
    grid = (0.0, 0.02, 0.05, 0.08, 0.12, 0.17, 0.23, 0.3, 0.4, 0.55, 0.75, 1.0, 1.4, 2.0)

    def __init__(self, K=40):
        self.K = K

    def fit(self, tr, mu, y):
        self.a = {}
        for p in np.unique(tr["position"]):
            s = (tr["position"] == p).to_numpy()
            self.a[p] = max(self.grid, key=lambda a: nb_ll(y[s], mu[s], a).sum())
        return self

    def predict(self, te, mu):
        alpha = te["position"].map(self.a).to_numpy(float)
        return nb_pmf(mu, alpha, self.K)


# ----------------------------------------------------------------------------- scoring

def score_q(fold_or_te, Q, y, mu, lines=(), bins=None, seed=0):
    """Per-row scores for quantile-form forecasts."""
    te = fold_or_te
    rng = np.random.default_rng(seed + int(te["season"].iloc[0]))
    F, Fl = cdf_at(Q, y), cdf_at(Q, y, left=True)
    out = pd.DataFrame({"season": te["season"].to_numpy(), "week": te["week"].to_numpy(),
                        "pos": te["position"].to_numpy(), "mu": mu, "y": y, "crps": crps_q(Q, y),
                        "pit": Fl + rng.random(len(y)) * (F - Fl), "med": Q[:, NQ // 2]})
    for ln in lines:
        out[f"p>{ln}"] = 1.0 - cdf_at(Q, ln)
    if bins is not None:                               # binned log score (probability of the outcome's bin)
        Fe = np.column_stack([cdf_at(Q, e) for e in bins])
        Fe = np.column_stack([np.zeros(len(y)), Fe, np.ones(len(y))])
        k = np.searchsorted(bins, y, side="left")      # bin k = (bins[k-1], bins[k]]
        r = np.arange(len(y))
        out["logs"] = -np.log(np.maximum(Fe[r, k + 1] - Fe[r, k], 1e-4))
    return out


def score_pmf(te, pmf, y, mu, lines=(), seed=0):
    rng = np.random.default_rng(seed + int(te["season"].iloc[0]))
    cdf = np.cumsum(pmf, axis=1)
    yi = np.clip(y.astype(int), 0, pmf.shape[1] - 1)
    r = np.arange(len(y))
    lo = np.where(yi >= 1, cdf[r, np.maximum(yi - 1, 0)], 0.0)
    hi = cdf[r, yi]
    out = pd.DataFrame({"season": te["season"].to_numpy(), "week": te["week"].to_numpy(),
                        "pos": te["position"].to_numpy(), "mu": mu, "y": y, "crps": crps_pmf(pmf, y),
                        "logs": -np.log(np.maximum(pmf[r, yi], 1e-4)),
                        "pit": lo + rng.random(len(y)) * (hi - lo), "midpit": lo + 0.5 * (hi - lo)})
    for ln in lines:
        out[f"p>{ln}"] = 1.0 - cdf[:, int(math.floor(ln))]
    return out


def run_dist(folds, dist, kind, lines=(), bins=None, first=FIRST):
    """Fit ``dist`` on each fold's train rows and score its test rows. Returns per-row scores."""
    out = []
    for f in folds:
        d = dist.fit(f.tr, f.mu_tr, f.y_tr)
        pred = d.predict(f.te, f.mu_te)
        if kind == "q":
            out.append(score_q(f.te, pred, f.y_te, f.mu_te, lines, bins))
        else:
            out.append(score_pmf(f.te, pred, f.y_te, f.mu_te, lines))
    r = pd.concat(out, ignore_index=True)
    return r[r["season"] >= first].reset_index(drop=True)


def summary(r):
    pit = r["pit"].to_numpy()
    d = {"n": len(r), "crps": r["crps"].mean(), "in50": np.mean((pit > .25) & (pit < .75)),
         "in80": np.mean((pit > .1) & (pit < .9)), ">p50": np.mean(pit > .5), ">p75": np.mean(pit > .75),
         ">p90": np.mean(pit > .9), "<p10": np.mean(pit < .1), "<p25": np.mean(pit < .25)}
    if "logs" in r:
        d["logs"] = r["logs"].mean()
    return d


def compare(base, new, col="crps"):
    """Pooled relative change (%; negative = better) and seasons improved."""
    a, b = base.groupby("season")[col].mean(), new.groupby("season")[col].mean()
    return 100 * (new[col].mean() / base[col].mean() - 1), int((b < a - 1e-12).sum()), len(a)


def line_fmt(name, r, base=None):
    s = summary(r)
    txt = (f"{name:<34}{s['crps']:>9.4f}" + (f"{s['logs']:>8.4f}" if "logs" in s else f"{'':>8}")
           + f"{s['in50']:>7.3f}{s['in80']:>7.3f}{s['<p10']:>7.3f}{s['<p25']:>7.3f}{s['>p50']:>7.3f}{s['>p75']:>7.3f}{s['>p90']:>7.3f}")
    if base is not None:
        c, w, n = compare(base, r)
        txt += f"{c:>8.2f}% {w:>2}/{n}"
        if "logs" in r and "logs" in base:
            c, w, n = compare(base, r, "logs")
            txt += f"  logs{c:>7.2f}% {w:>2}/{n}"
    return txt


HDR = (f"{'variant':<34}{'CRPS':>9}{'logs':>8}{'in50':>7}{'in80':>7}{'<p10':>7}{'<p25':>7}{'>p50':>7}{'>p75':>7}{'>p90':>7}"
       f"{'dCRPS':>9} {'won':>5}")


def reliability(r, col, thr, groups=None):
    """Mean forecast P(y > thr) vs observed frequency, pooled or by group label array."""
    p, o = r[col].to_numpy(), (r["y"].to_numpy() > thr).astype(float)
    if groups is None:
        return p.mean(), o.mean(), len(p)
    return {g: (p[groups == g].mean(), o[groups == g].mean(), int((groups == g).sum())) for g in pd.unique(groups)}


def rel_bins(p, o, edges=(0, .05, .1, .2, .3, .4, .5, .6, .7, .8, .9, 1.0001)):
    rows = []
    for lo, hi in zip(edges[:-1], edges[1:]):
        s = (p >= lo) & (p < hi)
        if s.sum() >= 30:
            se = math.sqrt(max(o[s].mean() * (1 - o[s].mean()), 1e-9) / s.sum())
            rows.append((lo, hi, int(s.sum()), p[s].mean(), o[s].mean(), (o[s].mean() - p[s].mean()) / se))
    return rows


def print_rel(title, p, o, edges=(0, .05, .1, .2, .3, .4, .5, .6, .7, .8, .9, 1.0001)):
    print(f"  {title}: Brier {np.mean((p - o) ** 2):.5f}  logloss {-np.mean(o * np.log(np.clip(p, 1e-6, 1)) + (1 - o) * np.log(np.clip(1 - p, 1e-6, 1))):.5f}")
    for lo, hi, n, pm, om, z in rel_bins(p, o, edges):
        print(f"    {lo:.2f}-{min(hi, 1):.2f} n={n:>6} forecast {pm:.3f} observed {om:.3f}  z={z:+.1f}")

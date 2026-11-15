"""Fast walk-forward harness for the usage/roles research.

Same model as nflmodel.props.PropModel (ridge per prop and position, ratio-quantile spread for
yardage, negative binomial for counts) and the same scores as research/props/backtest.py, but
vectorised, with the eligible rows FROZEN from the baseline panel so that every variant is scored on
exactly the same player-games. `python lib.py` checks it reproduces backtest.py's pooled numbers.
"""
from __future__ import annotations

import math
import os
from pathlib import Path

import numpy as np
import pandas as pd

from nflmodel import props as P

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"
CACHE = Path.home() / ".cache" / "nflmodel"
Q = P.QUANTILES
ALPHAS = (0.0, 0.02, 0.05, 0.08, 0.12, 0.17, 0.23, 0.3, 0.4, 0.55, 0.75, 1.0, 1.4, 2.0)
_LG = np.array([math.lgamma(i + 1) for i in range(400)])


def load_offense(extra_cols=()):
    """P.load_offense plus extra raw columns (kept numeric, NaN -> 0) and season_type."""
    raw = list(P._RAW)
    P._RAW = raw + [c for c in extra_cols if c not in raw]
    try:
        d = P.load_offense(str(CACHE))
    finally:
        P._RAW = raw
    return d


def base_panel(extra_cols=()) -> pd.DataFrame:
    games = pd.read_csv(CACHE / "games.csv")
    d = P.build_panel(load_offense(extra_cols), games)
    return freeze(d)


def freeze(d: pd.DataFrame) -> pd.DataFrame:
    """Store the baseline eligibility of every prop so later feature changes cannot move the test set."""
    for prop in P.PROPS:
        d[f"elig_{prop.key}"] = P.eligible(d, prop)
    return d


def _alpha(y, mu):
    yi = y.astype(int)
    best, best_ll = 0.0, -np.inf
    for a in ALPHAS:
        if a == 0.0:
            ll = np.sum(yi * np.log(mu) - mu - _LG[yi])
        else:
            r = 1.0 / a
            tab = np.array([math.lgamma(i + r) for i in range(int(yi.max()) + 1)])
            ll = np.sum(tab[yi] - _LG[yi] - math.lgamma(r) + r * np.log(r / (r + mu)) + yi * np.log(mu / (r + mu)))
        if ll > best_ll:
            best, best_ll = a, ll
    return best


def _nb_pmf(mu, alpha, kmax=40):
    k = np.arange(kmax + 1)[None, :]
    mu = np.maximum(mu, 1e-6)[:, None]
    if alpha < 1e-6:
        logp = k * np.log(mu) - mu - _LG[: kmax + 1][None, :]
    else:
        r = 1.0 / alpha
        t = np.array([math.lgamma(i + r) - math.lgamma(i + 1) for i in range(kmax + 1)])[None, :]
        logp = t - math.lgamma(r) + r * np.log(r / (r + mu)) + k * np.log(mu / (r + mu))
    p = np.exp(logp)
    return p / p.sum(1, keepdims=True)


def fit_predict(prop: P.Prop, feats, xtr, ytr, xte, yte, ridge=P.RIDGE):
    """Fit one position on train arrays; return (mu_test, crps_test)."""
    mean, sd = xtr.mean(0), xtr.std(0) + 1e-9
    z = (xtr - mean) / sd
    beta = np.linalg.solve(z.T @ z + ridge * np.eye(z.shape[1]), z.T @ (ytr - ytr.mean()))
    floor = 0.02 if prop.kind == "count" else 1.0
    mu = np.maximum(ytr.mean() + z @ beta, floor)
    mte = np.maximum(ytr.mean() + ((xte - mean) / sd) @ beta, floor)
    if prop.kind == "count":
        a = _alpha(ytr, mu)
        p = _nb_pmf(mte, a)
        cdf = np.cumsum(p, 1)
        ind = (np.arange(p.shape[1])[None, :] >= yte[:, None])
        crps = np.sum((cdf - ind) ** 2, 1)
    else:
        edges = np.quantile(mu, np.linspace(0, 1, P.N_BINS + 1)[1:-1])
        which = np.searchsorted(edges, mu)
        centers = np.array([mu[which == b].mean() for b in range(P.N_BINS)])
        ratios = np.vstack([np.quantile(ytr[which == b] / mu[which == b], Q) for b in range(P.N_BINS)])
        j = np.clip(np.searchsorted(centers, mte), 1, len(centers) - 1)
        w = np.clip((mte - centers[j - 1]) / (centers[j] - centers[j - 1] + 1e-9), 0.0, 1.0)[:, None]
        q = mte[:, None] * ((1 - w) * ratios[j - 1] + w * ratios[j])
        dlt = yte[:, None] - q
        crps = 2 * np.mean(np.where(dlt >= 0, Q * dlt, (Q - 1) * dlt), 1)
    return mte, crps


def evaluate(panel: pd.DataFrame, feats: dict | None = None, props=None, first=2012, last=None,
             train_first=None, ridge=P.RIDGE, elig: dict | None = None) -> pd.DataFrame:
    """Walk-forward scores, one row per eligible test player-game: prop, season, idx, y, mu, crps.

    feats: prop key -> feature list (defaults to P.FEATURES). elig: prop key -> boolean column name
    (defaults to the frozen baseline gate). train_first drops earlier seasons from every fit.
    """
    feats = {**P.FEATURES, **(feats or {})}
    last = int(panel["season"].max()) if last is None else last
    season = panel["season"].to_numpy()
    pos = panel["position"].to_numpy()
    out = []
    for prop in P.PROPS:
        if props is not None and prop.key not in props:
            continue
        ok = panel[(elig or {}).get(prop.key, f"elig_{prop.key}")].to_numpy(bool)
        x = panel[feats[prop.key]].to_numpy(float)
        if np.isnan(x[ok]).any():
            bad = [f for f, b in zip(feats[prop.key], np.isnan(x[ok]).any(0)) if b]
            raise ValueError(f"NaN in features {bad} for {prop.key}")
        y = panel[prop.target].to_numpy(float)
        for s in range(first, last + 1):
            for po in prop.positions:
                tr = ok & (pos == po) & (season < s)
                if train_first is not None:
                    tr &= season >= train_first
                te = ok & (pos == po) & (season == s)
                if not te.any():
                    continue
                mu, crps = fit_predict(prop, feats[prop.key], x[tr], y[tr], x[te], y[te], ridge)
                out.append(pd.DataFrame({"prop": prop.key, "season": s, "pos": po, "idx": np.flatnonzero(te),
                                         "y": y[te], "mu": mu, "crps": crps}))
    return pd.concat(out, ignore_index=True)


def summary(res: pd.DataFrame) -> pd.DataFrame:
    res = res.assign(ae=(res["y"] - res["mu"]).abs())
    return res.groupby("prop", sort=False).agg(n=("y", "size"), mae=("ae", "mean"), crps=("crps", "mean"))


def compare(base: pd.DataFrame, new: pd.DataFrame, label: str = "", mask=None, quiet=False) -> pd.DataFrame:
    """Pooled change (new - base; negative is better) per prop and seasons improved, on identical rows."""
    b = base.set_index(["prop", "idx"])
    n = new.set_index(["prop", "idx"])
    n = n.loc[n.index.intersection(b.index)]
    b = b.loc[n.index]
    d = pd.DataFrame({"season": b["season"], "crps_b": b["crps"], "crps_n": n["crps"],
                      "ae_b": (b["y"] - b["mu"]).abs(), "ae_n": (n["y"] - n["mu"]).abs()}).reset_index()
    if mask is not None:
        d = d[mask[d["idx"].to_numpy()]]
    rows = []
    for prop, g in d.groupby("prop", sort=False):
        ps = g.groupby("season")[["crps_b", "crps_n", "ae_b", "ae_n"]].mean()
        diff = (g["crps_n"] - g["crps_b"]).to_numpy()
        # season-block bootstrap-free z: mean of per-season diffs over their s.e.
        sd = ps["crps_n"] - ps["crps_b"]
        rows.append({"prop": prop, "n": len(g), "crps": g["crps_b"].mean(),
                     "dCRPS": diff.mean(), "dCRPS%": 100 * diff.mean() / g["crps_b"].mean(),
                     "dMAE": (g["ae_n"] - g["ae_b"]).mean(),
                     "dMAE%": 100 * (g["ae_n"] - g["ae_b"]).mean() / g["ae_b"].mean(),
                     "crps_seas": f"{int((ps['crps_n'] < ps['crps_b']).sum())}/{len(ps)}",
                     "mae_seas": f"{int((ps['ae_n'] < ps['ae_b']).sum())}/{len(ps)}",
                     "t": sd.mean() / (sd.std(ddof=1) / math.sqrt(len(sd)) + 1e-12)})
    out = pd.DataFrame(rows).set_index("prop")
    if not quiet:
        with pd.option_context("display.width", 200, "display.float_format", lambda v: f"{v:.4f}"):
            print(f"--- {label}")
            print(out.to_string())
    return out


def with_feats(extra: dict, drop: dict | None = None) -> dict:
    """P.FEATURES with `extra[prop]` appended (and `drop[prop]` removed)."""
    f = {}
    for k, v in P.FEATURES.items():
        v = [c for c in v if c not in (drop or {}).get(k, [])]
        f[k] = v + [c for c in extra.get(k, []) if c not in v]
    return f


if __name__ == "__main__":
    import time
    t = time.time()
    pan = base_panel()
    print("panel", pan.shape, f"{time.time() - t:.1f}s")
    t = time.time()
    res = evaluate(pan)
    print(summary(res).round(3), f"\n{time.time() - t:.1f}s")

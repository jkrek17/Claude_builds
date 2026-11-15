"""Fast walk-forward harness for the environment/opponent experiments.

Same model as nflmodel.props.PropModel (ridge per prop and position, ratio-quantile spread for yards,
negative binomial for counts) and the same scores as research/props/backtest.py, but vectorised and
taking the feature lists as an argument, so that many variants can be compared quickly.
`python harness.py` checks it against the package model on the baseline features.
"""
from __future__ import annotations

import math
import pickle
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from nflmodel import props as P

DATA = Path.home() / ".cache" / "nflmodel"
HERE = Path(__file__).resolve().parent
ALPHAS = (0.0, 0.02, 0.05, 0.08, 0.12, 0.17, 0.23, 0.3, 0.4, 0.55, 0.75, 1.0, 1.4, 2.0)
KMAX = 40
_LG = np.array([math.lgamma(i + 1) for i in range(400)])


def _lgam_int_plus(r: float, kmax: int) -> np.ndarray:
    return np.array([math.lgamma(i + r) for i in range(kmax + 1)])


def fit_alpha(y, mu):
    yi = y.astype(int)
    best, best_ll = 0.0, -np.inf
    for a in ALPHAS:
        if a == 0.0:
            ll = np.sum(yi * np.log(mu) - mu - _LG[yi])
        else:
            r = 1.0 / a
            t = _lgam_int_plus(r, int(yi.max()) + 1)
            ll = np.sum(t[yi] - _LG[yi] - math.lgamma(r) + r * np.log(r / (r + mu)) + yi * np.log(mu / (r + mu)))
        if ll > best_ll:
            best, best_ll = a, ll
    return best


def nb_pmf_mat(mu, alpha):
    k = np.arange(KMAX + 1)[None, :]
    mu = np.maximum(mu, 1e-6)[:, None]
    if alpha < 1e-6:
        logp = k * np.log(mu) - mu - _LG[: KMAX + 1][None, :]
    else:
        r = 1.0 / alpha
        logp = (_lgam_int_plus(r, KMAX)[None, :] - _LG[: KMAX + 1][None, :] - math.lgamma(r)
                + r * np.log(r / (r + mu)) + k * np.log(mu / (r + mu)))
    p = np.exp(logp)
    return p / p.sum(1, keepdims=True)


def fit_pos(x, y, kind, ridge=P.RIDGE):
    mean, sd = x.mean(0), x.std(0) + 1e-9
    z = (x - mean) / sd
    beta = np.linalg.solve(z.T @ z + ridge * np.eye(z.shape[1]), z.T @ (y - y.mean()))
    floor = 0.02 if kind == "count" else 1.0
    mu = np.maximum(y.mean() + z @ beta, floor)
    m = {"mean": mean, "sd": sd, "beta": beta, "icept": y.mean(), "floor": floor}
    if kind == "count":
        m["alpha"] = fit_alpha(y, mu)
    else:
        edges = np.quantile(mu, np.linspace(0, 1, P.N_BINS + 1)[1:-1])
        which = np.searchsorted(edges, mu)
        m["centers"] = np.array([mu[which == b].mean() for b in range(P.N_BINS)])
        m["ratios"] = np.vstack([np.quantile(y[which == b] / mu[which == b], P.QUANTILES)
                                 for b in range(P.N_BINS)])
    return m


def predict(m, x):
    return np.maximum(m["icept"] + ((x - m["mean"]) / m["sd"]) @ m["beta"], m["floor"])


def crps(m, kind, mu, y):
    if kind == "count":
        p = nb_pmf_mat(mu, m["alpha"])
        cdf = np.cumsum(p, 1)
        return np.sum((cdf - (np.arange(KMAX + 1)[None, :] >= y[:, None])) ** 2, 1)
    c = m["centers"]
    j = np.clip(np.searchsorted(c, mu), 1, len(c) - 1)
    w = np.clip((mu - c[j - 1]) / (c[j] - c[j - 1] + 1e-9), 0.0, 1.0)[:, None]
    q = mu[:, None] * ((1 - w) * m["ratios"][j - 1] + w * m["ratios"][j])
    d = y[:, None] - q
    return 2 * np.mean(np.where(d >= 0, P.QUANTILES * d, (P.QUANTILES - 1) * d), 1)


def evaluate(panel, features, props=None, first=2012, last=None, test_panel=None, ridge=P.RIDGE):
    """Walk forward. ``features``: prop key -> list of columns, or -> {position: list}.
    ``test_panel``: optional panel (same rows) whose features are used at test time only
    (to score a model trained on closing lines with degraded live inputs)."""
    last = last or int(panel["season"].max())
    tp = panel if test_panel is None else test_panel
    rows = []
    season = panel["season"].to_numpy()
    posa = panel["position"].to_numpy()
    for prop in P.PROPS:
        if props is not None and prop.key not in props:
            continue
        el = P.eligible(panel, prop)
        y_all = panel[prop.target].to_numpy(float)
        for pos in prop.positions:
            f = features[prop.key]
            f = f[pos] if isinstance(f, dict) else f
            sel = el & (posa == pos)
            X = panel.loc[sel, f].to_numpy(float)
            Xt = tp.loc[sel, f].to_numpy(float)
            y, s = y_all[sel], season[sel]
            for yr in range(first, last + 1):
                tr, te = s < yr, s == yr
                if not te.any():
                    continue
                m = fit_pos(X[tr], y[tr], prop.kind, ridge)
                mu = predict(m, Xt[te])
                rows.append({"season": yr, "prop": prop.key, "pos": pos, "n": int(te.sum()),
                             "ae": float(np.abs(y[te] - mu).sum()), "crps": float(crps(m, prop.kind, mu, y[te]).sum()),
                             "bias": float((mu - y[te]).sum())})
    r = pd.DataFrame(rows)
    return r


def by_season(r, by_pos=False):
    keys = ["prop", "pos", "season"] if by_pos else ["prop", "season"]
    g = r.groupby(keys)[["n", "ae", "crps"]].sum()
    g["mae"] = g["ae"] / g["n"]
    g["crps"] = g["crps"] / g["n"]
    return g


def compare(base, var, label="", by_pos=False, out=True):
    """Pooled % change in CRPS and MAE (negative = better) and seasons improved (CRPS / MAE)."""
    b, v = by_season(base, by_pos), by_season(var, by_pos)
    b = b.loc[v.index]
    lines = []
    keys = ["prop", "pos"] if by_pos else ["prop"]
    for k, vb in b.groupby(level=list(range(len(keys))), sort=False):
        vv = v.loc[vb.index]
        n = vb["n"].to_numpy()
        pc = lambda c: 100 * (np.average(vv[c], weights=n) / np.average(vb[c], weights=n) - 1)
        lines.append({"variant": label, "prop": k if isinstance(k, str) else "/".join(k),
                      "dCRPS%": round(pc("crps"), 3), "dMAE%": round(pc("mae"), 3),
                      "crps_wins": int((vv["crps"].to_numpy() < vb["crps"].to_numpy()).sum()),
                      "mae_wins": int((vv["mae"].to_numpy() < vb["mae"].to_numpy()).sum()), "of": len(vb)})
    df = pd.DataFrame(lines)
    if out:
        print(df.to_string(index=False))
        sys.stdout.flush()
    return df


def with_extra(extra: dict, base=None, drop=()):
    """Copy of the baseline feature lists with columns added (prop -> list) and ``drop`` removed everywhere."""
    base = base or P.FEATURES
    out = {}
    for k, f in base.items():
        f = f if not isinstance(f, dict) else None
        out[k] = [c for c in base[k] if c not in drop] + list(extra.get(k, []))
    return out


def base_panel():
    cache = HERE / "cache" / "panel.pkl"
    if cache.exists():
        return pd.read_pickle(cache)
    games = pd.read_csv(DATA / "games.csv")
    p = P.build_panel(P.load_offense(str(DATA)), games)
    cache.parent.mkdir(exist_ok=True)
    p.to_pickle(cache)
    return p


if __name__ == "__main__":
    panel = base_panel()
    import time
    t = time.time()
    r = evaluate(panel, P.FEATURES)
    g = r.groupby("prop")[["n", "ae", "crps"]].sum()
    print((g[["ae", "crps"]].div(g["n"], axis=0)).round(3).assign(n=g["n"]))
    print("seconds", round(time.time() - t, 1))


def pivot(rows, path=None):
    """Print variants x props: pooled CRPS change % (seasons improved of 15) and MAE change %."""
    d = pd.concat(rows) if isinstance(rows, list) else rows
    if path:
        d.to_csv(path, index=False)
    d = d.copy()
    d["s"] = d["dCRPS%"].map("{:+.3f}".format) + "(" + d["crps_wins"].astype(str) + ")m" + d["dMAE%"].map("{:+.2f}".format)
    with pd.option_context("display.width", 300, "display.max_rows", 500, "display.max_columns", 30,
                           "display.max_colwidth", 60):
        v = d.drop_duplicates("variant")["variant"].tolist()
        print(d.pivot(index="variant", columns="prop", values="s").fillna("").loc[v])

"""Step 3: build the hierarchical candidates and score them walk-forward (fit on earlier seasons, score the next).

Every candidate is a feature list handed to the shipped PropModel machinery (same ridge, same spread code,
same eligibility), so only the expected-value inputs differ. Candidate columns are written beside the
panel's own under new names; the gate columns are never touched, so every candidate scores the same rows.

Leakage: every column is a function of (a) the panel's pre-game decayed sums, which exclude the game
predicted, or (b) the pre-update state of a filter run in time order, plus (c) hyperparameters estimated on
seasons before the test season. test_no_leak() checks (a)/(b) by scrambling outcomes of one season's games
and confirming no feature of an earlier row, or of the scrambled rows' own game, changes.
"""
from __future__ import annotations

import sys, time
import numpy as np, pandas as pd
from numba import njit
from scipy.optimize import minimize
from common import *

HYPER = pd.read_csv(HERE / "pymc_hyper.csv")
BURN = 2011                 # filter likelihood counts rows from this season on
TARGET = {k: P.PROP_BY_KEY[k].target for k in KEYS}
KGRID = (1.0, 2.0, 3.0, 4.5, 7.0, 10.0)


# ------------------------------------------------------------------ learned shrink (c1, PyMC a)

def raw_sums(panel, stat, name):
    return panel[f"{stat}_{name}"].to_numpy() * (panel[f"n_{name}"].to_numpy() + P.SHRINK)


def shrink_cols(panel, key, k_of, tag):
    """Own-history features of prop ``key`` recomputed with k pseudo-games; ``k_of(stat)`` gives k per row."""
    tgt, vol = TARGET[key], VOLUME[key]
    new, ren = {}, {}
    for name in ("fast", "slow"):
        n = panel[f"n_{name}"].to_numpy()
        for stat in (tgt, vol):
            k = k_of(stat)
            new[f"{stat}_{name}@{tag}"] = raw_sums(panel, stat, name) / (n + k)
            ren[f"{stat}_{name}"] = f"{stat}_{name}@{tag}"
        k = k_of(tgt)
        new[f"new_{name}@{tag}"] = k / (n + k)
        ren[f"new_{name}"] = f"new_{name}@{tag}"
    hot = panel["implied_pts"].to_numpy() - 22.0
    new[f"imp_x_{tgt}@{tag}"] = hot * new[f"{tgt}_slow@{tag}"]
    ren[f"imp_x_{tgt}"] = f"imp_x_{tgt}@{tag}"
    qx = {"rec_yds": "qb_x_rec_yds", "receptions": "qb_x_rec"}.get(key)
    if qx:
        new[f"{qx}@{tag}"] = panel["qb_level"].to_numpy() * new[f"{tgt}_slow@{tag}"] / 100.0
        ren[qx] = f"{qx}@{tag}"
    return new, [ren.get(c, c) for c in P.FEATURES[key]]


def pymc_k(panel, stat, season):
    h = HYPER[(HYPER["stat"] == stat) & (HYPER["season"] == season)].set_index("position")
    pos = panel["position"]
    return (np.clip(pos.map(h["k"]).fillna(P.SHRINK).to_numpy(float), 0.5, 30.0), pos.map(h["mu"]).fillna(0.0).to_numpy(float),
            pos.map(h["sigma"]).fillna(1.0).to_numpy(float))


# ------------------------------------------------------------------ dynamic hierarchical model (Kalman)

@njit(cache=True)
def kfilter(y, first, newsea, newteam, lik, par):
    """Local-level model per player: level ~ N(mu0, P0) at his first game; before each later game the level
    variance grows by q; at a new season / new team the level is pulled toward mu0 (phi) and its variance
    grows by qs / qt. Observation variance r0 + r1 * level. Returns the Gaussian negative log likelihood
    on ``lik`` rows and the PRE-game mean and variance of every row."""
    mu0, P0, q, qs, qt, phis, phit, r0, r1 = par
    n = len(y)
    mp, Pp = np.zeros(n), np.zeros(n)
    m, Pv, nll = mu0, P0, 0.0
    for i in range(n):
        if first[i]:
            m, Pv = mu0, P0
        else:
            Pv += q
            if newsea[i]:
                m = mu0 + phis * (m - mu0); Pv = phis * phis * Pv + qs
            if newteam[i]:
                m = mu0 + phit * (m - mu0); Pv = phit * phit * Pv + qt
        mp[i], Pp[i] = m, Pv
        S = Pv + r0 + r1 * max(m, 0.0)
        e = y[i] - m
        if lik[i]:
            nll += 0.5 * (np.log(S) + e * e / S)
        K = Pv / S
        m += K * e
        Pv *= 1.0 - K
    return nll, mp, Pp


def _unpack(z, v):
    sg = lambda x: 1.0 / (1.0 + np.exp(-x))
    return np.array([z[0] * np.sqrt(v), np.exp(z[1]) * v, np.exp(z[2]) * v, np.exp(z[3]) * v, np.exp(z[4]) * v,
                     sg(z[5]), sg(z[6]), np.exp(z[7]) * v, np.exp(z[8]) * np.sqrt(v)])


Z0 = np.array([1.0, -0.5, -4.0, -2.5, -2.0, 2.0, 1.0, -1.0, -1.0])
_WARM: dict = {}
KF_LOG: list = []


def kalman_cols(panel, key, season, fixed=None):
    """kf mean / sd of the prop's target and volume stats, hyperparameters fitted on seasons < ``season``."""
    prop = P.PROP_BY_KEY[key]
    el = P.eligible(panel, prop)
    o = panel.sort_values(["player_id", "gameday", "game_id"], kind="mergesort")
    out = {}
    for stat in (TARGET[key], VOLUME[key]):
        ck = CACHE / f"kf_{'rec' if key != 'rush_yds' else 'rush'}_{stat}_{season}.npz"
        if fixed is None and ck.exists():
            z = np.load(ck)
            out[f"kf_{stat}"], out[f"kfsd_{stat}"] = z["m"], z["s"]
            continue
        mcol, scol = np.zeros(len(panel)), np.ones(len(panel))
        for pos in prop.positions:
            d = o[o["position"] == pos]
            ids, sea, tm = d["player_id"].to_numpy(), d["season"].to_numpy(), d["team"].to_numpy()
            same = np.r_[False, ids[1:] == ids[:-1]]
            ns = same & np.r_[False, sea[1:] != sea[:-1]]
            nt = same & np.r_[False, tm[1:] != tm[:-1]]
            y = d[stat].to_numpy(float)
            loc = panel.index.get_indexer(d.index)
            lik = el[loc] & (sea >= BURN) & (sea < season)
            v = float(y[lik].var())
            f = lambda z: kfilter(y, ~same, ns, nt, lik, _unpack(z, v))[0] / lik.sum()
            if fixed is not None:        # leak test: given hyperparameters, no fitting, no cache
                _, mp, Pp = kfilter(y, ~same, ns, nt, lik, _unpack(fixed, v))
                mcol[loc], scol[loc] = mp, np.sqrt(Pp)
                continue
            z0 = _WARM.get((stat, pos), Z0)
            r = minimize(f, z0, method="Nelder-Mead", options={"maxiter": 4000, "xatol": 1e-3, "fatol": 1e-7})
            r = minimize(f, r.x, method="Nelder-Mead", options={"maxiter": 4000, "xatol": 1e-3, "fatol": 1e-7})
            _WARM[(stat, pos)] = r.x
            par = _unpack(r.x, v)
            _, mp, Pp = kfilter(y, ~same, ns, nt, lik, par)
            mcol[loc], scol[loc] = mp, np.sqrt(Pp)
            KF_LOG.append(dict(stat=stat, pos=pos, season=season, nll=r.fun, mu0=par[0], sd0=np.sqrt(par[1]), q=par[2], qs=par[3],
                               qt=par[4], phi_season=par[5], phi_team=par[6], r0=par[7], r1=par[8],
                               k_first=(par[7] + par[8] * par[0]) / par[1]))
        if fixed is None:
            np.savez(ck, m=mcol, s=scol)
        out[f"kf_{stat}"], out[f"kfsd_{stat}"] = mcol, scol
    return out


# ------------------------------------------------------------------ candidates

def choose_k(panel, key, season):
    """k from KGRID with the lowest squared error over the last three training seasons, each predicted from
    the seasons before it (uses nothing from ``season`` on)."""
    prop = P.PROP_BY_KEY[key]
    best, best_e = P.SHRINK, np.inf
    for k in KGRID:
        new, cols = shrink_cols(panel, key, lambda s: k, "t")
        pan = panel.assign(**new)
        e = 0.0
        for v in range(season - 3, season):
            tr, te = pan[pan["season"] < v], pan[pan["season"] == v]
            te = te[P.eligible(te, prop)]
            m = Cand(prop, cols)
            m.prop = P.Prop(prop.key, prop.label, prop.target, prop.positions, "plain", prop.gate, prop.gate_min)  # ridge only
            m.fit(tr)
            m.prop = prop
            e += float(np.sum((te[prop.target].to_numpy(float) - m.expected(te)) ** 2))
        if e < best_e:
            best, best_e = k, e
    return best


KSEL: list = []


def make(key):
    """name -> (prep(panel, season) -> panel copy, cols(panel) -> feature list)"""
    base = P.FEATURES[key]
    tgt, vol = TARGET[key], VOLUME[key]
    c = {}
    for k in KGRID:
        if k == P.SHRINK:
            continue
        def prep(panel, season, k=k):
            new, cols = shrink_cols(panel, key, lambda s: k, "v")
            pan = panel.assign(**new); pan.attrs["cols"] = cols
            return pan
        c[f"c1 fixed k={k:g}"] = prep

    def prep_sel(panel, season):
        k = choose_k(panel, key, season)
        KSEL.append((key, season, k))
        new, cols = shrink_cols(panel, key, lambda s: k, "v")
        pan = panel.assign(**new); pan.attrs["cols"] = cols
        return pan
    c["c1 k chosen on train"] = prep_sel

    def prep_pm(panel, season):
        new, cols = shrink_cols(panel, key, lambda s: pymc_k(panel, s, season)[0], "v")
        pan = panel.assign(**new); pan.attrs["cols"] = cols
        return pan
    c["a  PyMC k per stat+pos"] = prep_pm

    def prep_pm_add(panel, season):
        new = {}
        for stat in (tgt, vol):
            k, mu, sig = pymc_k(panel, stat, season)
            n = panel["n_slow"].to_numpy()
            new[f"pm_{stat}"] = (k * mu + raw_sums(panel, stat, "slow")) / (k + n)
            new[f"pmsd_{stat}"] = sig / np.sqrt(k + n)
        pan = panel.assign(**new); pan.attrs["cols"] = base + list(new)
        return pan
    c["b  ridge + PyMC mean, sd"] = prep_pm_add

    kf4 = [f"kf_{tgt}", f"kfsd_{tgt}", f"kf_{vol}", f"kfsd_{vol}"]
    own = {f"{s}_{n}" for s in (tgt, vol) for n in ("fast", "slow")} | {"new_fast", "new_slow"}
    for name, cols in (("a  Kalman level only", [f"kf_{tgt}"]), ("a  Kalman replaces own avgs", [x for x in base if x not in own] + kf4),
                       ("b  ridge + Kalman mean, sd", base + kf4)):
        def prep_kf(panel, season, cols=cols):
            pan = panel.assign(**kalman_cols(panel, key, season)); pan.attrs["cols"] = cols
            return pan
        c[name] = prep_kf

    def prep_career(panel, season):
        w = SMALL / (panel["games_played"].to_numpy() + SMALL)
        new = {"car_w": w, "car_x_slow": w * panel[f"{tgt}_slow"].to_numpy(), "car_x_fast": w * panel[f"{tgt}_fast"].to_numpy(),
               "car_x_vol": w * panel[f"{vol}_slow"].to_numpy()}
        pan = panel.assign(**new); pan.attrs["cols"] = base + list(new)
        return pan
    c["c2 weight varies w/ career"] = prep_career

    def prep_small(panel, season):
        s = (panel["games_played"].to_numpy() < SMALL).astype(float)
        new = {"sm": s, "sm_x_slow": s * panel[f"{tgt}_slow"].to_numpy(), "sm_x_fast": s * panel[f"{tgt}_fast"].to_numpy(),
               "sm_x_new": s * panel["new_slow"].to_numpy(), "rk": panel["is_rookie"].to_numpy(),
               "rk_x_slow": panel["is_rookie"].to_numpy() * panel[f"{tgt}_slow"].to_numpy()}
        pan = panel.assign(**new); pan.attrs["cols"] = base + list(new)
        return pan
    c["c2 small/rookie terms"] = prep_small
    return c


def test_no_leak(panel):
    """Scramble every outcome in 2022 and later; candidate columns of rows up to each player's first
    scrambled game (inclusive: that row is still pre-game) must not move."""
    rng = np.random.default_rng(1)
    p2 = panel.copy()
    late = (p2["season"] >= 2022).to_numpy()
    for col in ("targets", "receptions", "receiving_yards", "carries", "rushing_yards"):
        v = p2[col].to_numpy().copy(); v[late] = rng.permutation(v[late]) + 7.0; p2[col] = v
    first_late = panel[late].groupby("player_id")["gameday"].min()
    ok = (panel["gameday"] <= panel["player_id"].map(first_late).fillna("9999")).to_numpy()
    for key in KEYS:
        # hyperparameters held fixed (they are fitted on rows with season < test season only, by the `lik` mask),
        # so this isolates the filter: does any pre-game state depend on that game or a later one?
        a, b = kalman_cols(panel, key, 2022, fixed=Z0), kalman_cols(p2, key, 2022, fixed=Z0)
        for col in a:
            assert np.allclose(a[col][ok], b[col][ok]), (key, col)
            assert not np.allclose(a[col][~ok], b[col][~ok]), "test has no power"
        for tag, k_of in (("v", lambda s: 4.5),):
            na, _ = shrink_cols(panel, key, k_of, tag); nb, _ = shrink_cols(p2, key, k_of, tag)
            for col in na:
                assert np.allclose(na[col][ok], nb[col][ok]), (key, col)
    print(f"no-leak check passed: Kalman columns unchanged on {int(ok.sum())} rows at or before each player's first scrambled game "
          f"({int((ok & late).sum())} of them are scrambled games themselves)")


if __name__ == "__main__":
    panel = load_panel()
    if "leak" in sys.argv:
        test_no_leak(panel); sys.exit()
    first = 2015
    last = int(panel["season"].max())
    for key in KEYS:
        for name, prep in make(key).items():
            f = CACHE / f"rows_{key}_{name.replace(' ', '_').replace('/', '-')}.pkl"
            t = time.time()
            r = walk(panel, key, lambda pan: pan.attrs["cols"], first, last, prep)
            r.to_pickle(f)
            print(f"{key:<11}{name:<30}CRPS {r['crps'].mean():.4f}  MAE {np.mean(np.abs(r['y']-r['mu'])):.3f}  {time.time()-t:.0f}s", flush=True)
    pd.DataFrame(KF_LOG).to_csv(HERE / "kalman_hyper.csv", index=False)
    pd.DataFrame(KSEL, columns=["prop", "season", "k"]).to_csv(HERE / "k_chosen.csv", index=False)
    test_no_leak(panel)

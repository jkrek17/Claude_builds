"""Q1: expected-value stage. Ridge (baseline) vs log-link GLM vs boosted trees, same stage 2 (baseline spread).

Usage: python q1_mean.py [fast]   ('fast' skips the tree models)
"""
import sys
import time
from multiprocessing import Pool
import numpy as np
import pandas as pd
from nflmodel import props as P
import common as C
from gbm import GBM

VARIANTS = {
    "ridge (baseline)": (C.Ridge, {}),
    "logGLM logx": (C.LogGLM, {}),
    "logGLM rawx": (C.LogGLM, {"logx": False}),
    "logGLM logx lam1": (C.LogGLM, {"lam": 1.0}),
    "logGLM logx lam25": (C.LogGLM, {"lam": 25.0}),
}
TREES = {
    "ridge+GBM d3 150x.08": (GBM, {}),
    "ridge+GBM d2 150x.08": (GBM, {"depth": 2}),
    "ridge+GBM d3 60x.08": (GBM, {"n_trees": 60}),
    "GBM alone d3 300x.08": (GBM, {"on_ridge": False, "n_trees": 300}),
}


def one(args):
    key, name, cls, kw = args
    panel = C.get_panel()
    prop = P.PROP_BY_KEY[key]
    folds = C.make_folds(panel, prop, mean=cls, **kw)
    if prop.kind == "yards":
        r = C.run_dist(folds, C.EmpRatio(), "q")
    else:
        r = C.run_dist(folds, C.NBGrid(), "pmf")
    return key, name, r


if __name__ == "__main__":
    t = time.time()
    C.get_panel()
    var = dict(VARIANTS)
    if "fast" not in sys.argv:
        var.update(TREES)
    jobs = [(p.key, n, c, kw) for p in P.PROPS for n, (c, kw) in var.items()]
    with Pool(4) as pool:
        res = pool.map(one, jobs, chunksize=1)
    R = {(k, n): r for k, n, r in res}
    pd.to_pickle(R, C.CACHE / "q1.pkl")
    for prop in P.PROPS:
        base = R[(prop.key, "ridge (baseline)")]
        print(f"\n=== {prop.key}   (bias = mean(actual - expected) in the lowest / highest tenth of expected value, with z)")
        print(f"{'variant':<24}{'MAE':>8}{'RMSE':>8}{'CRPS':>9}{'dCRPS%':>8}{'won':>7}{'dRMSE%':>8}{'won':>7}{'in50':>7}{'in80':>7}"
              f"{'lo10 bias':>16}{'hi10 bias':>16}")
        for n in var:
            r = R[(prop.key, n)]
            e = r["y"] - r["mu"]
            c, w, k = C.compare(base, r)
            b2, r2 = base.assign(se=(base.y - base.mu) ** 2), r.assign(se=e ** 2)
            c2, w2, _ = C.compare(b2, r2, "se")
            s = C.summary(r)
            lo, hi = r["mu"] <= r["mu"].quantile(.1), r["mu"] >= r["mu"].quantile(.9)
            bl = f"{e[lo].mean():+.3f} ({e[lo].mean()/(e[lo].std()/np.sqrt(lo.sum())):+.1f})"
            bh = f"{e[hi].mean():+.3f} ({e[hi].mean()/(e[hi].std()/np.sqrt(hi.sum())):+.1f})"
            print(f"{n:<24}{np.abs(e).mean():>8.3f}{np.sqrt((e**2).mean()):>8.3f}{s['crps']:>9.4f}{c:>8.2f}{w:>4}/{k}{c2/2:>8.2f}{w2:>4}/{k}"
                  f"{s['in50']:>7.3f}{s['in80']:>7.3f}{bl:>16}{bh:>16}")
        # bias by position at the ends for ridge
        for pos in prop.positions:
            b = base[base.pos == pos]
            e = b.y - b.mu
            qs = b.mu.quantile([.05, .1, .9, .95]).to_numpy()
            parts = [("lo5", b.mu <= qs[0]), ("lo10", b.mu <= qs[1]), ("hi10", b.mu >= qs[2]), ("hi5", b.mu >= qs[3])]
            print(f"   ridge {pos}: " + "  ".join(f"{nm} mu={b.mu[m].mean():.2f} bias={e[m].mean():+.3f} z={e[m].mean()/(e[m].std()/np.sqrt(m.sum())):+.1f}" for nm, m in parts))
    print(f"{time.time()-t:.0f}s")

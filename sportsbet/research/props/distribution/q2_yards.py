"""Q2: yardage spread. Empirical ratio bins (baseline) vs parametric families, bin-count neighbours, and
whether the spread depends on anything beyond the expected value (terciles of a modifier inside the ratio table)."""
import time
from multiprocessing import Pool
import numpy as np
import pandas as pd
from nflmodel import props as P
import common as C
import dists as D

LINES = {"pass_yds": (199.5, 249.5, 299.5, 349.5), "rush_yds": (24.5, 49.5, 74.5, 99.5),
         "rec_yds": (24.5, 49.5, 74.5, 99.5)}
BINS = {"pass_yds": np.arange(24.5, 500, 25.0), "rush_yds": np.arange(-0.5, 200, 10.0),
        "rec_yds": np.arange(-0.5, 200, 10.0)}
MODS = {"pass_yds": ["cv_pass", "early", "n_eff"], "rush_yds": ["cv_rush", "early", "n_eff"],
        "rec_yds": ["adot_slow", "cv_rec", "early", "n_eff"]}


def variants(key):
    v = {"emp 12 bins (baseline)": C.EmpRatio()}
    for nb in (4, 6, 8, 20, 30):
        v[f"emp {nb} bins"] = C.EmpRatio(nbins=nb)
    v["emp 12 bins, positions pooled"] = C.EmpRatio(bypos=False)
    v["zero-infl gamma"] = D.ZeroInflated("gamma")
    v["zero-infl lognormal"] = D.ZeroInflated("lognormal")
    if key == "pass_yds":
        v["normal sd(mu)"] = D.SkewNormal(False)
        v["skew-normal sd(mu)"] = D.SkewNormal(True)
    for m in MODS[key]:
        for nb in (6, 12):
            v[f"emp {nb} bins x {m} tercile"] = C.EmpRatio(nbins=nb, split=m)
    return v


def one(args):
    key, name = args
    panel = C.get_panel()
    folds = C.make_folds(panel, P.PROP_BY_KEY[key])
    r = C.run_dist(folds, variants(key)[name], "q", LINES[key], BINS[key])
    if name.startswith("emp 12 bins (b"):
        te = pd.concat([f.te for f in folds])
        for c in set(MODS[key]) | {"games_played"}:
            r[c] = te[c].to_numpy()
    return key, name, r


def slope(p, o):
    """Calibration intercept and slope of the outcome on logit(forecast): (0, 1) is perfect."""
    lp = np.log(np.clip(p, 1e-4, 1 - 1e-4) / (1 - np.clip(p, 1e-4, 1 - 1e-4)))
    b = C.logistic_fit(lp[:, None], o, lam=0.0)
    return b[0], b[1]


def subgroup(r, mask, name):
    s = C.summary(r[mask])
    return (f"    {name:<22} n={s['n']:>6} in50 {s['in50']:.3f} in80 {s['in80']:.3f} <p10 {s['<p10']:.3f} <p25 {s['<p25']:.3f}"
            f" >p50 {s['>p50']:.3f} >p75 {s['>p75']:.3f} >p90 {s['>p90']:.3f}")


def subgroups(r, extra=()):
    out = []
    for p in pd.unique(r["pos"]):
        out.append(subgroup(r, r["pos"] == p, f"pos {p}"))
    q = r.groupby("pos")["mu"].transform(lambda s: pd.qcut(s, 3, labels=False, duplicates="drop"))
    for k, nm in enumerate(("low mu", "mid mu", "high mu")):
        out.append(subgroup(r, q == k, nm))
    out.append(subgroup(r, r["week"] <= 4, "weeks 1-4"))
    out.append(subgroup(r, r["week"] > 4, "weeks 5+"))
    for c in extra:
        if c == "early":
            continue
        v = r[c]
        g = r.assign(v=v).groupby("pos")["v"].transform(lambda s: pd.qcut(s, 3, labels=False, duplicates="drop"))
        for k, nm in enumerate(("low", "mid", "high")):
            out.append(subgroup(r, g == k, f"{c} {nm}"))
    return "\n".join(out)


if __name__ == "__main__":
    t = time.time()
    C.get_panel()
    D._gamma_table()
    jobs = [(k, n) for k in LINES for n in variants(k)]
    with Pool(4) as pool:
        res = pool.map(one, jobs, chunksize=1)
    R = {(k, n): r for k, n, r in res}
    pd.to_pickle(R, C.CACHE / "q2.pkl")
    for key in LINES:
        base = R[(key, "emp 12 bins (baseline)")]
        print(f"\n=== {key}  n={len(base)}  (logs = binned log score, {BINS[key][1]-BINS[key][0]:.0f}-yard bins)")
        print(C.HDR)
        for n in variants(key):
            print(C.line_fmt(n, R[(key, n)], base))
        print("  fixed-line reliability: mean forecast / observed, calibration intercept, slope")
        for n in ("emp 12 bins (baseline)", "zero-infl gamma", "zero-infl lognormal", "skew-normal sd(mu)"):
            if (key, n) not in R:
                continue
            r = R[(key, n)]
            txt = []
            for ln in LINES[key]:
                p, o = r[f"p>{ln}"].to_numpy(), (r["y"] > ln).to_numpy(float)
                a, b = slope(p, o)
                txt.append(f">{ln}: {p.mean():.3f}/{o.mean():.3f} a={a:+.2f} b={b:.2f}")
            print(f"    {n:<24}" + "  ".join(txt))
        print("  baseline calibration in subgroups (terciles are within position):")
        print(subgroups(base, MODS[key] + ["games_played"]))
    print(f"{time.time()-t:.0f}s")

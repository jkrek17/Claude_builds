"""Q3: count props. Negative binomial on the package grid (baseline) vs Poisson, continuous-ML NB, NB with
dispersion depending on the mean, binomial and Conway-Maxwell-Poisson (under-dispersion), a beta-binomial over an
opportunity count (receptions over targets, completions over attempts), and attempts/completions as counts
instead of the continuous ratio table. Stage 1 is the baseline ridge throughout."""
import time
from multiprocessing import Pool
import numpy as np
import pandas as pd
from nflmodel import props as P
import common as C
import counts as K
from q2_yards import slope

LINES = {"pass_tds": (0.5, 1.5, 2.5, 3.5), "receptions": (1.5, 3.5, 5.5, 7.5), "any_td": (0.5, 1.5),
         "pass_att": (27.5, 33.5, 39.5), "pass_cmp": (17.5, 22.5, 27.5)}
KMAX = {"pass_tds": 40, "receptions": 40, "any_td": 40, "pass_att": 90, "pass_cmp": 70}
AUX = {"receptions": "targets", "pass_cmp": "attempts"}


def variants(key):
    k = KMAX[key]
    v = {}
    if key in ("pass_att", "pass_cmp"):
        v["emp ratio 12 bins (baseline)"] = K.EmpAsPmf(K=k)
        v["emp ratio 6 bins"] = K.EmpAsPmf(K=k, nbins=6)
        v["NB grid alpha"] = C.NBGrid(K=k)
    else:
        v["NB grid alpha (baseline)"] = C.NBGrid(K=k)
    v["Poisson"] = K.Poisson(K=k)
    v["NB ML alpha"] = K.NBML(K=k)
    v["NB alpha=exp(a+b log mu)"] = K.NBML(K=k, mode="mu")
    if key in ("pass_tds", "any_td", "receptions"):
        v["binomial(n, mu/n)"] = K.Binomial(K=k)
        v["COM-Poisson nu const"] = K.ComPoisson(K=k)
    if key in ("pass_tds",):
        v["COM-Poisson nu(mu)"] = K.ComPoisson(K=k, mode="mu")
    if key in AUX:
        v["beta-binomial over NB opp."] = K.BBMix(K=k, T=45 if key == "receptions" else 90)
    return v


def one(args):
    key, name = args
    panel = C.get_panel()
    prop = P.PROP_BY_KEY[key]
    folds = C.make_folds(panel, prop)
    if name.startswith("beta-binomial"):
        aux = C.make_folds(panel, prop, target=AUX[key], feats=P.FEATURES["pass_att"] if key == "pass_cmp" else None)
        for f, g in zip(folds, aux):
            f.tr = f.tr.assign(mu_aux=np.maximum(g.mu_tr, 0.3), y_aux=g.y_tr)
            f.te = f.te.assign(mu_aux=np.maximum(g.mu_te, 0.3), y_aux=g.y_te)
    d = variants(key)[name]
    r = C.run_dist(folds, d, "pmf", LINES[key])
    par = getattr(d, "a", None) or getattr(d, "m", None) or getattr(d, "phi", None)
    return key, name, r, par


def subgroup(r, mask, name):
    s = C.summary(r[mask])
    return (f"    {name:<12} n={s['n']:>6} in50 {s['in50']:.3f} in80 {s['in80']:.3f} <p10 {s['<p10']:.3f} <p25 {s['<p25']:.3f}"
            f" >p50 {s['>p50']:.3f} >p75 {s['>p75']:.3f} >p90 {s['>p90']:.3f}")


def subgroups(r):
    out = [subgroup(r, r["pos"] == p, f"pos {p}") for p in pd.unique(r["pos"])]
    q = r.groupby("pos")["mu"].transform(lambda s: pd.qcut(s, 3, labels=False, duplicates="drop"))
    out += [subgroup(r, q == k, nm) for k, nm in enumerate(("low mu", "mid mu", "high mu"))]
    out += [subgroup(r, r["week"] <= 4, "weeks 1-4"), subgroup(r, r["week"] > 4, "weeks 5+")]
    return "\n".join(out)


def lines_txt(r, lines):
    txt = []
    for ln in lines:
        p, o = r[f"p>{ln}"].to_numpy(), (r["y"] > ln).to_numpy(float)
        a, b = slope(p, o)
        txt.append(f">{ln}: {p.mean():.3f}/{o.mean():.3f} a={a:+.2f} b={b:.2f}")
    return "  ".join(txt)


if __name__ == "__main__":
    t = time.time()
    C.get_panel()
    jobs = [(k, n) for k in LINES for n in variants(k)]
    with Pool(4) as pool:
        res = pool.map(one, jobs, chunksize=1)
    R = {(k, n): r for k, n, r, _ in res}
    PAR = {(k, n): p for k, n, _, p in res}
    pd.to_pickle(R, C.CACHE / "q3.pkl")
    for key in LINES:
        names = list(variants(key))
        base = R[(key, names[0])]
        print(f"\n=== {key}  n={len(base)}   (in50/in80 etc. from randomised PIT; logs = -log pmf(actual))")
        print(C.HDR)
        for n in names:
            print(C.line_fmt(n, R[(key, n)], base))
            if PAR[(key, n)] is not None and not n.startswith("emp"):
                print("      last-fold parameters: " + "  ".join(f"{p}:{np.round(v, 3)}" for p, v in PAR[(key, n)].items() if v is not None))
        for n in names:
            print(f"  lines {n:<28}" + lines_txt(R[(key, n)], LINES[key]))
        for n in names[:1]:
            print(f"  subgroups, {n}:")
            print(subgroups(R[(key, n)]))
    print(f"{time.time()-t:.0f}s")

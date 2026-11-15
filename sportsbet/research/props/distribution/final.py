"""The recommended setup against the baseline, prop by prop, with subgroup and tail calibration.

Recommended:
  pass_yds / pass_att / pass_cmp : ridge mean; ratio table with nbins = clip(n_position // 500, 2, 6)
  rush_yds                       : same + spread modifier [cv_rush, log1p(n_eff), early]
  rec_yds                        : same + spread modifier [adot_slow, cv_rec, log1p(n_eff), early]
  pass_tds                       : ridge mean; binomial(n, mu/n), n by ML (about 9)
  receptions                     : ridge mean; negative binomial with alpha = exp(a + b log mu) per position
  any_td                         : direct logistic (lam 25, logged volumes) for p = P(1+); the count is a negative
                                   binomial with alpha per position and mean r((1-p)^(-1/r) - 1), r = 1/alpha, so P(0) = 1-p
"""
import math
import numpy as np
import pandas as pd
from multiprocessing import Pool
from nflmodel import props as P
import common as C
import counts as K
from q2_yards import slope, BINS
from q2b_scaled import EmpScaled, MODSETS
from q4_anytd import Logit

LINES = {"pass_yds": (224.5, 249.5, 299.5, 349.5), "rush_yds": (49.5, 74.5, 99.5), "rec_yds": (49.5, 74.5, 99.5),
         "pass_att": (33.5, 39.5), "pass_cmp": (22.5, 27.5), "pass_tds": (0.5, 1.5, 2.5), "receptions": (3.5, 5.5, 7.5),
         "any_td": (0.5, 1.5)}


def rec_dist(key):
    if key in ("rush_yds", "rec_yds"):
        return EmpScaled(mods=MODSETS[key][list(MODSETS[key])[-1]], per=500)
    if key in ("pass_yds", "pass_att", "pass_cmp"):
        return EmpScaled(per=500)
    if key == "pass_tds":
        return K.Binomial()
    if key == "receptions":
        return K.NBML(mode="mu")
    return K.NBFromP()


def one(key):
    panel = C.get_panel()
    panel["td1"] = (panel["skill_tds"] >= 1).astype(float)
    prop = P.PROP_BY_KEY[key]
    folds = C.make_folds(panel, prop)
    kind = "q" if prop.kind == "yards" else "pmf"
    bins = BINS.get(key)
    base = C.run_dist(folds, C.EmpRatio() if kind == "q" else C.NBGrid(), kind, LINES[key], bins) if kind == "q" else \
        C.run_dist(folds, C.NBGrid(), kind, LINES[key])
    extra = {}
    if key == "any_td":
        fl = C.make_folds(panel, prop, mean=Logit, target="td1", lam=25.0)
        for f, g in zip(folds, fl):
            f.mu_te, f.mu_tr = g.mu_te, g.mu_tr          # the fold's 'mu' is now P(1+)
    new = C.run_dist(folds, rec_dist(key), kind, LINES[key], bins) if kind == "q" else C.run_dist(folds, rec_dist(key), kind, LINES[key])
    if key == "pass_tds":
        for n in (7, 8, 9, 10, 12, 14, 20):
            d = K.Binomial()
            d.grid = (n,)
            extra[f"binomial n={n} fixed"] = C.run_dist(folds, d, kind, LINES[key])
    if key == "receptions":
        aux = C.make_folds(panel, prop, target="targets")
        for f, g in zip(folds, aux):
            f.tr = f.tr.assign(mu_aux=np.maximum(g.mu_tr, 0.3), y_aux=g.y_tr)
            f.te = f.te.assign(mu_aux=np.maximum(g.mu_te, 0.3), y_aux=g.y_te)
        extra["beta-binomial over NB targets"] = C.run_dist(folds, K.BBMix(T=45), kind, LINES[key])
    te = pd.concat([f.te for f in folds])
    for r in (base, new):
        r["games_played"] = te["games_played"].to_numpy()
    return key, base, new, extra


def sub(r, mask, name, lines):
    d = r[mask]
    s = C.summary(d)
    txt = (f"    {name:<16} n={s['n']:>6} in50 {s['in50']:.3f} in80 {s['in80']:.3f} <p10 {s['<p10']:.3f} <p25 {s['<p25']:.3f}"
           f" >p50 {s['>p50']:.3f} >p75 {s['>p75']:.3f} >p90 {s['>p90']:.3f} |")
    for ln in lines:
        p, o = d[f"p>{ln}"].mean(), (d["y"] > ln).mean()
        se = math.sqrt(max(o * (1 - o), 1e-9) / len(d))
        txt += f" >{ln:g}: {p:.3f}/{o:.3f}{'*' if abs(p - o) > 2.5 * se else ' '}"
    return txt


def table(r, lines):
    out = [sub(r, np.ones(len(r), bool), "all", lines)]
    out += [sub(r, r["pos"] == p, f"pos {p}", lines) for p in pd.unique(r["pos"])]
    q = r.groupby("pos")["mu"].transform(lambda s: pd.qcut(s, 3, labels=False, duplicates="drop"))
    out += [sub(r, q == k, nm, lines) for k, nm in enumerate(("low expected", "mid expected", "high expected"))]
    out += [sub(r, r["week"] <= 4, "weeks 1-4", lines), sub(r, r["week"] > 4, "weeks 5+", lines)]
    out += [sub(r, r["games_played"] < 8, "under 8 games", lines)]
    out += [sub(r, r["season"] <= 2018, "2012-2018", lines), sub(r, r["season"] >= 2019, "2019-2026", lines)]
    return "\n".join(out)


if __name__ == "__main__":
    C.get_panel()
    with Pool(4) as pool:
        res = pool.map(one, [p.key for p in P.PROPS], chunksize=1)
    print("Calibration columns use randomised PITs; '>x: forecast/observed' are fixed lines, * = gap over 2.5 standard errors.")
    for key, base, new, extra in res:
        print(f"\n=== {key}")
        print(C.HDR)
        print(C.line_fmt("baseline", base))
        print(C.line_fmt("recommended", new, base))
        for n, r in extra.items():
            print(C.line_fmt(n, r, base))
        for nm, r in (("baseline", base), ("recommended", new)):
            txt = []
            for ln in LINES[key]:
                p, o = r[f"p>{ln}"].to_numpy(), (r["y"] > ln).to_numpy(float)
                a, b = slope(p, o)
                br = np.mean((p - o) ** 2)
                txt.append(f">{ln:g}: {p.mean():.3f}/{o.mean():.3f} a={a:+.2f} b={b:.2f} Brier {br:.5f}")
            print(f"  lines {nm:<12}" + "  ".join(txt))
        print("  baseline subgroups:")
        print(table(base, LINES[key]))
        print("  recommended subgroups:")
        print(table(new, LINES[key]))
    pd.to_pickle({k: (b, n) for k, b, n, _ in res}, C.CACHE / "final.pkl")

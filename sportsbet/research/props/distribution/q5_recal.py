"""Q5: a final recalibration step fitted on earlier seasons' out-of-sample forecasts.

Yardage: PIT recalibration. For test season s, G = empirical cdf of the randomised PITs of all out-of-sample
forecasts from seasons 2009..s-1 (walk-forward from 2009); new quantile at level u = old quantile at level G^-1(u).
Anytime TD: Platt scaling (logistic on logit p) and isotonic regression (pool-adjacent-violators) fitted the same way.
"""
import numpy as np
import pandas as pd
from nflmodel import props as P
import common as C
from q2b_scaled import EmpScaled, MODSETS
from q4_anytd import Logit

START = 2009


def pav(p, o):
    """Isotonic regression of o on p: returns step-function knots (x, y)."""
    order = np.argsort(p)
    x, y = p[order], o[order].astype(float)
    val, wt, xs = [], [], []
    for xi, yi in zip(x, y):
        val.append(yi); wt.append(1.0); xs.append(xi)
        while len(val) > 1 and val[-2] >= val[-1]:
            v = (val[-2] * wt[-2] + val[-1] * wt[-1]) / (wt[-2] + wt[-1])
            w = wt[-2] + wt[-1]
            val[-2:], wt[-2:] = [v], [w]
            xs[-2:] = [xs[-1]]
    return np.array(xs), np.array(val)


def recal_q(Q, pit_prior):
    v = np.clip(np.quantile(pit_prior, C.L), C.L[0], C.L[-1])        # G^-1(L)
    j = np.clip(np.searchsorted(C.L, v), 1, C.NQ - 1)
    w = (v - C.L[j - 1]) / (C.L[j] - C.L[j - 1])
    return Q[:, j - 1] * (1 - w) + Q[:, j] * w


def yards(key, dist, label):
    panel = C.get_panel()
    folds = C.make_folds(panel, P.PROP_BY_KEY[key], first=2010 if key == "rec_yds" else START)   # targets exist only from 2009
    raw, rec, recpos, rec3 = [], [], [], []
    hist = []                                             # (season, pos, pit) of earlier out-of-sample forecasts
    for f in folds:
        Q = dist.fit(f.tr, f.mu_tr, f.y_tr).predict(f.te, f.mu_te)
        s0 = C.score_q(f.te, Q, f.y_te, f.mu_te)
        if f.season >= C.FIRST:
            H = pd.concat(hist)
            raw.append(s0)
            rec.append(C.score_q(f.te, recal_q(Q, H["pit"].to_numpy()), f.y_te, f.mu_te, seed=1))
            rec3.append(C.score_q(f.te, recal_q(Q, H.loc[H.season >= f.season - 3, "pit"].to_numpy()), f.y_te, f.mu_te, seed=1))
            Q2 = Q.copy()
            for p in pd.unique(f.te["position"]):
                a = (f.te["position"] == p).to_numpy()
                hp = H.loc[H.pos == p, "pit"].to_numpy()
                if len(hp) >= 300:
                    Q2[a] = recal_q(Q[a], hp)
            recpos.append(C.score_q(f.te, Q2, f.y_te, f.mu_te, seed=1))
        hist.append(s0[["season", "pos", "pit"]])
    raw = pd.concat(raw)
    print(f"\n=== {key} / {label}")
    print(C.HDR)
    print(C.line_fmt("no recalibration", raw, None))
    for n, r in (("PIT recal, all earlier seasons", rec), ("PIT recal, by position", recpos), ("PIT recal, last 3 seasons", rec3)):
        r = pd.concat(r)
        print(C.line_fmt(n, r, raw))
        if key == "rush_yds" and n.endswith("position"):
            for nm, d in (("raw", raw), ("recal by pos", r)):
                q = d[d.pos == "QB"]
                s = C.summary(q)
                print(f"      QB rushers {nm}: n={s['n']} in50 {s['in50']:.3f} in80 {s['in80']:.3f} <p10 {s['<p10']:.3f} >p90 {s['>p90']:.3f} crps {s['crps']:.3f}")


def anytd():
    panel = C.get_panel()
    panel["td1"] = (panel["skill_tds"] >= 1).astype(float)
    prop = P.PROP_BY_KEY["any_td"]
    f0 = C.make_folds(panel, prop, first=2010)
    nb = C.run_dist(f0, C.NBGrid(), "pmf", (0.5,), first=2010)
    fl = C.make_folds(panel, prop, mean=Logit, target="td1", lam=25.0, first=2010)
    seasons = nb["season"].to_numpy()
    o = (nb["y"].to_numpy() >= 1).astype(float)
    for label, p in (("ridge + NB (baseline)", nb["p>0.5"].to_numpy()), ("direct logistic lam25", np.concatenate([g.mu_te for g in fl]))):
        p = np.clip(p, 1e-4, 1 - 1e-4)
        out = {"none": [], "Platt": [], "isotonic": []}
        for s in range(C.FIRST, C.LAST + 1):
            pr, te = seasons < s, seasons == s
            lp = np.log(p / (1 - p))
            a, b = C.logistic_fit(lp[pr][:, None], o[pr], lam=0.0)
            xs, ys = pav(p[pr], o[pr])
            out["none"].append(p[te]); out["Platt"].append(C.sigmoid(a + b * lp[te]))
            out["isotonic"].append(np.clip(np.interp(p[te], xs, ys), 1e-3, 1 - 1e-3))
        m = seasons >= C.FIRST
        print(f"\n=== any_td / {label}")
        base = None
        for n, v in out.items():
            v = np.concatenate(v)
            br, ll = (v - o[m]) ** 2, -(o[m] * np.log(v) + (1 - o[m]) * np.log(1 - v))
            d = pd.DataFrame({"season": seasons[m], "br": br, "ll": ll})
            txt = f"  {n:<10} Brier {br.mean():.5f} logloss {ll.mean():.5f}"
            if base is not None:
                gb, gn = base.groupby("season")[["br", "ll"]].mean(), d.groupby("season")[["br", "ll"]].mean()
                txt += f"  dBrier {100*(br.mean()/base.br.mean()-1):+.2f}% {int((gn.br < gb.br).sum())}/15  dLL {100*(ll.mean()/base.ll.mean()-1):+.2f}% {int((gn.ll < gb.ll).sum())}/15"
            base = d if base is None else base
            lo = v < 0.10
            print(txt + f"   forecasts under 10%: n={lo.sum()} forecast {v[lo].mean():.3f} observed {o[m][lo].mean():.3f}")


if __name__ == "__main__":
    import sys
    if "td" in sys.argv:
        anytd()
        sys.exit()
    for key in ("pass_yds", "rush_yds", "rec_yds", "pass_att", "pass_cmp"):
        yards(key, C.EmpRatio(), "baseline 12-bin ratio table")
    for key in ("rush_yds", "rec_yds"):
        yards(key, EmpScaled(mods=MODSETS[key][list(MODSETS[key])[-1]], per=500), "recommended (bins by n + spread modifier)")
    yards("pass_yds", EmpScaled(per=500), "recommended (6 bins)")
    anytd()

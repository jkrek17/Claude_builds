"""Q2 (continued): a smooth spread modifier on top of the empirical ratio table, and bin counts tied to sample size.

EmpScaled: ratio table as baseline (nbins), then for each player the ratio quantiles are stretched about 1:
    r' = 1 + s * (r - 1)   (quantiles at or below zero are left alone; r' floored at 0; rescaled to the same mean)
    s  = exp(sum_j g_j z_j), clipped to [0.6, 1.6]
where z_j are standardised pre-game modifiers (clipped to +-3, missing = 0) and g_j come from a log-link GLM of |actual/expected - 1| on
[log mu, (log mu)^2, z] fitted per position on the training seasons (ridge 5).
"""
import sys
import time
from multiprocessing import Pool
import numpy as np
import pandas as pd
from nflmodel import props as P
import common as C
from q2_yards import LINES, BINS, subgroups, slope

MODSETS = {
    "rec_yds": {"adot": ["adot_slow"], "cv": ["cv_rec"], "adot+cv": ["adot_slow", "cv_rec"],
                "adot+cv+hist+early": ["adot_slow", "cv_rec", "lhist", "early"]},
    "rush_yds": {"cv": ["cv_rush"], "cv+hist+early": ["cv_rush", "lhist", "early"]},
    "pass_yds": {"cv": ["cv_pass"], "cv+hist+early": ["cv_pass", "lhist", "early"]},
}


class EmpScaled(C.EmpRatio):
    def __init__(self, nbins=6, mods=(), per=None, lam=5.0, clip=(0.6, 1.6), gscale=1.0):
        super().__init__(nbins=nbins)
        self.mods, self.per, self.lam, self.clip, self.gscale = list(mods), per, lam, clip, gscale

    def _z(self, d, p, fit=False):
        x = np.column_stack([np.log1p(d["n_eff"].to_numpy(float)) if m == "lhist" else d[m].to_numpy(float) for m in self.mods])
        if fit:
            self.st[p] = (np.nanmean(x, 0), np.nanstd(x, 0) + 1e-9)
        z = (x - self.st[p][0]) / self.st[p][1]
        return np.clip(np.nan_to_num(z, nan=0.0), -3, 3)

    def fit(self, tr, mu, y):
        pos = tr["position"].to_numpy()
        self.m, self.g, self.st = {}, {}, {}
        for p in np.unique(pos):
            a = pos == p
            nb = self.nbins if self.per is None else int(np.clip(a.sum() // self.per, 2, self.nbins))
            self.nbins_used = nb
            keep, self.nbins = self.nbins, nb
            self.m[p] = self._table(mu[a], y[a])
            self.nbins = keep
            if self.mods:
                z = self._z(tr[a], p, fit=True)
                lm = np.log(mu[a])
                X = np.column_stack([lm, lm ** 2, z])
                g = C.LogGLM(lam=self.lam, logx=False).fit(X, np.abs(y[a] / mu[a] - 1.0), 0.0)
                self.g[p] = g.b[3:] / g.s[2:]        # coefficients per unit of z (the GLM re-standardises internally)
        return self

    def predict(self, te, mu):
        Q = np.zeros((len(te), C.NQ))
        pos = te["position"].to_numpy()
        for p in np.unique(pos):
            a = pos == p
            q = self._apply(self.m[p], mu[a])
            if self.mods:
                z = self._z(te[a], p)
                s = np.clip(np.exp(self.gscale * (z @ self.g[p])), *self.clip)[:, None]
                r = q / mu[a][:, None]
                r2 = np.where(r <= 0, r, np.maximum(1 + s * (r - 1), 0.0))
                r2 *= (r.mean(1, keepdims=True) / np.maximum(r2.mean(1, keepdims=True), 1e-9))
                q = r2 * mu[a][:, None]
            Q[a] = q
        return Q


def variants(key):
    v = {"emp 12 bins (baseline)": C.EmpRatio(), "emp 6 bins": C.EmpRatio(nbins=6)}
    for per in (250, 500, 1000):
        v[f"emp bins=clip(n/{per},2,6)"] = EmpScaled(per=per)
    v["emp bins=clip(n/500,2,12)"] = EmpScaled(nbins=12, per=500)
    for nm, mods in MODSETS[key].items():
        v[f"6 bins + scale[{nm}]"] = EmpScaled(mods=mods, per=500)
    last = list(MODSETS[key])[-1]
    v[f"6 bins + scale[{last}] x0.5"] = EmpScaled(mods=MODSETS[key][last], per=500, gscale=0.5)
    v[f"6 bins + scale[{last}] x1.5"] = EmpScaled(mods=MODSETS[key][last], per=500, gscale=1.5)
    return v


def one(args):
    key, name = args
    panel = C.get_panel()
    folds = C.make_folds(panel, P.PROP_BY_KEY[key])
    d = variants(key)[name]
    r = C.run_dist(folds, d, "q", LINES[key], BINS[key])
    te = pd.concat([f.te for f in folds])
    for c in ("adot_slow", "cv_rec", "cv_rush", "cv_pass", "games_played"):
        r[c] = te[c].to_numpy()
    return key, name, r, getattr(d, "g", None)


if __name__ == "__main__":
    t = time.time()
    C.get_panel()
    jobs = [(k, n) for k in LINES for n in variants(k)]
    with Pool(4) as pool:
        res = pool.map(one, jobs, chunksize=1)
    R = {(k, n): r for k, n, r, _ in res}
    G = {(k, n): g for k, n, _, g in res}
    pd.to_pickle(R, C.CACHE / "q2b.pkl")
    for key in LINES:
        base = R[(key, "emp 12 bins (baseline)")]
        print(f"\n=== {key}")
        print(C.HDR)
        for n in variants(key):
            print(C.line_fmt(n, R[(key, n)], base))
            if G[(key, n)]:
                print("      last-fold modifier coefficients g: " + "  ".join(f"{p}:{np.round(g, 3)}" for p, g in G[(key, n)].items()))
        last = list(MODSETS[key])[-1]
        for n in ("emp 12 bins (baseline)", "emp bins=clip(n/500,2,6)", f"6 bins + scale[{last}]"):
            r = R[(key, n)]
            print(f"  -- {n}: fixed lines (forecast/observed, intercept a, slope b)")
            txt = []
            for ln in LINES[key]:
                p, o = r[f"p>{ln}"].to_numpy(), (r["y"] > ln).to_numpy(float)
                a, b = slope(p, o)
                txt.append(f">{ln}: {p.mean():.3f}/{o.mean():.3f} a={a:+.2f} b={b:.2f}")
            print("    " + "  ".join(txt))
            ex = {"rec_yds": ["adot_slow", "cv_rec"], "rush_yds": ["cv_rush"], "pass_yds": ["cv_pass"]}[key] + ["games_played"]
            print(subgroups(r, ex))
    print(f"{time.time()-t:.0f}s")

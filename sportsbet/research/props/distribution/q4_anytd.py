"""Q4: anytime touchdown. P(score at least once) from a count mean (ridge + NB baseline, log-link GLM + Poisson)
vs a direct logistic regression; plus the 2+ TD probability from the count model vs a direct logistic."""
import numpy as np
import pandas as pd
from nflmodel import props as P
import common as C


class Logit:
    """Ridge-penalised logistic regression on the stage-1 features (volumes logged as in LogGLM)."""

    def __init__(self, lam=5.0, logx=True):
        self.lam, self.logx = lam, logx

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
        self.b = C.logistic_fit((x - self.m) / self.s, y, lam=self.lam)
        return self

    def predict(self, x):
        z = (self._tx(x) - self.m) / self.s
        return C.sigmoid(self.b[0] + z @ self.b[1:])


def collect(folds):
    te = pd.concat([f.te for f in folds])
    return pd.DataFrame({"season": te["season"].to_numpy(), "pos": te["position"].to_numpy(),
                         "week": te["week"].to_numpy(), "mu": np.concatenate([f.mu_te for f in folds])})


def evaluate(name, p, o, seasons, base=None):
    p = np.clip(p, 1e-6, 1 - 1e-6)
    br, ll = (p - o) ** 2, -(o * np.log(p) + (1 - o) * np.log(1 - p))
    d = pd.DataFrame({"season": seasons, "br": br, "ll": ll})
    lp = np.log(p / (1 - p))
    a, b = C.logistic_fit(lp[:, None], o, lam=0.0)
    txt = f"{name:<34} Brier {br.mean():.5f}  logloss {ll.mean():.5f}  mean p {p.mean():.4f} obs {o.mean():.4f}  cal a={a:+.3f} b={b:.3f}"
    if base is not None:
        gb, gn = base.groupby("season")[["br", "ll"]].mean(), d.groupby("season")[["br", "ll"]].mean()
        txt += (f"  dBrier {100*(br.mean()/base.br.mean()-1):+.2f}% {int((gn.br < gb.br).sum())}/{len(gb)}"
                f"  dLL {100*(ll.mean()/base.ll.mean()-1):+.2f}% {int((gn.ll < gb.ll).sum())}/{len(gb)}")
    print(txt)
    return d


if __name__ == "__main__":
    panel = C.get_panel()
    panel["td1"] = (panel["skill_tds"] >= 1).astype(float)
    panel["td2"] = (panel["skill_tds"] >= 2).astype(float)
    prop = P.PROP_BY_KEY["any_td"]
    f_ridge = C.make_folds(panel, prop)
    base = collect(f_ridge)
    y = np.concatenate([f.y_te for f in f_ridge])
    o1, o2 = (y >= 1).astype(float), (y >= 2).astype(float)
    nb = C.run_dist(f_ridge, C.NBGrid(), "pmf", (0.5, 1.5))
    P1, P2 = {"ridge + NB (baseline)": nb["p>0.5"].to_numpy()}, {"ridge + NB (baseline)": nb["p>1.5"].to_numpy()}
    for lam in (1.0, 5.0, 25.0, 100.0):
        f = C.make_folds(panel, prop, mean=C.LogGLM, lam=lam)
        mu = np.concatenate([g.mu_te for g in f])
        P1[f"logGLM lam{lam:g} + Poisson"] = 1 - np.exp(-mu)
        P2[f"logGLM lam{lam:g} + Poisson"] = 1 - np.exp(-mu) * (1 + mu)
        if lam == 25.0:
            r = C.run_dist(f, C.NBGrid(), "pmf", (0.5, 1.5))
            P1["logGLM lam25 + NB grid"], P2["logGLM lam25 + NB grid"] = r["p>0.5"].to_numpy(), r["p>1.5"].to_numpy()
            mu25 = mu
    f = C.make_folds(panel, prop, mean=C.LogGLM, lam=25.0, logx=False)
    P1["logGLM rawx lam25 + Poisson"] = 1 - np.exp(-np.concatenate([g.mu_te for g in f]))
    for lam in (1.0, 5.0, 25.0, 100.0):
        f = C.make_folds(panel, prop, mean=Logit, target="td1", lam=lam)
        P1[f"direct logistic lam{lam:g}"] = np.concatenate([g.mu_te for g in f])
    f = C.make_folds(panel, prop, mean=Logit, target="td1", lam=25.0, logx=False)
    P1["direct logistic rawx lam25"] = np.concatenate([g.mu_te for g in f])
    P1["average(logGLM25 Poisson, logistic25)"] = 0.5 * (P1["logGLM lam25 + Poisson"] + P1["direct logistic lam25"])
    f = C.make_folds(panel, prop, mean=Logit, target="td2", lam=25.0)
    P2["direct logistic lam25 (2+)"] = np.concatenate([g.mu_te for g in f])

    print(f"=== P(at least one rushing or receiving TD), n={len(y)}, test seasons 2012-2026")
    b = None
    for n, p in P1.items():
        d = evaluate(n, p, o1, base["season"].to_numpy(), b)
        b = d if b is None else b
    edges = (0, .05, .1, .15, .2, .3, .4, .5, .6, 1.0001)
    for n in ("ridge + NB (baseline)", "logGLM lam25 + Poisson", "direct logistic lam25"):
        C.print_rel(n, P1[n], o1, edges)
    print("\n  by position: forecast / observed (all rows), and forecast / observed among forecasts under 15%")
    for n in ("ridge + NB (baseline)", "logGLM lam25 + Poisson", "direct logistic lam25"):
        txt = []
        for pos in ("QB", "RB", "WR", "TE"):
            s = (base.pos == pos).to_numpy()
            lo = s & (P1[n] < 0.15)
            txt.append(f"{pos}: {P1[n][s].mean():.3f}/{o1[s].mean():.3f}  <15%: {P1[n][lo].mean():.3f}/{o1[lo].mean():.3f} (n={lo.sum()})")
        print(f"    {n:<26}" + "   ".join(txt))
    print("  early season (weeks 1-4) forecast/observed: " + "  ".join(
        f"{n}: {P1[n][(base.week <= 4).to_numpy()].mean():.3f}/{o1[(base.week <= 4).to_numpy()].mean():.3f}"
        for n in ("ridge + NB (baseline)", "logGLM lam25 + Poisson", "direct logistic lam25")))

    print(f"\n=== P(two or more TDs)")
    b = None
    for n, p in P2.items():
        d = evaluate(n, p, o2, base["season"].to_numpy(), b)
        b = d if b is None else b
    e2 = (0, .02, .04, .06, .08, .1, .15, .2, .3, 1.0001)
    for n in ("ridge + NB (baseline)", "logGLM lam25 + Poisson", "direct logistic lam25 (2+)"):
        C.print_rel(n, P2[n], o2, e2)
    pd.to_pickle({"P1": P1, "P2": P2, "o1": o1, "o2": o2, "base": base}, C.CACHE / "q4.pkl")

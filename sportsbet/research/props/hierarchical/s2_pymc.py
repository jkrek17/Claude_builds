"""Step 2a: PyMC hierarchical normal model of a player's per-game level, fitted on earlier seasons only.

For each stat (targets, receptions, receiving yards; carries, rushing yards) and each test season S, on
seasons FIT_FIRST..S-1:

    y[i, g]  ~ Normal(theta[i], sigma[pos])           game g of player-season i
    theta[i] ~ Normal(mu[pos], tau[pos])              player-season level, pooled within position
    mu[pos] ~ Normal(1, 2);  log tau[pos] ~ Normal(-0.5, 1);  log sigma[pos] ~ Normal(0, 1)   (units of the stat's sd)
(With only two or three positions a further layer of hyperpriors across positions gave divergent NUTS
transitions and no information, so the position-level priors are fixed and weak.)

theta is integrated out exactly (the model is conjugate), so NUTS samples only the hyperparameters:
    ybar[i] ~ Normal(mu, sqrt(tau^2 + sigma^2 / n[i])),   SS[i] ~ Gamma((n[i]-1)/2, 1 / (2 sigma^2)).
The posterior mean of a player's level after n games is (k*mu + sum y) / (k + n) with k = sigma^2 / tau^2,
which is exactly the shipped SHRINK formula with a learned k in place of 3. Output: mu, tau, sigma, k per
stat, position and test season, in pymc_hyper.csv.
"""
import sys, time, warnings, logging
import numpy as np, pandas as pd, pymc as pm
from common import *

warnings.filterwarnings("ignore")
logging.getLogger("pymc").setLevel(logging.ERROR)
STATS = {"targets": "rec_yds", "receptions": "rec_yds", "receiving_yards": "rec_yds",
         "carries": "rush_yds", "rushing_yards": "rush_yds"}     # stat -> prop whose gate picks the players


def fit(panel, stat, season):
    prop = P.PROP_BY_KEY[STATS[stat]]
    d = panel[(panel["season"] < season) & (panel["season"] >= P.FIT_FIRST) & panel["position"].isin(prop.positions)]
    d = d.assign(el=P.eligible(d, prop))
    g = d.groupby(["player_id", "season", "position"])
    u = g.agg(n=(stat, "size"), ybar=(stat, "mean"), var=(stat, lambda s: s.var(ddof=0)), el=("el", "sum")).reset_index()
    u = u[(u["el"] >= 1) & (u["n"] >= 4)]          # player-seasons that got a line, with enough games for a variance
    pos = list(prop.positions)
    pi = u["position"].map({p: i for i, p in enumerate(pos)}).to_numpy()
    n = u["n"].to_numpy(float)
    sc = float(d.loc[d["el"], stat].std())
    yb, ss = u["ybar"].to_numpy() / sc, (u["var"] * u["n"]).to_numpy() / sc ** 2
    with pm.Model():
        mu = pm.Normal("mu", 1.0, 2.0, shape=len(pos))
        tau = pm.Deterministic("tau", pm.math.exp(pm.Normal("ltau", -0.5, 1.0, shape=len(pos))))
        sig = pm.Deterministic("sigma", pm.math.exp(pm.Normal("lsig", 0.0, 1.0, shape=len(pos))))
        pm.Normal("ybar", mu[pi], pm.math.sqrt(tau[pi] ** 2 + sig[pi] ** 2 / n), observed=yb)
        pm.Gamma("ss", alpha=(n - 1) / 2, beta=1 / (2 * sig[pi] ** 2), observed=np.maximum(ss, 1e-6))
        pm.Deterministic("k", sig ** 2 / tau ** 2)
        tr = pm.sample(1000, tune=1000, chains=2, cores=2, random_seed=season, progressbar=False, target_accept=0.9)
    po = tr.posterior
    div = int(tr.sample_stats["diverging"].sum())
    rows = []
    for i, p in enumerate(pos):
        f = lambda v: float(po[v].isel({po[v].dims[-1]: i}).mean())
        rows.append({"stat": stat, "season": season, "position": p, "units": int((pi == i).sum()), "mu": f("mu") * sc,
                     "tau": f("tau") * sc, "sigma": f("sigma") * sc, "k": f("k"),
                     "k_sd": float(po["k"].isel({po["k"].dims[-1]: i}).std()), "divergences": div})
    return rows


if __name__ == "__main__":
    panel = load_panel()
    first = int(sys.argv[1]) if len(sys.argv) > 1 else 2015
    last = int(sys.argv[2]) if len(sys.argv) > 2 else int(panel["season"].max())
    out = []
    for stat in STATS:
        for season in range(first, last + 1):
            t = time.time()
            out += fit(panel, stat, season)
            print(stat, season, [(r["position"], round(r["k"], 2)) for r in out[-len(P.PROP_BY_KEY[STATS[stat]].positions):]],
                  f"{time.time()-t:.0f}s", flush=True)
            pd.DataFrame(out).to_csv(HERE / "pymc_hyper.csv", index=False)

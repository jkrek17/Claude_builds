"""Tune the Bayesian rating filter: python3 research/stochastic/tune.py DATA_DIR

Maximises the one-step-ahead log-likelihood of each channel on seasons 2003-2017 (1999-2002 warm the
filter up). Seasons from 2018 on are never read here; they are the judging window of backtest.py.
Needs scipy. The result is pasted into nflmodel/bayes_ratings.py PARAMS.
"""
from __future__ import annotations

import sys

import numpy as np
import pandas as pd
from scipy.optimize import minimize

from nflmodel import bayes_ratings as br, margin_model as mm

DESIGN = (2003, 2017)
KEYS = ["obs_sd", "prior_sd", "keep", "drift_sd", "qb_sd", "depth_sd"]


def main(data_dir: str) -> None:
    games = pd.read_csv(f"{data_dir}/games.csv")
    games = games[games.season <= DESIGN[1]]
    stats = mm.load_team_stats(data_dir)
    stats = stats[stats.season <= DESIGN[1]]
    for c in br.CHANNELS:
        start = br.PARAMS[c]
        free = [k for k in KEYS if not (c == "rush" and k in ("qb_sd", "depth_sd"))]

        def unpack(v):
            p = dict(start)
            for k, z in zip(free, v):
                p[k] = 1 / (1 + np.exp(-z)) if k == "keep" else abs(z)
            return p

        def loss(v):
            f = br.run_filter(games, stats, params={c: unpack(v)}, channels=(c,))
            w = f[(f.season >= DESIGN[0])]
            return -w[f"loglik_{c}"].sum()

        v0 = [np.log(start[k] / (1 - start[k])) if k == "keep" else start[k] for k in free]
        res = minimize(loss, v0, method="Nelder-Mead", options=dict(xatol=0.01, fatol=0.05, maxiter=400))
        best = unpack(res.x)
        print(c, {k: round(float(v), 3) for k, v in best.items()}, "loglik", round(-res.fun, 2),
              "start", round(-loss(v0), 2), flush=True)


if __name__ == "__main__":
    main(sys.argv[1].rstrip("/"))

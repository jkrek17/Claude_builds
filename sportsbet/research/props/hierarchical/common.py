"""Shared pieces for the hierarchical-shrinkage review: cached panel, candidate model class, row-level scoring.

Nothing here edits nflmodel; candidates are extra columns on a copy of the panel plus a feature list.
"""
from __future__ import annotations

import os
import pickle
import zlib
from pathlib import Path

import numpy as np
import pandas as pd

from nflmodel import props as P

HERE = Path(__file__).resolve().parent
CACHE = Path(os.environ.get("HIER_CACHE", "/tmp/claude-0/-home-user/ae0e1b98-3647-5f0e-bcff-c3f2cfbd80b3/scratchpad/hier_cache"))
CACHE.mkdir(parents=True, exist_ok=True)
DATA = Path.home() / ".cache" / "nflmodel"
KEYS = ("rec_yds", "receptions", "rush_yds")
VOLUME = {"rec_yds": "targets", "receptions": "targets", "rush_yds": "carries"}
SMALL = 8          # "small sample": fewer than this many career games before the one predicted


def load_panel() -> pd.DataFrame:
    f = CACHE / "panel.pkl"
    if f.exists():
        return pd.read_pickle(f)
    games = pd.read_csv(DATA / "games.csv")
    d = str(DATA)
    panel = P.build_panel(P.load_offense(d), games, P.load_snaps(d), P.load_rookie_seasons(d), P.load_lineups(d, games))
    rk = P.load_rookie_seasons(d)
    panel = panel.merge(rk, on="player_id", how="left")
    panel["is_rookie"] = (panel["rookie_season"] == panel["season"]).astype(float)
    # games already played for the current team (pre-game), and whether the player has had an earlier team
    o = panel.sort_values(["player_id", "gameday", "game_id"], kind="mergesort")
    stint = o.groupby("player_id")["team"].transform(lambda s: (s != s.shift()).cumsum())
    panel.loc[o.index, "stint_no"] = stint.to_numpy()
    panel.loc[o.index, "stint_games"] = o.groupby([o["player_id"], stint]).cumcount().to_numpy()
    panel.to_pickle(f)
    return panel


def groups(d: pd.DataFrame) -> dict[str, np.ndarray]:
    """Row groups, all defined from pre-game information."""
    gp = d["games_played"].to_numpy()
    newteam = ((d["stint_no"] > 1) & (d["stint_games"] < SMALL)).to_numpy()
    return {"all": np.ones(len(d), bool), "established (8+ games)": gp >= SMALL, "small (<8 games)": gp < SMALL,
            "rookies": d["is_rookie"].to_numpy() == 1, "rookies <8 games": (d["is_rookie"].to_numpy() == 1) & (gp < SMALL),
            "new team (first 8)": newteam}


class Cand(P.PropModel):
    """PropModel with its own feature list; fitting, spreads and dispersion are the shipped code."""

    def __init__(self, prop, cols):
        super().__init__(prop)
        self.cols = list(cols)

    def _design(self, d):
        return d[self.cols].to_numpy(float)


def crps_quantiles(q, y):
    dd = y - q
    return float(2 * np.mean(np.where(dd >= 0, P.QUANTILES * dd, (P.QUANTILES - 1) * dd)))


def crps_pmf(p, y):
    cdf = np.cumsum(p)
    return float(np.sum((cdf - (np.arange(len(p)) >= y)) ** 2))


def score_rows(model: P.PropModel, d: pd.DataFrame, mu: np.ndarray, scale: np.ndarray, seed: int) -> pd.DataFrame:
    """Per-row CRPS and PIT; same logic as research/props/backtest.py::score. The random draws that spread
    ties are seeded per (prop, season) so every candidate gets the same ones."""
    rng = np.random.default_rng(seed)
    u = rng.random(len(d))
    y = d[model.prop.target].to_numpy(float)
    crps, pit = np.zeros(len(y)), np.zeros(len(y))
    for i, (yi, mi, po, sc) in enumerate(zip(y, mu, d["position"].to_numpy(), scale)):
        if model.prop.kind == "count":
            p = model.pmf(po, mi)
            crps[i] = crps_pmf(p, yi)
            cdf = np.cumsum(p)
            lo = cdf[int(yi) - 1] if yi >= 1 else 0.0
            pit[i] = lo + u[i] * (cdf[min(int(yi), len(cdf) - 1)] - lo)
        else:
            q = model.quantiles(po, mi, sc)
            crps[i] = crps_quantiles(q, yi)
            lo, hi = float(np.mean(q < yi)), float(np.mean(q <= yi))
            pit[i] = lo + u[i] * (hi - lo) if hi - lo > 0.01 else float(np.interp(yi, q, P.QUANTILES, left=0.0, right=1.0))
    return pd.DataFrame({"y": y, "mu": mu, "crps": crps, "pit": pit}, index=d.index)


def seed_for(key: str, season: int) -> int:
    return zlib.crc32(f"{key}-{season}".encode())


def walk(panel: pd.DataFrame, key: str, cols, first: int, last: int, prep=None) -> pd.DataFrame:
    """Walk-forward rows for one candidate. ``cols`` is a feature list or a function (fold panel -> list);
    ``prep(panel, season)`` may return a panel copy with columns that were estimated on seasons < season."""
    prop = P.PROP_BY_KEY[key]
    out = []
    for season in range(first, last + 1):
        pan = prep(panel, season) if prep else panel
        train, test = pan[pan["season"] < season], pan[pan["season"] == season]
        te = test[P.eligible(test, prop)]
        if te.empty:
            continue
        c = cols(pan) if callable(cols) else cols
        m = (Cand(prop, c) if c is not None else P.PropModel(prop)).fit(train)
        r = score_rows(m, te, m.expected(te), m.spread_scale(te), seed_for(key, season))
        r["season"] = season
        out.append(r)
    return pd.concat(out)


def summarise(r: pd.DataFrame) -> dict:
    pit = r["pit"].to_numpy()
    return {"n": len(r), "mae": float(np.mean(np.abs(r["y"] - r["mu"]))), "crps": float(r["crps"].mean()),
            "bias": float(np.mean(r["mu"] - r["y"])), "in50": float(np.mean((pit > 0.25) & (pit < 0.75))),
            "in80": float(np.mean((pit > 0.10) & (pit < 0.90))), "over": float(np.mean(pit > 0.5))}

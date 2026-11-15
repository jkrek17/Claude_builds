"""Re-computable player-history features (the decayed sums of nflmodel.props.build_panel) with knobs."""
from __future__ import annotations

import numpy as np
import pandas as pd

from nflmodel import props as P

STATS = ["carries", "rushing_yards", "targets", "receptions", "receiving_yards", "skill_tds", "touches"]
PSTATS = ["attempts", "completions", "passing_yards", "passing_tds"]


def decayed(keys, x, decay, mult=None):
    """Pre-game decayed sums within runs of equal keys; `mult[i]` scales the memory before game i."""
    out = np.zeros_like(x, dtype=float)
    acc = np.zeros(x.shape[1])
    prev = None
    for i in range(len(keys)):
        if keys[i] != prev:
            acc = np.zeros(x.shape[1])
            prev = keys[i]
        elif mult is not None and mult[i] != 1.0:
            acc = acc * mult[i]
        out[i] = acc
        acc = acc * decay + x[i]
    return out


def order(d):
    """Row order used for player history (player, then date), and the inverse."""
    return np.lexsort((d["game_id"].to_numpy(), d["gameday"].to_numpy(), d["player_id"].to_numpy()))


def break_mult(d, o, off=1.0, team=1.0):
    """Multiplier on a player's memory before each game: `off` at a new season, `team` at a new team."""
    pid = d["player_id"].to_numpy()[o]
    sea = d["season"].to_numpy()[o]
    tm = d["team"].to_numpy()[o]
    same = np.r_[False, pid[1:] == pid[:-1]]
    m = np.ones(len(o))
    m[same & np.r_[False, sea[1:] != sea[:-1]]] *= off
    m[same & np.r_[False, tm[1:] != tm[:-1]]] *= team
    return m


def add_hist(d, name, decay, shrink=P.SHRINK, off=1.0, team=1.0, qb=True):
    """Write {stat}_{name}, new_{name}, tgt_share_{name}, car_share_{name} (and QB passing) into d."""
    o = order(d)
    keys = d["player_id"].to_numpy()[o]
    mult = break_mult(d, o, off, team) if (off != 1.0 or team != 1.0) else None
    tt = d.groupby(["game_id", "team"])["targets"].transform("sum").to_numpy()
    tc = d.groupby(["game_id", "team"])["carries"].transform("sum").to_numpy()
    x = np.column_stack([d[STATS].to_numpy(float), np.ones(len(d)), tt, tc])[o]
    s = decayed(keys, x, decay, mult)
    n = s[:, len(STATS)]
    res = {}
    for j, c in enumerate(STATS):
        res[f"{c}_{name}"] = s[:, j] / (n + shrink)
    res[f"new_{name}"] = shrink / (n + shrink)
    res[f"n_{name}"] = n
    res[f"tgt_share_{name}"] = s[:, 2] / (s[:, len(STATS) + 1] + shrink * 34.0)
    res[f"car_share_{name}"] = s[:, 0] / (s[:, len(STATS) + 2] + shrink * 27.0)
    if qb:
        st = d["starter"].to_numpy()[o]
        xp = np.column_stack([d[PSTATS].to_numpy(float)[o] * st[:, None], st])
        sp = decayed(keys, xp, decay, mult)
        for j, c in enumerate(PSTATS):
            res[f"{c}_{name}"] = sp[:, j] / (sp[:, -1] + shrink)
        res[f"qbnew_{name}"] = shrink / (sp[:, -1] + shrink)
    inv = np.empty(len(o), dtype=int)
    inv[o] = np.arange(len(o))
    for k, v in res.items():
        d[k] = v[inv]
    return d


def rebuild(d, fast=P.FAST, slow=P.SLOW, shrink=P.SHRINK, off=1.0, team=1.0):
    """Copy of the panel with fast/slow history recomputed (exp_targets / exp_carries follow)."""
    d = d.copy()
    add_hist(d, "fast", fast, shrink, off, team)
    add_hist(d, "slow", slow, shrink, off, team)
    d["exp_targets"] = d["tgt_share_slow"] * d["tm_targets"]
    d["exp_carries"] = d["car_share_slow"] * d["tm_carries"]
    return d


if __name__ == "__main__":
    import lib
    pan = lib.base_panel()
    r = rebuild(pan)
    for c in ["targets_fast", "receiving_yards_slow", "tgt_share_slow", "passing_yards_fast", "qbnew_slow", "exp_carries"]:
        print(c, float(np.abs(r[c] - pan[c]).max()))

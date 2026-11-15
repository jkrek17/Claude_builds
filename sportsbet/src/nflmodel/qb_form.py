"""Quarterback form: each starter's own rolling efficiency, and whether he is an in-season backup.

Two pre-game features per game, home minus away:

* ``qb_form_diff``: the starting quarterback's EPA per dropback (passing and rushing, every
  appearance, recency-weighted and shrunk toward a new-quarterback prior), relative to the league.
* ``qb_backup_diff``: 1 when the starter is not the quarterback who has started most of the team's
  games this season.

Both use only games played on earlier calendar dates, plus the announced starter for the game itself.
"""
from __future__ import annotations

import glob
import os
from collections import defaultdict

import numpy as np
import pandas as pd

TEAM_MAP = {"OAK": "LV", "SD": "LAC", "STL": "LA", "JAC": "JAX", "SL": "LA"}
DECAY = 0.97         # per-appearance decay of a quarterback's history
PRIOR_WEIGHT = 200.0  # dropbacks of new-quarterback prior
PRIOR_START = -0.10  # initial EPA per dropback assumed for a quarterback with no history
FEATURES = ["qb_form_diff", "qb_backup_diff"]
_COLS = ["player_id", "position", "game_id", "attempts", "sacks_suffered", "carries",
         "passing_epa", "rushing_epa"]


def load_player_stats(data_dir: str) -> pd.DataFrame:
    """Quarterback rows of nflverse stats_player_week_*.csv."""
    files = sorted(glob.glob(os.path.join(data_dir, "stats_player_week_*.csv")))
    if not files:
        raise FileNotFoundError(f"no stats_player_week_*.csv in {data_dir}")
    parts = []
    for f in files:
        d = pd.read_csv(f, usecols=_COLS, low_memory=False)
        parts.append(d[d["position"] == "QB"])
    return pd.concat(parts, ignore_index=True)


def qb_form_features(games: pd.DataFrame, player_stats: pd.DataFrame) -> pd.DataFrame:
    """One row per game_id with qb_form_diff and qb_backup_diff (home minus away)."""
    g = games.copy()
    g["gameday"] = g["gameday"].astype(str)
    g = g.sort_values(["gameday", "game_id"], kind="mergesort").reset_index(drop=True)
    home = g["home_team"].replace(TEAM_MAP).to_numpy()
    away = g["away_team"].replace(TEAM_MAP).to_numpy()
    played = (pd.to_numeric(g["home_score"], errors="coerce").notna()
              & pd.to_numeric(g["away_score"], errors="coerce").notna()).to_numpy()
    hq, aq = g["home_qb_id"].to_numpy(dtype=object), g["away_qb_id"].to_numpy(dtype=object)
    days, seasons, gids = g["gameday"].to_numpy(), g["season"].to_numpy(), g["game_id"].to_numpy()

    ps = player_stats[player_stats["position"] == "QB"].copy()
    for c in ("attempts", "sacks_suffered", "carries", "passing_epa", "rushing_epa"):
        ps[c] = pd.to_numeric(ps[c], errors="coerce").fillna(0.0)
    ps["dropbacks"] = ps["attempts"] + ps["sacks_suffered"] + ps["carries"]
    ps["epa"] = ps["passing_epa"] + ps["rushing_epa"]
    by_game = defaultdict(list)
    for gid, pid, db, epa in zip(ps["game_id"], ps["player_id"], ps["dropbacks"], ps["epa"]):
        if db > 0:
            by_game[gid].append((pid, db, epa))

    weight, total, career = defaultdict(float), defaultdict(float), defaultdict(float)
    league, prior = 0.0, PRIOR_START
    starters: dict[str, tuple[int, dict]] = {}  # team -> (season, {qb: starts})
    last_qb: dict[str, str] = {}
    form = np.zeros(len(g))
    backup = np.zeros(len(g))

    def side(team, qb, season):
        if not isinstance(qb, str):
            qb = last_qb.get(team)
        if qb is None:
            return prior - league, 0.0, None
        value = (total[qb] + PRIOR_WEIGHT * prior) / (weight[qb] + PRIOR_WEIGHT) - league
        ss = starters.get(team)
        is_backup = 0.0
        if ss and ss[0] == season and sum(ss[1].values()) >= 2:
            is_backup = float(max(ss[1].items(), key=lambda kv: kv[1])[0] != qb)
        return value, is_backup, qb

    i, n = 0, len(g)
    while i < n:
        j = i
        while j < n and days[j] == days[i]:
            j += 1
        todays = []
        for k in range(i, j):
            fh, bh, qh = side(home[k], hq[k], seasons[k])
            fa, ba, qa = side(away[k], aq[k], seasons[k])
            form[k], backup[k] = fh - fa, bh - ba
            todays.append((k, qh, qa))
        for k, qh, qa in todays:  # update only after every game of the day has its features
            if not played[k]:
                continue
            for team, qb in ((home[k], qh), (away[k], qa)):
                if qb is None:
                    continue
                last_qb[team] = qb
                ss = starters.get(team)
                if not ss or ss[0] != seasons[k]:
                    ss = (seasons[k], {})
                    starters[team] = ss
                ss[1][qb] = ss[1].get(qb, 0) + 1
            for pid, db, epa in by_game.get(gids[k], ()):
                if career[pid] < PRIOR_WEIGHT:
                    prior += 0.002 * db / 35.0 * (epa / db - prior)
                league += 0.0005 * db / 35.0 * (epa / db - league)
                weight[pid] = weight[pid] * DECAY + db
                total[pid] = total[pid] * DECAY + epa
                career[pid] += db
        i = j
    return pd.DataFrame({"game_id": gids, "qb_form_diff": form, "qb_backup_diff": backup})

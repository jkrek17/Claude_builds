"""Idea 6: opponent-adjust each player's own history.

Each past game's yardage is divided by (defence ratio faced)^lam, where the ratio is the defence's
opponent-adjusted allowed/expected going INTO that game (Ctx decay 0.92, k 2) -- so nothing later
than the game itself is used. The fast/slow memories are rebuilt from the adjusted numbers with the
package's constants. Also tried: leave the history alone and add the decayed mean log-ratio faced
("schedule strength of the history") as a feature."""
import numpy as np
import pandas as pd
import harness as H
import features as F
from nflmodel import props as P

panel = H.base_panel()
T = F.team_table(panel)
c = F.Ctx(T, 0.92, 2.0)
p = F.attach(panel, T, {"r_pass": c.dfn("passing_yards", True), "r_rush": c.dfn("all_rushing_yards", True),
                        "r_cmp": c.dfn("completions", True), "r_pts": c.dfn("points", True),
                        "r_att": c.dfn("attempts", True), "r_car": c.dfn("all_carries", True)}).fillna(1.0)
p = p.sort_values(["player_id", "gameday", "game_id"], kind="mergesort").reset_index(drop=True)
keys = p["player_id"].to_numpy()
MAP = {"rushing_yards": "r_rush", "receiving_yards": "r_pass", "receptions": "r_cmp", "skill_tds": "r_pts",
       "carries": "r_car", "targets": "r_att"}
PMAP = {"passing_yards": "r_pass", "completions": "r_cmp", "passing_tds": "r_pts", "attempts": "r_att"}
st = p["starter"].to_numpy()
for lam in (0.5, 1.0):
    tag = f"a{int(lam * 10)}"
    x = np.column_stack([p[s].to_numpy(float) / p[r].to_numpy() ** lam for s, r in MAP.items()] + [np.ones(len(p))])
    xp = np.column_stack([p[s].to_numpy(float) * st / p[r].to_numpy() ** lam for s, r in PMAP.items()] + [st])
    for name, decay in (("fast", P.FAST), ("slow", P.SLOW)):
        s = P._decayed(keys, x, decay)
        for j, col in enumerate(MAP):
            p[f"{col}_{name}_{tag}"] = s[:, j] / (s[:, -1] + P.SHRINK)
        s = P._decayed(keys, xp, decay)
        for j, col in enumerate(PMAP):
            p[f"{col}_{name}_{tag}"] = s[:, j] / (s[:, -1] + P.SHRINK)
# schedule strength of the history: decayed mean log ratio faced
lx = np.column_stack([np.log(p[r].to_numpy()) for r in ("r_pass", "r_rush", "r_cmp", "r_pts")] + [np.ones(len(p))])
lxq = np.column_stack([np.log(p[r].to_numpy()) * st for r in ("r_pass", "r_cmp", "r_pts")] + [st])
for name, decay in (("fast", P.FAST), ("slow", P.SLOW)):
    s = P._decayed(keys, lx, decay)
    for j, r in enumerate(("pass", "rush", "cmp", "pts")):
        p[f"sos_{r}_{name}"] = s[:, j] / (s[:, -1] + P.SHRINK)
    s = P._decayed(keys, lxq, decay)
    for j, r in enumerate(("pass", "cmp", "pts")):
        p[f"qsos_{r}_{name}"] = s[:, j] / (s[:, -1] + P.SHRINK)
    # multiplied by the player's level so the correction scales with volume
    p[f"sosX_rush_{name}"] = p[f"sos_rush_{name}"] * p[f"rushing_yards_{name}"]
    p[f"sosX_rec_{name}"] = p[f"sos_pass_{name}"] * p[f"receiving_yards_{name}"]
    p[f"sosX_rcp_{name}"] = p[f"sos_cmp_{name}"] * p[f"receptions_{name}"]
    p[f"sosX_pass_{name}"] = p[f"qsos_pass_{name}"] * p[f"passing_yards_{name}"]
    p[f"sosX_cmp_{name}"] = p[f"qsos_cmp_{name}"] * p[f"completions_{name}"]
    p[f"sosX_ptd_{name}"] = p[f"qsos_pts_{name}"] * p[f"passing_tds_{name}"]
    p[f"sosX_td_{name}"] = p[f"sos_pts_{name}"] * p[f"skill_tds_{name}"]
p = p.sort_values(["gameday", "game_id", "player_id"], kind="mergesort").reset_index(drop=True)
base_sorted = panel
base = H.evaluate(p, P.FEATURES)
rows = []
YARD = ["rushing_yards", "receiving_yards", "passing_yards"]
for tag in ("a5", "a10"):
    for which, cols in (("yardage only", YARD), ("yardage, receptions, completions, TDs", YARD + ["receptions", "completions", "skill_tds", "passing_tds"]),
                        ("all incl. volume", list(MAP) + list(PMAP))):
        feats = {k: [f"{f}_{tag}" if any(f == f"{s}_{n}" for s in cols for n in ("fast", "slow")) else f for f in v]
                 for k, v in P.FEATURES.items()}
        rows.append(H.compare(base, H.evaluate(p, feats), f"replace history with adjusted (lam {tag[1:]}/10), {which}", out=False))
SOS = {"pass_yds": ["sosX_pass_fast", "sosX_pass_slow"], "pass_cmp": ["sosX_cmp_fast", "sosX_cmp_slow"],
       "pass_tds": ["sosX_ptd_fast", "sosX_ptd_slow"], "rush_yds": ["sosX_rush_fast", "sosX_rush_slow"],
       "rec_yds": ["sosX_rec_fast", "sosX_rec_slow"], "receptions": ["sosX_rcp_fast", "sosX_rcp_slow"],
       "any_td": ["sosX_td_fast", "sosX_td_slow"]}
rows.append(H.compare(base[base.prop.isin(list(SOS))], H.evaluate(p, H.with_extra(SOS), list(SOS)), "+schedule strength x level (fast, slow)", out=False))
SOS2 = {"pass_yds": ["qsos_pass_fast", "qsos_pass_slow"], "pass_cmp": ["qsos_cmp_fast", "qsos_cmp_slow"],
        "pass_tds": ["qsos_pts_fast", "qsos_pts_slow"], "rush_yds": ["sos_rush_fast", "sos_rush_slow"],
        "rec_yds": ["sos_pass_fast", "sos_pass_slow"], "receptions": ["sos_cmp_fast", "sos_cmp_slow"],
        "any_td": ["sos_pts_fast", "sos_pts_slow"]}
rows.append(H.compare(base[base.prop.isin(list(SOS2))], H.evaluate(p, H.with_extra(SOS2), list(SOS2)), "+schedule strength (fast, slow), additive", out=False))
H.pivot(rows, H.HERE / "out_exp6.csv")

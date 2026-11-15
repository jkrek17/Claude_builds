"""Line dependence, continued: do opponent terms matter more without a line, and does the adopt set
(exp9 A+B+C+D) survive when the line is the nflmodel prediction instead of the close?"""
import numpy as np
import pandas as pd
import harness as H
import exp9_adopt as A
from nflmodel import props as P

p = A.build()
pr = pd.read_pickle(H.HERE / "cache" / "preds.pkl")
pr["home_team"] = pr["home_team"].replace(P.TEAM_MAP); pr["away_team"] = pr["away_team"].replace(P.TEAM_MAP)
s = pd.concat([pd.DataFrame({"game_id": pr.game_id, "team": pr.home_team, "m_fav": pr.pred_margin, "m_tot": pr.pred_total}),
               pd.DataFrame({"game_id": pr.game_id, "team": pr.away_team, "m_fav": -pr.pred_margin, "m_tot": pr.pred_total})])
p = p.merge(s, on=["game_id", "team"], how="left", validate="m:1")
q = p.copy()
q["fav"] = q["m_fav"].fillna(0.0); q["implied_pts"] = (q["m_tot"] / 2 + q["m_fav"] / 2).fillna(22.0)
for k, lv in A.LEVEL.items():
    q[f"impX_{k}"] = (q["implied_pts"] - 22) * q[lv]
live = ["A implied x level", "B defence completion rate allowed", "C offence rushing EPA per game", "D starter QB yards x receiver level"]
ad = H.with_extra(A.merged(live))
DEF = ("def_pass_ypa", "def_rush_ypc", "def_pass_att", "def_rush_att", "def_pass_td", "def_rush_td")
LINE = ("implied_pts", "fav")
base = H.evaluate(p, P.FEATURES)
rows = []
noline = H.evaluate(p, H.with_extra({}, drop=LINE))
rows.append(H.compare(base, noline, "[vs close baseline] no line", out=False))
rows.append(H.compare(noline, H.evaluate(p, H.with_extra({}, drop=LINE + DEF)), "[vs no-line] also drop def_* terms", out=False))
nf = H.evaluate(q, P.FEATURES)
rows.append(H.compare(base, nf, "[vs close baseline] nflmodel line, baseline features", out=False))
rows.append(H.compare(nf, H.evaluate(q, ad), "[vs nflmodel-line baseline] + adopt set A+B+C+D", out=False))
rows.append(H.compare(base, H.evaluate(q, ad), "[vs close baseline] nflmodel line + adopt set", out=False))
B_CD = H.with_extra(A.merged(live[1:]))
rows.append(H.compare(noline, H.evaluate(p, {k: [f for f in v if f not in LINE] for k, v in B_CD.items()}), "[vs no-line] + B+C+D (no line needed)", out=False))
H.pivot(rows, H.HERE / "out_exp7b.csv")

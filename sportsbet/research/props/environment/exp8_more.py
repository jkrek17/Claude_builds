"""Own ideas and neighbour checks: defence x level, team scoring vs implied, starting-QB quality for
receivers/backs, and decay/shrink neighbours for the two team features that looked alive in exp3."""
import numpy as np
import pandas as pd
import harness as H
import features as F
from nflmodel import props as P

panel = H.base_panel()
T = F.team_table(panel)
ALL = list(P.FEATURES)
rows = []
LEVEL = {"pass_yds": "passing_yards_slow", "pass_tds": "passing_tds_slow", "pass_att": "attempts_slow",
         "pass_cmp": "completions_slow", "rush_yds": "rushing_yards_slow", "rec_yds": "receiving_yards_slow",
         "receptions": "receptions_slow", "any_td": "skill_tds_slow"}

# neighbours
base = H.evaluate(panel, P.FEATURES)
for decay, k in ((0.8, 1), (0.85, 1), (0.85, 2), (0.92, 1), (0.92, 2), (0.92, 4), (0.96, 2), (0.96, 4), (0.98, 4)):
    c = F.Ctx(T, decay, k)
    p = F.attach(panel, T, {"d_cmp_rate": c.dfn_rate("completions", "attempts"), "o_rush_epa": c.off("rushing_epa"),
                            "o_plays": c.off("plays")}).fillna(0.0)
    r = H.evaluate(p, H.with_extra({"pass_cmp": ["d_cmp_rate"], "receptions": ["d_cmp_rate"]}), ["pass_cmp", "receptions"])
    a = H.compare(base[base.prop.isin(["pass_cmp", "receptions"])], r, f"d={decay} k={k}: +d_cmp_rate / +o_rush_epa / +o_plays", out=False)
    r = H.evaluate(p, H.with_extra({"pass_yds": ["o_rush_epa"], "pass_att": ["o_rush_epa"]}), ["pass_yds", "pass_att"])
    b = H.compare(base[base.prop.isin(["pass_yds", "pass_att"])], r, f"d={decay} k={k}: +d_cmp_rate / +o_rush_epa / +o_plays", out=False)
    rows += [a, b]
H.pivot(rows)
rows = []

c = F.Ctx(T, 0.92, 2.0)
pf = c.off("points")
p = F.attach(panel, T, {"pf": pf, "own_pa": F.opp_side(T, c.dfn("points") * c.L["points"])}).fillna(22.0)
p["imp_minus_pf"] = p["implied_pts"] - p["pf"]
# defence x level
p["dX_pass_yds"] = (p["def_pass_ypa"] - 1) * p["passing_yards_slow"]
p["dX_rec_yds"] = (p["def_pass_ypa"] - 1) * p["receiving_yards_slow"]
p["dX_rush_yds"] = (p["def_rush_ypc"] - 1) * p["rushing_yards_slow"]
p["dX_receptions"] = (p["def_pass_att"] - 1) * p["receptions_slow"]
p["dX_pass_att"] = (p["def_pass_att"] - 1) * p["attempts_slow"]
p["dX_pass_cmp"] = (p["def_pass_att"] - 1) * p["completions_slow"]
p["dX_pass_tds"] = p["def_pass_td"] * p["passing_tds_slow"]
p["dX_any_td"] = (p["def_pass_td"] + p["def_rush_td"]) * p["skill_tds_slow"]
# starting QB quality seen by his team-mates (announced starter is known before kickoff)
q = p[p["starter"] > 0][["game_id", "team", "passing_yards_slow", "passing_yards_fast", "attempts_slow", "qbnew_slow", "passing_tds_slow"]]
q = q.drop_duplicates(["game_id", "team"]).rename(columns={"passing_yards_slow": "qb_yds", "passing_yards_fast": "qb_yds_fast",
                                                           "attempts_slow": "qb_att", "qbnew_slow": "qb_new", "passing_tds_slow": "qb_tds"})
p = p.merge(q, on=["game_id", "team"], how="left", validate="m:1")
p["qb_known"] = p["qb_yds"].notna().astype(float)
for col in ("qb_yds", "qb_yds_fast", "qb_att", "qb_tds"):
    p[col] = p[col].fillna(p["tm_passing_yards" if "yds" in col else ("tm_attempts" if col == "qb_att" else "tm_passing_tds")])
p["qb_new"] = p["qb_new"].fillna(1.0)
p["qb_delta"] = p["qb_yds"] - p["tm_passing_yards"] * (1 - p["qb_new"])   # starter's level vs what the team has been throwing for
p["qbX_rec"] = (p["qb_yds"] - 200) * p["receiving_yards_slow"] / 100
for k, lv in LEVEL.items():
    p[f"impX_{k}"] = (p["implied_pts"] - 22) * p[lv]

base = H.evaluate(p, P.FEATURES)
def test(label, extra, drop=()):
    rows.append(H.compare(base[base.prop.isin(list(extra))], H.evaluate(p, H.with_extra(extra, drop=drop), list(extra)), label, out=False))
test("drop every def_* feature (value of today's opponent terms)", {k: [] for k in ALL},
     drop=("def_pass_ypa", "def_rush_ypc", "def_pass_att", "def_rush_att", "def_pass_td", "def_rush_td"))
test("+defence x level", {k: [f"dX_{k}"] for k in ALL})
test("+team points for (decayed)", {k: ["pf"] for k in ALL})
test("+team points for + own defence points allowed", {k: ["pf", "own_pa"] for k in ALL})
SK = ["rec_yds", "receptions", "rush_yds", "any_td"]
test("+starter QB yards, new-ness (skill props)", {k: ["qb_yds", "qb_new"] for k in SK})
test("+starter QB yards fast+slow, tds, new", {k: ["qb_yds", "qb_yds_fast", "qb_tds", "qb_new"] for k in SK})
test("+QB delta vs team", {k: ["qb_delta"] for k in SK})
test("+QB yards x receiver level", {k: ["qbX_rec"] for k in ["rec_yds", "receptions"]})
test("+implied x level", {k: [f"impX_{k}"] for k in ALL})
H.pivot(rows, H.HERE / "out_exp8.csv")

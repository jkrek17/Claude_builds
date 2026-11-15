"""Idea 1: defence versus position, raw and opponent-adjusted, with decay / shrink neighbours."""
import sys
import numpy as np
import pandas as pd
import harness as H
import features as F
from nflmodel import props as P

panel = H.base_panel()
T = F.team_table(panel)
PROPS = ["rec_yds", "receptions", "rush_yds", "any_td", "pass_yds", "pass_tds", "pass_att", "pass_cmp"]
base = H.evaluate(panel, P.FEATURES, PROPS)
rows = []


def build(decay, k):
    c = F.Ctx(T, decay, k)
    cols = {}
    for adj, tag in ((False, "raw"), (True, "adj")):
        for pos in F.POS:
            for st in F.PSTATS:
                cols[f"d_{tag}_{pos}_{st}"] = c.dfn(f"{pos}_{st}", adj)
            cols[f"d_{tag}_{pos}_ypt"] = c.dfn_rate(f"{pos}_receiving_yards", f"{pos}_targets", adj)
            cols[f"d_{tag}_{pos}_cr"] = c.dfn_rate(f"{pos}_receptions", f"{pos}_targets", adj)
            cols[f"d_{tag}_{pos}_ypc"] = c.dfn_rate(f"{pos}_rushing_yards", f"{pos}_carries", adj)
        for st in ["passing_yards", "attempts", "completions", "passing_tds", "all_rushing_yards", "all_carries",
                   "all_skill_tds", "all_receptions", "points"]:
            cols[f"d_{tag}_tm_{st}"] = c.dfn(st, adj)
        cols[f"d_{tag}_tm_ypa"] = c.dfn_rate("passing_yards", "attempts", adj)
        cols[f"d_{tag}_tm_cmpr"] = c.dfn_rate("completions", "attempts", adj)
        cols[f"d_{tag}_tm_ypc"] = c.dfn_rate("all_rushing_yards", "all_carries", adj)
    p = F.attach(panel, T, cols)
    for tag in ("raw", "adj"):
        for st in F.PSTATS + ["ypt", "cr", "ypc"]:
            F.by_position(p, f"dvp_{tag}_{st}", "d_" + tag + "_{pos}_" + st)
    return p.fillna(1.0)


def test(p, label, extra, drop=()):
    r = H.evaluate(p, H.with_extra(extra, drop=drop), list(extra))
    d = H.compare(base[base.prop.isin(list(extra))], r, label, out=False)
    rows.append(d)



for decay, k in ((0.85, 1), (0.92, 2), (0.92, 4), (0.96, 4), (0.96, 8), (0.98, 8)):
    p = build(decay, k)
    for tag in ("raw", "adj"):
        L = f"{tag} d={decay} k={k}"
        # position split, added to the baseline team-level ratios
        test(p, f"dvp yards+vol {L}", {
            "rec_yds": [f"dvp_{tag}_receiving_yards"], "receptions": [f"dvp_{tag}_receptions"],
            "rush_yds": [f"dvp_{tag}_rushing_yards"], "any_td": [f"dvp_{tag}_skill_tds"]})
        test(p, f"dvp rate+targets {L}", {
            "rec_yds": [f"dvp_{tag}_ypt", f"dvp_{tag}_targets"], "receptions": [f"dvp_{tag}_cr", f"dvp_{tag}_targets"],
            "rush_yds": [f"dvp_{tag}_ypc", f"dvp_{tag}_carries"],
            "any_td": [f"dvp_{tag}_rushing_tds", f"dvp_{tag}_receiving_tds"]})
        # team-level (not position split) version of the same construction, replacing the baseline ratios
        test(p, f"team-level replace {L}", {
            "rec_yds": [f"d_{tag}_tm_ypa", f"d_{tag}_tm_attempts"], "receptions": [f"d_{tag}_tm_attempts", f"d_{tag}_tm_cmpr"],
            "rush_yds": [f"d_{tag}_tm_ypc", f"d_{tag}_tm_all_carries"], "any_td": [f"d_{tag}_tm_all_skill_tds"],
            "pass_yds": [f"d_{tag}_tm_ypa", f"d_{tag}_tm_attempts"], "pass_tds": [f"d_{tag}_tm_passing_tds", f"d_{tag}_tm_ypa"],
            "pass_att": [f"d_{tag}_tm_attempts", f"d_{tag}_tm_all_carries"], "pass_cmp": [f"d_{tag}_tm_attempts", f"d_{tag}_tm_cmpr"]},
            drop=("def_pass_ypa", "def_rush_ypc", "def_pass_att", "def_rush_att", "def_pass_td", "def_rush_td"))
H.pivot(rows, H.HERE / "out_exp1.csv")

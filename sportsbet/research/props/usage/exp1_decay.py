"""Idea 1: decay rates, SHRINK, a third horizon, offseason / team-change decay.

Eligible rows are frozen at the baseline gates, so only the features move.
Prints pooled dCRPS% per prop (negative = better) and seasons improved out of 15.
"""
import sys
import numpy as np, pandas as pd
import lib, hist
from nflmodel import props as P

pan = lib.base_panel()
base = lib.evaluate(pan)
PR = [p.key for p in P.PROPS]


def line(label, res):
    c = lib.compare(base, res, quiet=True)
    print(f"{label:<34}" + " ".join(f"{c.loc[p,'dCRPS%']:+6.2f}({c.loc[p,'crps_seas'].split('/')[0]:>2})" for p in PR), flush=True)
    return c

print(f"{'variant: dCRPS% (seasons better)':<34}" + " ".join(f"{p:>10}" for p in PR))
which = sys.argv[1] if len(sys.argv) > 1 else "all"
if which in ("all", "grid"):
    for f in (0.5, 0.6, 0.7, 0.8, 0.85):
        line(f"FAST={f}", lib.evaluate(hist.rebuild(pan, fast=f)))
    for s in (0.90, 0.92, 0.96, 0.975, 0.99):
        line(f"SLOW={s}", lib.evaluate(hist.rebuild(pan, slow=s)))
    for k in (1.0, 2.0, 5.0, 8.0):
        line(f"SHRINK={k}", lib.evaluate(hist.rebuild(pan, shrink=k)))
if which in ("all", "off"):
    for off in (0.3, 0.5, 0.7, 0.85):
        line(f"offseason x{off}", lib.evaluate(hist.rebuild(pan, off=off)))
    for tm in (0.3, 0.5, 0.7):
        line(f"team change x{tm}", lib.evaluate(hist.rebuild(pan, team=tm)))
    line("off 0.7 + team 0.5", lib.evaluate(hist.rebuild(pan, off=0.7, team=0.5)))
if which in ("all", "third"):
    # third horizon: add a very fast (last ~2 games) or very slow (career) memory as extra features
    for nm, dec in (("vf", 0.4), ("vf", 0.55), ("mid", 0.87), ("car", 0.985), ("car", 1.0)):
        d = pan.copy()
        hist.add_hist(d, nm, dec)
        ex = {"pass_yds": [f"passing_yards_{nm}"], "pass_tds": [f"passing_tds_{nm}"], "pass_att": [f"attempts_{nm}"],
              "pass_cmp": [f"completions_{nm}"], "rush_yds": [f"rushing_yards_{nm}", f"carries_{nm}"],
              "rec_yds": [f"receiving_yards_{nm}", f"targets_{nm}"], "receptions": [f"receptions_{nm}", f"targets_{nm}"],
              "any_td": [f"skill_tds_{nm}", f"touches_{nm}"]}
        line(f"third horizon decay={dec}", lib.evaluate(d, lib.with_feats(ex)))

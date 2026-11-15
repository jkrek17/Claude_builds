"""Step 1: is the shipped model biased or miscalibrated for players with little history?"""
import numpy as np, pandas as pd
from common import *

panel = load_panel()
for key in KEYS:
    r = pd.read_pickle(CACHE / f"rows_{key}_current.pkl")
    d = panel.loc[r.index]
    print(f"\n== {key}: shipped model, test seasons 2015-{int(r.season.max())}")
    print(f"{'group':<24}{'n':>7}{'mean y':>8}{'MAE':>8}{'CRPS':>8}{'bias':>7}{'se':>6}{'in50':>7}{'in80':>7}{'>med':>7}{'se':>7}  bias>0 seasons")
    def line(name, sel):
        g = r[sel]
        if len(g) < 30:
            return
        s = summarise(g)
        err = g["mu"] - g["y"]
        bs = g.assign(e=err).groupby("season")["e"].mean()
        print(f"{name:<24}{s['n']:>7}{g['y'].mean():>8.2f}{s['mae']:>8.2f}{s['crps']:>8.3f}{s['bias']:>7.2f}{err.std()/np.sqrt(len(g)):>6.2f}"
              f"{s['in50']:>7.3f}{s['in80']:>7.3f}{s['over']:>7.3f}{0.5/np.sqrt(len(g)):>7.3f}  {int((bs>0).sum())} of {len(bs)}")
    for name, sel in groups(d).items():
        line(name, sel)
    gp = d["games_played"].to_numpy()
    for lo, hi in ((1, 1), (2, 2), (3, 3), (4, 5), (6, 7), (8, 15), (16, 31), (32, 999)):
        line(f"  career games {lo}-{hi}", (gp >= lo) & (gp <= hi))
    for pos in P.PROP_BY_KEY[key].positions:
        line(f"  small, {pos}", (gp < SMALL) & (d["position"] == pos).to_numpy())
    line("  first game, new team", (d["new_team"] == 1).to_numpy())
    line("  first game of season", (d["first_of_season"] == 1).to_numpy())

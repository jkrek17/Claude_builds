"""Step 4: tables. Every candidate against the shipped model on identical rows, overall and by history group."""
import numpy as np, pandas as pd
from common import *

panel = load_panel()
SHOW = ("all", "established (8+ games)", "small (<8 games)", "rookies", "new team (first 8)")
for key in KEYS:
    cur = pd.read_pickle(CACHE / f"rows_{key}_current.pkl")
    d = panel.loc[cur.index]
    G = groups(d)
    files = sorted(CACHE.glob(f"rows_{key}_*.pkl"))
    cands = {"current model": cur}
    for f in files:
        nm = f.stem[len(f"rows_{key}_"):].replace("_", " ")
        if nm != "current":
            r = pd.read_pickle(f)
            assert r.index.equals(cur.index)
            cands[nm] = r
    for g in SHOW:
        sel = G[g]
        base = cur[sel]
        print(f"\n== {key} | {g} | n={int(sel.sum())} | seasons {int(cur.season.min())}-{int(cur.season.max())}")
        print(f"{'forecast':<30}{'MAE':>8}{'CRPS':>9}{'dCRPS%':>8}{'+-se':>6}{'bias':>7}{'in50':>7}{'in80':>7}{'>med':>7}  seasons better")
        bs = base.groupby("season")["crps"].mean()
        for nm, r in cands.items():
            x = r[sel]
            s = summarise(x)
            if nm == "current model":
                print(f"{nm:<30}{s['mae']:>8.3f}{s['crps']:>9.4f}{'':>8}{'':>6}{s['bias']:>7.2f}{s['in50']:>7.3f}{s['in80']:>7.3f}{s['over']:>7.3f}")
                continue
            diff = (x["crps"] - base["crps"])
            xs = x.groupby("season")["crps"].mean()
            print(f"{nm:<30}{s['mae']:>8.3f}{s['crps']:>9.4f}{100*diff.mean()/base['crps'].mean():>+8.2f}"
                  f"{100*diff.std()/np.sqrt(len(diff))/base['crps'].mean():>6.2f}{s['bias']:>7.2f}{s['in50']:>7.3f}{s['in80']:>7.3f}{s['over']:>7.3f}"
                  f"  {int((xs < bs).sum())} of {len(bs)}")

"""Q1 (continued): does the ridge need an early-season term? Baseline calibration showed the passing props
under-forecast in weeks 1-4. Adds 'early' (weeks 1-4) and/or week number to the ridge features."""
import numpy as np
import pandas as pd
from multiprocessing import Pool
from nflmodel import props as P
import common as C

VAR = {"ridge (baseline)": [], "+early": ["early"], "+early+week": ["early", "wk"], "+early+early*slow": ["early", "early_x"]}


def one(args):
    key, name = args
    panel = C.get_panel()
    prop = P.PROP_BY_KEY[key]
    panel = panel.assign(wk=np.minimum(panel["week"], 18).astype(float))
    slow = P.FEATURES[key][1] if key != "any_td" else "skill_tds_slow"
    panel["early_x"] = panel["early"] * panel[slow]
    folds = C.make_folds(panel, prop, feats=P.FEATURES[key] + VAR[name])
    r = C.run_dist(folds, C.EmpRatio() if prop.kind == "yards" else C.NBGrid(), "q" if prop.kind == "yards" else "pmf")
    return key, name, r


if __name__ == "__main__":
    C.get_panel()
    with Pool(4) as pool:
        res = pool.map(one, [(p.key, n) for p in P.PROPS for n in VAR], chunksize=1)
    R = {(k, n): r for k, n, r in res}
    for prop in P.PROPS:
        base = R[(prop.key, "ridge (baseline)")]
        print(f"\n=== {prop.key}")
        print(f"{'variant':<22}{'CRPS':>9}{'dCRPS%':>8}{'won':>7}   weeks 1-4: bias(act-exp)  >p50   | weeks 5+: bias  >p50")
        for n in VAR:
            r = R[(prop.key, n)]
            c, w, k = C.compare(base, r)
            e, l = r[r.week <= 4], r[r.week > 4]
            print(f"{n:<22}{r.crps.mean():>9.4f}{c:>8.2f}{w:>4}/{k}   {np.mean(e.y-e.mu):>+10.3f} {np.mean(e.pit>.5):>12.3f}   | {np.mean(l.y-l.mu):>+10.3f} {np.mean(l.pit>.5):>7.3f}")

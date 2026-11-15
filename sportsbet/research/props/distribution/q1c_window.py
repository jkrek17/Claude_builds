"""Q1 (continued): does a rolling training window (both stages) beat using every earlier season? Motivated by the
passing-yards over-forecast since 2019."""
import numpy as np
from multiprocessing import Pool
from nflmodel import props as P
import common as C

WIN = (None, 12, 8, 5)


def one(args):
    key, w = args
    prop = P.PROP_BY_KEY[key]
    folds = C.make_folds(C.get_panel(), prop, window=w)
    r = C.run_dist(folds, C.EmpRatio(nbins=6) if prop.kind == "yards" else C.NBGrid(), "q" if prop.kind == "yards" else "pmf")
    return key, w, r


if __name__ == "__main__":
    C.get_panel()
    with Pool(4) as pool:
        res = pool.map(one, [(p.key, w) for p in P.PROPS for w in WIN], chunksize=1)
    R = {(k, w): r for k, w, r in res}
    for prop in P.PROPS:
        base = R[(prop.key, None)]
        print(f"\n=== {prop.key}   (seasons 2017+ only for the 'won' count, where an 8-12 season window first differs)")
        for w in WIN:
            r = R[(prop.key, w)]
            c, won, k = C.compare(base[base.season >= 2017], r[r.season >= 2017])
            l = r[r.season >= 2019]
            print(f"  window {str(w or 'all'):<4} CRPS {r.crps.mean():.4f}  2017+: {c:+.2f}% {won}/{k}   2019-2026 bias(act-exp) {np.mean(l.y-l.mu):+.3f}  >p50 {np.mean(l.pit>.5):.3f} >p90 {np.mean(l.pit>.9):.3f}")

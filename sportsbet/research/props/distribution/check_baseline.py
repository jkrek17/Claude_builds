"""Reproduce the baseline (research/props/backtest.py) with the vectorised machinery; adds randomised-PIT calibration."""
import time
import numpy as np
from nflmodel import props as P
import common as C

if __name__ == "__main__":
    t = time.time()
    panel = C.get_panel()
    print("panel", panel.shape, f"{time.time()-t:.1f}s", "season types", panel["season_type"].value_counts().to_dict())
    print(C.HDR)
    for prop in P.PROPS:
        folds = C.make_folds(panel, prop)
        if prop.kind == "yards":
            r = C.run_dist(folds, C.EmpRatio(), "q")
        else:
            r = C.run_dist(folds, C.NBGrid(), "pmf")
            mp = r["midpit"]
            extra = f"  midPIT in50 {np.mean((mp>.25)&(mp<.75)):.3f} in80 {np.mean((mp>.1)&(mp<.9)):.3f}"
        print(C.line_fmt(prop.key, r), f" n={len(r)} bias {np.mean(r.mu-r.y):+.2f}", extra if prop.kind == "count" else "")
    print(f"{time.time()-t:.1f}s")

"""Step 0: the shipped model walk-forward, saved per row; checks it reproduces backtest_output.txt."""
import sys, time
import pandas as pd
from common import *

t = time.time()
panel = load_panel()
print("panel", panel.shape, f"{time.time()-t:.0f}s", "seasons", panel.season.min(), panel.season.max())
last = int(panel["season"].max())
for key in KEYS:
    t = time.time()
    r = walk(panel, key, None, 2015, last)
    r.to_pickle(CACHE / f"rows_{key}_current.pkl")
    s = summarise(r)
    print(key, {k: round(v, 4) for k, v in s.items()}, f"{time.time()-t:.0f}s")

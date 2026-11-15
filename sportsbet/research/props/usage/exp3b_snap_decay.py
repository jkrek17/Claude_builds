"""Idea 3 neighbours: how fast should the snap-share memory be? (decay 0 = last game only)."""
import numpy as np
import feats, t
d = feats.build()
PR = ["rush_yds", "rec_yds", "receptions", "any_td"]
has = d["has_snap"].to_numpy()
for dec in (0.0, 0.2, 0.35, 0.5, 0.65):
    a, n = feats.dec_avg(d, ["snap_pct"], dec, valid=has)
    d[f"snap_d{dec}"] = np.where(n > 0, a[:, 0], 0.0)
d[["snap_fast", "snap_slow"]] = d[["snap_fast", "snap_slow"]].fillna(0.0)
T = t.Tester(d, props=PR, first=2015, train_first=2013)
for dec in (0.0, 0.2, 0.35, 0.5, 0.65):
    T.run(f"snap decay {dec} alone", {p: [f"snap_d{dec}"] for p in PR})
for dec in (0.0, 0.2, 0.35, 0.5):
    T.run(f"snap decay {dec} + slow", {p: [f"snap_d{dec}", "snap_slow"] for p in PR})

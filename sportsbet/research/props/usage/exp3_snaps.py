"""Idea 3: offensive snap share (nflverse snap_counts, 2013+). Train from 2013, test 2015-2026 (12 seasons).

snap_fast/slow = decayed mean offense_pct over earlier games (decay .75/.94); snap_last = previous game;
tprr_slow = decayed targets / (decayed snap_pct x team attempts + 30); exp_tgt_snap = tprr_slow x snap_fast x tm_attempts;
cps / exp_car_snap the same with carries (prior 25 snaps).
"""
import feats, t
d = feats.build()
d[["snap_fast", "snap_slow", "snap_last", "snap_trend", "snapsh_fast", "snapsh_slow"]] = d[["snap_fast", "snap_slow", "snap_last", "snap_trend", "snapsh_fast", "snapsh_slow"]].fillna(0.0)
PR = ["rush_yds", "rec_yds", "receptions", "any_td"]
T = t.Tester(d, props=PR, first=2015, train_first=2013)
def same(cols, extra_rush=(), extra_rec=()):
    return {"rush_yds": cols + list(extra_rush), "rec_yds": cols + list(extra_rec), "receptions": cols + list(extra_rec), "any_td": cols + list(extra_rush) + list(extra_rec)}
T.run("snap_slow", same(["snap_slow"]))
T.run("snap_fast", same(["snap_fast"]))
T.run("snap_last", same(["snap_last"]))
T.run("snap_fast + snap_slow", same(["snap_fast", "snap_slow"]))
T.run("snap_fast + slow + last", same(["snap_fast", "snap_slow", "snap_last"]))
T.run("snapsh (shrunk) fast + slow", same(["snapsh_fast", "snapsh_slow"]))
T.run("per-snap rates only", same([], ["cps_slow", "exp_car_snap"], ["tprr_slow", "exp_tgt_snap"]))
T.run("snap f+s + per-snap rates", same(["snap_fast", "snap_slow"], ["cps_slow", "exp_car_snap"], ["tprr_slow", "exp_tgt_snap"]))
T.run("snap f+s+last + rates + xlast", same(["snap_fast", "snap_slow", "snap_last"], ["cps_slow", "exp_car_snap", "exp_car_snaplast"], ["tprr_slow", "exp_tgt_snap", "exp_tgt_snaplast"]))

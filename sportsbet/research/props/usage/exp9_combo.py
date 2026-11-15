"""Stacking the pieces that passed on their own. Each tier adds a data requirement.

Tier A (weekly stat files only): memory x0.7 at a new season and x0.5 at a team change (non-QB history),
    absence flags (missed_games, missed_any, late_start, first_of_season, new_team), exp_years,
    vac_*_prev_fast (competition vs last week's lineup); rush_yds also gets a last-game horizon (decay 0.5).
Tier B (+ nflverse snap_counts): snap_last, snap_slow.
Tier C (+ inactives list): vac_*_now_fast replaces the prev version (row-based here; exp2b shows the
    roster-status version scores the same).
"""
import sys
import feats, t, hist
d0 = feats.build()
d0[["snap_last", "snap_slow"]] = d0[["snap_last", "snap_slow"]].fillna(0.0)
d = hist.rebuild(d0, off=0.7, team=0.5)
hist.add_hist(d, "vf", 0.5, off=0.7, team=0.5, qb=False)
PR = ["rush_yds", "rec_yds", "receptions", "any_td"]
ABS = ["missed_games", "missed_any", "late_start", "first_of_season", "new_team", "exp_years"]
VF = {"rush_yds": ["rushing_yards_vf", "carries_vf"]}
def vac(v):
    tg, cr = [f"vac_tgt_{v}_fast"], [f"vac_car_{v}_fast"]
    return {"rec_yds": tg, "receptions": tg, "rush_yds": cr, "any_td": tg + cr}
def mk(*parts):
    out = {p: [] for p in PR}
    for part in parts:
        for p in PR:
            out[p] = out[p] + (part.get(p, []) if isinstance(part, dict) else list(part))
    return out
SN = ["snap_last", "snap_slow"]
for label, kw in (("ALL 15 test seasons 2012-2026", {}), ("snap window: train from 2013, test 2015-2026", {"first": 2015, "train_first": 2013})):
    print("\n==", label)
    T = t.Tester(d0, props=PR, **kw)
    T.run("memory breaks only", panel=d)
    T.run("A: breaks + absence + exp_years", mk(ABS), panel=d)
    T.run("A: ... + rush last-game horizon", mk(ABS, VF), panel=d)
    T.run("A: ... + vac prev", mk(ABS, VF, vac("prev")), panel=d)
    T.run("C-without-snaps: A + vac now", mk(ABS, VF, vac("now")), panel=d)
    if kw:
        T.run("B: A + snaps", mk(ABS, VF, vac("prev"), SN), panel=d)
        T.run("B minus memory breaks", mk(ABS, VF, vac("prev"), SN), panel=d0.assign(rushing_yards_vf=d["rushing_yards_vf"], carries_vf=d["carries_vf"]))
        T.run("C: A + snaps + vac now", mk(ABS, VF, vac("now"), SN), panel=d)
        T.run("C + vac prev too", mk(ABS, VF, vac("now"), vac("prev"), SN), panel=d)
        T.run("snaps alone", mk(SN))
        T.run("vac now alone", mk(vac("now")))

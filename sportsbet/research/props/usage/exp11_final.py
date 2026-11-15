"""Headline numbers for the recommended spec, per data tier (prop-specific pieces after exp9b).

All non-QB props: player memory x0.7 at a new season and x0.5 at a team change.
rec_yds, receptions: + missed_games, missed_any, late_start, first_of_season, new_team, exp_years
rush_yds: + rushing_yards / carries with decay 0.5 (last-game horizon)
Tier A adds vac_*_prev_fast; Tier B adds snap_last + snap_slow; Tier C swaps prev for vac_*_now_fast.
"""
import feats, t, hist, lib
d0 = feats.build()
d0[["snap_last", "snap_slow"]] = d0[["snap_last", "snap_slow"]].fillna(0.0)
d = hist.rebuild(d0, off=0.7, team=0.5)
hist.add_hist(d, "vf", 0.5, off=0.7, team=0.5, qb=False)
PR = ["rush_yds", "rec_yds", "receptions", "any_td"]
ABS = ["missed_games", "missed_any", "late_start", "first_of_season", "new_team", "exp_years"]
def spec(vac=None, snaps=False):
    f = {"rec_yds": list(ABS), "receptions": list(ABS), "rush_yds": ["rushing_yards_vf", "carries_vf"], "any_td": []}
    if vac:
        for p, c in (("rec_yds", ["tgt"]), ("receptions", ["tgt"]), ("rush_yds", ["car"]), ("any_td", ["tgt", "car"])):
            f[p] += [f"vac_{k}_{vac}_fast" for k in c]
    if snaps:
        for p in PR:
            f[p] += ["snap_last", "snap_slow"]
    return f
if __name__ == "__main__":
    for label, kw in (("ALL 15 test seasons 2012-2026 (train from 2006)", {}), ("train from 2013, test 2015-2026 (12 seasons)", {"first": 2015, "train_first": 2013})):
        print("\n==", label)
        T = t.Tester(d0, props=PR, **kw)
        T.run("A0: memory breaks + flags (no vac)", spec(), panel=d)
        T.run("A: + vac prev", spec("prev"), panel=d)
        T.run("C-no-snaps: A0 + vac now [inactives]", spec("now"), panel=d)
        if kw:
            T.run("B: A + snaps", spec("prev", True), panel=d)
            res = T.run("C: A0 + snaps + vac now [inactives]", spec("now", True), panel=d)
            print(lib.compare(T.base, res, quiet=True)[["n", "crps", "dCRPS", "dCRPS%", "dMAE", "dMAE%", "crps_seas", "mae_seas"]].round(4).to_string())

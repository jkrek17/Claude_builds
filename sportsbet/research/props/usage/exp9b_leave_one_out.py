"""Leave-one-out on the stacks of exp9: change in pooled CRPS% when one piece is REMOVED (positive = the piece helps).
Base for each block is the full stack, so the numbers read as each piece's marginal contribution."""
import feats, t, hist, lib
d0 = feats.build()
d0[["snap_last", "snap_slow"]] = d0[["snap_last", "snap_slow"]].fillna(0.0)
d = hist.rebuild(d0, off=0.7, team=0.5)
hist.add_hist(d, "vf", 0.5, off=0.7, team=0.5, qb=False)
d0 = d0.assign(rushing_yards_vf=d["rushing_yards_vf"], carries_vf=d["carries_vf"])
PR = ["rush_yds", "rec_yds", "receptions", "any_td"]
PIECES = {"absence flags": {p: ["missed_games", "missed_any", "late_start", "first_of_season", "new_team"] for p in PR},
          "exp_years": {p: ["exp_years"] for p in PR},
          "rush last-game horizon": {"rush_yds": ["rushing_yards_vf", "carries_vf"]},
          "vac prev": {"rec_yds": ["vac_tgt_prev_fast"], "receptions": ["vac_tgt_prev_fast"], "rush_yds": ["vac_car_prev_fast"], "any_td": ["vac_tgt_prev_fast", "vac_car_prev_fast"]},
          "vac now": {"rec_yds": ["vac_tgt_now_fast"], "receptions": ["vac_tgt_now_fast"], "rush_yds": ["vac_car_now_fast"], "any_td": ["vac_tgt_now_fast", "vac_car_now_fast"]},
          "snaps": {p: ["snap_last", "snap_slow"] for p in PR}}
def mk(names):
    out = {p: [] for p in PR}
    for n in names:
        for p, c in PIECES[n].items():
            out[p] = out[p] + c
    return out
for label, names, kw in (("Tier A, 2012-2026", ["absence flags", "exp_years", "rush last-game horizon", "vac prev"], {}),
                         ("Tier B, train 2013 / test 2015-2026", ["absence flags", "exp_years", "rush last-game horizon", "vac prev", "snaps"], {"first": 2015, "train_first": 2013}),
                         ("Tier C, train 2013 / test 2015-2026", ["absence flags", "exp_years", "rush last-game horizon", "vac now", "snaps"], {"first": 2015, "train_first": 2013})):
    print("\n==", label, "(rows: stack WITHOUT the named piece, vs the full stack)")
    full = lib.evaluate(d, lib.with_feats(mk(names)), props=PR, **kw)
    T = t.Tester(d0, props=PR, **kw)   # baseline = unmodified panel
    T.run("full stack vs baseline", mk(names), panel=d)
    T.base = full
    for n in names:
        T.run(f"without {n}", mk([x for x in names if x != n]), panel=d)
    T.run("without memory breaks", mk(names), panel=d0)

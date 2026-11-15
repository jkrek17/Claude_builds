"""Idea 5: draft position prior and experience, within the baseline gates (the gate itself is exp7).

draft_val = 1 - ln(pick)/ln(262), undrafted = 0. dv_new_* = draft_val x new_* (the "no history" weight),
dv_rookie = draft_val x first season, dv_yr2 = x second season, exp_years = season - rookie season (0..12),
age_old = max(age - 29, 0).
"""
import feats, t
d = feats.build()
PR = ["rush_yds", "rec_yds", "receptions", "any_td"]
T = t.Tester(d, props=PR)
def r(cols): return {p: cols for p in PR}
T.run("draft_val", r(["draft_val"]))
T.run("dv_new_fast + dv_new_slow", r(["dv_new_fast", "dv_new_slow"]))
T.run("draft_val + dv_new_slow", r(["draft_val", "dv_new_slow"]))
T.run("rookie + dv_rookie", r(["rookie", "dv_rookie"]))
T.run("rookie,yr2 + dv x both", r(["rookie", "dv_rookie", "yr2", "dv_yr2"]))
T.run("exp_years", r(["exp_years"]))
T.run("age + age_old", r(["age", "age_old"]))
T.run("draft_val + dv_young + exp_years", r(["draft_val", "dv_young", "exp_years"]))
T.run("all draft/age", r(["draft_val", "dv_new_slow", "rookie", "dv_rookie", "yr2", "dv_yr2", "exp_years", "age_old"]))
m = (d["games_played"] < 8).to_numpy()
print("\nonly rows with fewer than 8 career games (the 'new player' rows):")
T.run("  draft_val + dv_new_slow", r(["draft_val", "dv_new_slow"]), mask=m)
T.run("  all draft/age", r(["draft_val", "dv_new_slow", "rookie", "dv_rookie", "yr2", "dv_yr2", "exp_years", "age_old"]), mask=m)
m = (d["rookie"] == 1).to_numpy()
print("only rookie-season rows:")
T.run("  draft_val + dv_new_slow", r(["draft_val", "dv_new_slow"]), mask=m)
T.run("  rookie,yr2 + dv x both", r(["rookie", "dv_rookie", "yr2", "dv_yr2"]), mask=m)

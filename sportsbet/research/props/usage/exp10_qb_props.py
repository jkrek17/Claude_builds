"""Passing props: do role features help the QB's own lines?

wpn_now [LIVE] = sum of receiving_yards_slow of the non-QB teammates with a row in this game;
wpn_hist = team's decayed (.75) mean of that over earlier games; wpn_delta = now - hist.
Also the QB's own absence flags and experience, and a check that dropping the 2006-08 seasons
(no targets recorded in the weekly files) from training changes nothing for anyone.
"""
import feats, t, lib
d = feats.build()
PR = ["pass_yds", "pass_tds", "pass_att", "pass_cmp"]
T = t.Tester(d, props=PR)
def r(cols): return {p: cols for p in PR}
T.run("[LIVE] wpn_delta", r(["wpn_delta"]))
T.run("[LIVE] wpn_now + wpn_hist", r(["wpn_now", "wpn_hist"]))
T.run("wpn_hist only (prior)", r(["wpn_hist"]))
T.run("missed/late/first/new_team", r(["missed_games", "late_start", "first_of_season", "new_team"]))
T.run("exp_years + age_old", r(["exp_years", "age_old"]))
T.run("draft_val + dv_new(qb)", r(["draft_val", "dv_rookie", "rookie"]))
print("\nbaseline features, training from 2010 instead of 2006 (targets are absent from the 2006-08 files):")
T2 = t.Tester(d)
T2.run("train_first=2010", train_first=2010)
T2.run("train_first=2009", train_first=2009)

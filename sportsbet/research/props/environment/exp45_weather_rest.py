"""Ideas 4 and 5: weather (OBSERVED wind / temperature: an upper bound on what a forecast can give),
dome and surface; rest, short weeks, division games, late season."""
import numpy as np
import harness as H
import features as F
from nflmodel import props as P

p = F.game_columns(H.base_panel())
LEVEL = {"pass_yds": "passing_yards_slow", "pass_tds": "passing_tds_slow", "pass_att": "attempts_slow",
         "pass_cmp": "completions_slow", "rush_yds": "rushing_yards_slow", "rec_yds": "receiving_yards_slow",
         "receptions": "receptions_slow", "any_td": "skill_tds_slow"}
for k, lv in LEVEL.items():
    p[f"windX_{k}"] = p["wind_hi"] * p[lv]
    p[f"wind15X_{k}"] = p["wind_15"] * p[lv]
    p[f"coldX_{k}"] = p["cold"] * p[lv]
    p[f"domeX_{k}"] = p["dome"] * p[lv]
# total line carries the market's own weather adjustment: wind effect beyond the total
ALL = list(P.FEATURES)
base = H.evaluate(p, P.FEATURES)
rows = []
def test(label, cols, drop=(), per_prop=False, bp=None):
    ex = {k: ([c.format(k=k) for c in cols]) for k in ALL}
    r = H.evaluate(p, H.with_extra(ex, drop=drop))
    rows.append(H.compare(base, r, label, out=False))
    return r

print("share of outdoor player-rows with observed weather, by season:")
o = p[p.dome == 0].groupby("season")["wx_known"].mean().round(2)
print(o.loc[2012:].to_dict())
test("drop dome", [], drop=("dome",))
test("drop is_home", [], drop=("is_home",))
test("+turf", ["turf"])
test("+dome x level", ["domeX_{k}"])
test("WX +wind (mph, linear)", ["wind_f"])
test("WX +wind_hi (mph above 10)", ["wind_hi"])
test("WX +wind_15 (flag >=15 mph)", ["wind_15"])
test("WX +wind_hi x level", ["windX_{k}"])
test("WX +wind_15 x level", ["wind15X_{k}"])
test("WX +wind_hi + wind_hi x level", ["wind_hi", "windX_{k}"])
test("WX +temp (F, linear)", ["temp_f"])
test("WX +cold (deg below 40)", ["cold"])
test("WX +cold x level", ["coldX_{k}"])
test("WX +hot (deg above 80)", ["hot"])
r_w = test("WX +wind_hi + cold", ["wind_hi", "cold"])
test("WX +wind_hi + cold, dome dropped", ["wind_hi", "cold"], drop=("dome",))
for thr in (5, 8, 12, 15):
    p["wind_t"] = (p["wind_f"] - thr).clip(lower=0)
    test(f"WX +wind above {thr}", ["wind_t"])
# rest etc.
test("REST +rest days", ["rest"])
test("REST +short_week (<=5 days)", ["short_week"])
test("REST +off_bye (>=13 days)", ["off_bye"])
test("REST +rest_diff", ["rest_diff"])
test("REST +div_game", ["div_game"])
test("REST +week number", ["week_n"])
test("REST +late (week>=14)", ["late"])
test("REST +playoff", ["playoff"])
test("REST all seven", ["rest", "short_week", "off_bye", "rest_diff", "div_game", "week_n", "playoff"])
H.pivot(rows, H.HERE / "out_exp45.csv")

# where does wind matter: only games with wind >= 15 mph (test rows), baseline vs +wind_hi+cold
print("\nRows in games with observed wind >= 15 mph: baseline vs +wind_hi+cold (pooled over 2012-2026)")
import pandas as pd
sub = p[p["wind_f"] >= 15]
print("share of eligible rows:", round(len(sub) / len(p), 3))

"""Idea 2: game script. Non-linear spread/total terms and interactions with the player's own level."""
import numpy as np
import harness as H
from nflmodel import props as P

p = H.base_panel().copy()
LEVEL = {"pass_yds": "passing_yards_slow", "pass_tds": "passing_tds_slow", "pass_att": "attempts_slow",
         "pass_cmp": "completions_slow", "rush_yds": "rushing_yards_slow", "rec_yds": "receiving_yards_slow",
         "receptions": "receptions_slow", "any_td": "skill_tds_slow"}
p["abs_fav"] = p["fav"].abs()
p["fav_pos"] = p["fav"].clip(lower=0)
p["fav_big"] = (p["fav"].abs() - 7).clip(lower=0) * np.sign(p["fav"])
p["imp_sq"] = (p["implied_pts"] - 22) ** 2
p["fav_sq"] = p["fav"] ** 2
p["fav_x_imp"] = p["fav"] * (p["implied_pts"] - 22)
p["opp_imp"] = p["implied_pts"] - p["fav"]
for k, lv in LEVEL.items():
    p[f"favX_{k}"] = p["fav"] * p[lv]
    p[f"impX_{k}"] = (p["implied_pts"] - 22) * p[lv]
    p[f"absX_{k}"] = p["abs_fav"] * p[lv]
p["favX_car"] = p["fav"] * p["exp_carries"]
p["favX_tgt"] = p["fav"] * p["exp_targets"]
p["impX_car"] = (p["implied_pts"] - 22) * p["exp_carries"]
p["impX_tgt"] = (p["implied_pts"] - 22) * p["exp_targets"]
p["impX_touch"] = (p["implied_pts"] - 22) * p["touches_slow"]
p["favX_touch"] = p["fav"] * p["touches_slow"]

ALL = list(LEVEL)
base = H.evaluate(p, P.FEATURES)
rows = []
def test(label, extra, drop=()):
    r = H.evaluate(p, H.with_extra(extra, drop=drop), list(extra))
    rows.append(H.compare(base[base.prop.isin(list(extra))], r, label, out=False))

test("+abs_fav", {k: ["abs_fav"] for k in ALL})
test("+fav_pos (kink at 0)", {k: ["fav_pos"] for k in ALL})
test("+fav_big (beyond +-7)", {k: ["fav_big"] for k in ALL})
test("+fav_sq", {k: ["fav_sq"] for k in ALL})
test("+imp_sq", {k: ["imp_sq"] for k in ALL})
test("+fav_x_imp", {k: ["fav_x_imp"] for k in ALL})
test("+abs_fav+imp_sq+fav_x_imp", {k: ["abs_fav", "imp_sq", "fav_x_imp"] for k in ALL})
test("+fav x level", {k: [f"favX_{k}"] for k in ALL})
test("+implied x level", {k: [f"impX_{k}"] for k in ALL})
test("+fav x level + implied x level", {k: [f"favX_{k}", f"impX_{k}"] for k in ALL})
test("+abs_fav x level", {k: [f"absX_{k}"] for k in ALL})
test("+fav,imp x level + abs_fav", {k: [f"favX_{k}", f"impX_{k}", "abs_fav"] for k in ALL})
test("+fav,imp x exp volume", {"rush_yds": ["favX_car", "impX_car"], "rec_yds": ["favX_tgt", "impX_tgt"],
                               "receptions": ["favX_tgt", "impX_tgt"], "any_td": ["favX_touch", "impX_touch"]})
test("+fav,imp x level AND x volume", {"rush_yds": ["favX_car", "impX_car", "favX_rush_yds", "impX_rush_yds"],
                                       "rec_yds": ["favX_tgt", "impX_tgt", "favX_rec_yds", "impX_rec_yds"],
                                       "receptions": ["favX_tgt", "impX_tgt", "favX_receptions", "impX_receptions"],
                                       "any_td": ["favX_touch", "impX_touch", "favX_any_td", "impX_any_td"]})
H.pivot(rows, H.HERE / "out_exp2.csv")

# by position for the winner
print("\nby position: + fav x level + implied x level")
ex = {k: [f"favX_{k}", f"impX_{k}"] for k in ALL}
H.compare(base, H.evaluate(p, H.with_extra(ex)), "favX+impX", by_pos=True)

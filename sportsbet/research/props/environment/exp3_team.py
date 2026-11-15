"""Idea 3: team tendencies (pass rate, pace), opponent pace / pass rate allowed, pass rush, EPA-based defence."""
import sys
import numpy as np
import harness as H
import features as F
from nflmodel import props as P

panel = H.base_panel()
T = F.team_table(panel)
ALL = list(P.FEATURES)
PASS = ["pass_yds", "pass_tds", "pass_att", "pass_cmp", "rec_yds", "receptions"]


def build(decay=0.92, k=2.0):
    c = F.Ctx(T, decay, k)
    cols = {
        "o_pass_rate": c.off_rate("dropbacks", "plays"), "o_plays": c.off("plays"),
        "o_sack_rate": c.off_rate("sacks_suffered", "dropbacks"), "o_pass_epa": c.off("passing_epa"),
        "o_rush_epa": c.off("rushing_epa"), "o_first_downs": c.off("first_downs"),
        "d_plays": c.dfn("plays"), "d_plays_adj": c.dfn("plays", True),
        "d_pass_rate": c.dfn_rate("dropbacks", "plays"), "d_pass_rate_adj": c.dfn_rate("dropbacks", "plays", True),
        "d_sack_rate": c.dfn_rate("sacks_suffered", "dropbacks"), "d_sack_rate_adj": c.dfn_rate("sacks_suffered", "dropbacks", True),
        "d_hit_rate": c.dfn_rate("hits_taken", "dropbacks"), "d_hit_rate_adj": c.dfn_rate("hits_taken", "dropbacks", True),
        "d_int_rate": c.dfn_rate("passing_interceptions", "attempts"),
        "d_cmp_rate": c.dfn_rate("completions", "attempts"), "d_cmp_rate_adj": c.dfn_rate("completions", "attempts", True),
        "d_pass_epa": c.dfn_diff("passing_epa"), "d_pass_epa_adj": c.dfn_diff("passing_epa", True),
        "d_rush_epa": c.dfn_diff("rushing_epa"), "d_rush_epa_adj": c.dfn_diff("rushing_epa", True),
        "d_first_downs": c.dfn("first_downs"), "d_first_downs_adj": c.dfn("first_downs", True),
        "d_points": c.dfn("points"), "d_points_adj": c.dfn("points", True),
        "d_air": c.dfn_rate("passing_air_yards", "attempts"),
    }
    # the opposing offence (it sets how long our offence is on the field)
    cols["oo_plays"] = F.opp_side(T, cols["o_plays"])
    cols["oo_pass_rate"] = F.opp_side(T, cols["o_pass_rate"])
    cols["oo_first_downs"] = F.opp_side(T, cols["o_first_downs"])
    cols["oo_rush_epa"] = F.opp_side(T, cols["o_rush_epa"])
    # our own defence (a bad defence means more passing)
    cols["od_points"] = F.opp_side(T, cols["d_points"])
    cols["od_plays"] = F.opp_side(T, cols["d_plays"])
    cols["od_first_downs"] = F.opp_side(T, cols["d_first_downs"])
    return F.attach(panel, T, cols).fillna(0.0), list(cols)


if __name__ == "__main__":
    p, names = build()
    base = H.evaluate(p, P.FEATURES)
    rows = []
    for n in names:
        rows.append(H.compare(base, H.evaluate(p, H.with_extra({k: [n] for k in ALL})), "+" + n, out=False))
    groups = {
        "pass rush: d_sack_rate+d_hit_rate": ["d_sack_rate", "d_hit_rate"],
        "pace: o_plays+d_plays+oo_plays": ["o_plays", "d_plays", "oo_plays"],
        "pass tendency: o_pass_rate+d_pass_rate": ["o_pass_rate", "d_pass_rate"],
        "EPA defence adj: d_pass_epa_adj+d_rush_epa_adj": ["d_pass_epa_adj", "d_rush_epa_adj"],
        "EPA offence: o_pass_epa+o_rush_epa": ["o_pass_epa", "o_rush_epa"],
        "all of the above": ["d_sack_rate", "d_hit_rate", "o_plays", "d_plays", "oo_plays", "o_pass_rate", "d_pass_rate",
                             "d_pass_epa_adj", "d_rush_epa_adj", "o_pass_epa", "o_rush_epa", "d_cmp_rate"],
    }
    for g, f in groups.items():
        rows.append(H.compare(base, H.evaluate(p, H.with_extra({k: f for k in ALL})), g, out=False))
    H.pivot(rows, H.HERE / "out_exp3.csv")

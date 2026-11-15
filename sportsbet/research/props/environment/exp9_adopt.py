"""The adopt set, one piece at a time and together, plus the line-dependence of the adopted model."""
import numpy as np
import pandas as pd
import harness as H
import features as F
from nflmodel import props as P

LEVEL = {"pass_yds": "passing_yards_slow", "pass_tds": "passing_tds_slow", "pass_att": "attempts_slow",
         "pass_cmp": "completions_slow", "rush_yds": "rushing_yards_slow", "rec_yds": "receiving_yards_slow",
         "receptions": "receptions_slow", "any_td": "skill_tds_slow"}
ALL = list(LEVEL)


def build():
    panel = F.game_columns(H.base_panel())
    T = F.team_table(panel)
    c = F.Ctx(T, 0.92, 2.0)
    p = F.attach(panel, T, {"d_cmp_rate": c.dfn_rate("completions", "attempts"),
                            "o_rush_epa": c.off("rushing_epa")}).fillna({"d_cmp_rate": 1.0, "o_rush_epa": 0.0})
    for k, lv in LEVEL.items():
        p[f"impX_{k}"] = (p["implied_pts"] - 22) * p[lv]
    q = p[p["starter"] > 0][["game_id", "team", "passing_yards_slow"]].drop_duplicates(["game_id", "team"])
    p = p.merge(q.rename(columns={"passing_yards_slow": "qb_yds"}), on=["game_id", "team"], how="left", validate="m:1")
    p["qb_yds"] = p["qb_yds"].fillna(p["tm_passing_yards"])
    p["qbX_rec"] = (p["qb_yds"] - 200) * p["receiving_yards_slow"] / 100
    p["qbX_rcp"] = (p["qb_yds"] - 200) * p["receptions_slow"] / 100
    return p


PIECES = {
    "A implied x level": {k: [f"impX_{k}"] for k in ("any_td", "rec_yds", "receptions", "rush_yds", "pass_tds")},
    "B defence completion rate allowed": {"pass_cmp": ["d_cmp_rate"], "receptions": ["d_cmp_rate"]},
    "C offence rushing EPA per game": {"pass_yds": ["o_rush_epa"], "pass_att": ["o_rush_epa"], "pass_cmp": ["o_rush_epa"]},
    "D starter QB yards x receiver level": {"rec_yds": ["qbX_rec"], "receptions": ["qbX_rcp"]},
    "W observed wind above 5 mph (UPPER BOUND)": {k: ["wind_t5"] for k in ("pass_yds", "pass_cmp", "pass_att", "pass_tds")},
}


def merged(names):
    out = {}
    for n in names:
        for k, v in PIECES[n].items():
            out.setdefault(k, []).extend(v)
    return out


if __name__ == "__main__":
    p = build()
    p["wind_t5"] = (p["wind_f"] - 5).clip(lower=0)
    base = H.evaluate(p, P.FEATURES)
    rows = []
    for n, ex in PIECES.items():
        rows.append(H.compare(base[base.prop.isin(list(ex))], H.evaluate(p, H.with_extra(ex), list(ex)), n, out=False))
    live = ["A implied x level", "B defence completion rate allowed", "C offence rushing EPA per game", "D starter QB yards x receiver level"]
    r_live = H.evaluate(p, H.with_extra(merged(live)))
    rows.append(H.compare(base, r_live, "A+B+C+D", out=False))
    rows.append(H.compare(base, H.evaluate(p, H.with_extra(merged(live), drop=("is_home",))), "A+B+C+D, is_home dropped", out=False))
    rows.append(H.compare(base, H.evaluate(p, H.with_extra(merged(list(PIECES)))), "A+B+C+D+W (wind observed)", out=False))
    H.pivot(rows, H.HERE / "out_exp9.csv")

    print("\nA+B+C+D by position")
    H.compare(base, r_live, "A+B+C+D", by_pos=True)

    # signs of the new terms in the model fitted on everything before 2026
    print("\nstandardised coefficients, fit on seasons < 2026 (units of the target per 1 sd of the feature)")
    feats = H.with_extra(merged(list(PIECES)))
    for prop in P.PROPS:
        el = P.eligible(p, prop) & (p["season"] < 2026).to_numpy()
        for pos in prop.positions:
            d = p[el & (p["position"] == pos).to_numpy()]
            m = H.fit_pos(d[feats[prop.key]].to_numpy(float), d[prop.target].to_numpy(float), prop.kind)
            new = [f for f in feats[prop.key] if f not in P.FEATURES[prop.key]] + ["implied_pts", "fav"]
            print(f"  {prop.key:<11}{pos:<3}", "  ".join(f"{f} {m['beta'][feats[prop.key].index(f)]:+.3f}" for f in new))

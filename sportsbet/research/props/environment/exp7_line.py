"""Closing-line dependence: what is lost when implied_pts / fav come from something available early."""
import numpy as np
import pandas as pd
import harness as H
import features as F
from nflmodel import props as P, card

p = H.base_panel().copy()
games = pd.read_csv(H.DATA / "games.csv")
cache = H.HERE / "cache" / "preds.pkl"
if cache.exists():
    pr = pd.read_pickle(cache)
else:
    pr = card.predict_all(games, str(H.DATA))[["game_id", "home_team", "away_team", "pred_margin", "pred_total"]]
    pr.to_pickle(cache)
pr["home_team"] = pr["home_team"].replace(P.TEAM_MAP); pr["away_team"] = pr["away_team"].replace(P.TEAM_MAP)
s = pd.concat([pd.DataFrame({"game_id": pr.game_id, "team": pr.home_team, "m_fav": pr.pred_margin, "m_tot": pr.pred_total}),
               pd.DataFrame({"game_id": pr.game_id, "team": pr.away_team, "m_fav": -pr.pred_margin, "m_tot": pr.pred_total})])
p = p.merge(s, on=["game_id", "team"], how="left", validate="m:1")
print("nflmodel vs close: spread corr %.3f, RMS diff %.2f; total corr %.3f, RMS diff %.2f" % (
    p[["m_fav", "team_spread"]].corr().iloc[0, 1], np.sqrt(((p.m_fav - p.team_spread) ** 2).mean()),
    p[["m_tot", "game_total"]].corr().iloc[0, 1], np.sqrt(((p.m_tot - p.game_total) ** 2).mean())))

# team scoring proxies (no line, no nflmodel): decayed points for / against of both teams
T = F.team_table(p)
c = F.Ctx(T, 0.92, 2)
Tp = T[["game_id", "team", "opponent_team"]].copy()
Tp["pf"] = c.off("points").to_numpy(); Tp["pa_allowed_by_opp"] = (c.dfn("points") * c.L["points"]).to_numpy()
opp = Tp[["game_id", "team", "pf", "pa_allowed_by_opp"]].rename(columns={"team": "opponent_team", "pf": "opp_pf", "pa_allowed_by_opp": "own_pa"})
Tp = Tp.merge(opp, on=["game_id", "opponent_team"], how="left")
p = p.merge(Tp[["game_id", "team", "pf", "pa_allowed_by_opp", "opp_pf", "own_pa"]], on=["game_id", "team"], how="left").fillna({"pf": 22, "pa_allowed_by_opp": 22, "opp_pf": 22, "own_pa": 22})

base = H.evaluate(p, P.FEATURES)
rows = []
LINE = ("implied_pts", "fav")
ALL = list(P.FEATURES)

# (b) no line at all
rows.append(H.compare(base, H.evaluate(p, H.with_extra({}, drop=LINE)), "no line (drop implied_pts, fav)", out=False))
rows.append(H.compare(base, H.evaluate(p, H.with_extra({}, drop=LINE + ("is_home", "dome"))), "no context at all", out=False))
rows.append(H.compare(base, H.evaluate(p, H.with_extra({k: ["pf", "pa_allowed_by_opp", "opp_pf", "own_pa"] for k in ALL}, drop=LINE)),
                      "no line + decayed team points for/against", out=False))
# (c) nflmodel prediction used in both training and test
q = p.copy()
q["fav"] = q["m_fav"].fillna(0.0); q["implied_pts"] = (q["m_tot"] / 2 + q["m_fav"] / 2).fillna(22.0)
rows.append(H.compare(base, H.evaluate(q, P.FEATURES), "nflmodel line (train and test)", out=False))
# (d) trained on closing lines, fed the nflmodel line at test time
rows.append(H.compare(base, H.evaluate(p, P.FEATURES, test_panel=q), "train close, test nflmodel line", out=False))
# (e) trained on closing lines, fed a noisy (early-week) line at test time
gid = p["game_id"].to_numpy()
u, inv = np.unique(gid, return_inverse=True)
home = p.merge(games[["game_id", "home_team"]].assign(home_team=lambda d: d.home_team.replace(P.TEAM_MAP)), on="game_id", how="left")
sign = np.where(home["team"].to_numpy() == home["home_team"].to_numpy(), 1.0, -1.0)
for sd_s, sd_t in ((1.0, 1.0), (1.5, 1.5), (2.5, 2.5)):
    out = []
    for seed in range(5):
        rng = np.random.default_rng(seed)
        es, et = rng.normal(0, sd_s, len(u))[inv], rng.normal(0, sd_t, len(u))[inv]
        q = p.copy()
        q["fav"] = p["fav"] + sign * es
        q["implied_pts"] = p["implied_pts"] + et / 2 + sign * es / 2
        out.append(H.compare(base, H.evaluate(p, P.FEATURES, test_panel=q), "x", out=False))
    m = pd.concat(out).groupby("prop", as_index=False, sort=False)[["dCRPS%", "dMAE%", "crps_wins", "mae_wins", "of"]].mean().round(3)
    m["crps_wins"] = m["crps_wins"].round().astype(int)
    m.insert(0, "variant", f"train close, test close+noise sd {sd_s} spread / {sd_t} total (5 seeds)")
    rows.append(m)
H.pivot(rows, H.HERE / "out_exp7.csv")
print("\nBaseline skill vs slow avg for scale: pass_yds 3.2%, pass_tds 1.7, pass_att 3.4, pass_cmp 3.5, rush_yds 4.4, rec_yds 1.8, receptions 2.5, any_td 1.6")

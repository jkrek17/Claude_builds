"""Backup and injured quarterbacks: python3 research/backup_qb/backtest.py DATA_DIR

Walk-forward on 2010 to 2025, the same window as research/backtest_v2.py. Tests, one at a time:
  A. correcting the starter where nflverse lists a quarterback who did not play
  B. extra pre-kickoff facts about the starter (first start of a stint, inexperience, third starter)
  C. discounting a game the starter left early when the ratings are updated
"""
import sys
import numpy as np, pandas as pd
from nflmodel import margin_model as mm, qb_form, qb_depth, totals_model as tm

D = sys.argv[1].rstrip("/")
FIRST, LAST = 2010, 2025
raw = pd.read_csv(f"{D}/games.csv")
team, players, tstats = mm.load_team_stats(D), qb_form.load_player_stats(D), tm.load_team_stats(D)


def margin_feats(games, shares=None):
    f = mm.compute_features(games, team, qb_shares=shares)
    f = f.merge(qb_form.qb_form_features(games, players), on="game_id", how="left")
    s = qb_depth.side_table(games)
    f = f.merge(s[["game_id"]].assign(qb_new_diff=s.home_new - s.away_new, qb_new_sum=s.home_new + s.away_new,
                                      qb_backup_sum=s.home_backup + s.away_backup), on="game_id")
    green = lambda x: np.clip((8 - x) / 8, 0, 1)
    third = lambda side: ((s[f"{side}_nth"] >= 3) & (s[f"{side}_backup"] > 0)).astype(float)
    f = f.merge(s[["game_id"]].assign(
        qb_green_diff=green(s.home_starts) - green(s.away_starts), qb_green_sum=green(s.home_starts) + green(s.away_starts),
        qb_greenbk_diff=green(s.home_starts) * s.home_backup - green(s.away_starts) * s.away_backup,
        qb_greenbk_sum=green(s.home_starts) * s.home_backup + green(s.away_starts) * s.away_backup,
        qb_third_diff=third("home") - third("away"), qb_third_sum=third("home") + third("away")), on="game_id")
    return f


def score_margin(f, features, games):
    pred, _ = mm.blend_walk_forward(f, features=features)
    d = games[["game_id", "season", "result", "spread_line"]].merge(
        pd.DataFrame({"game_id": f.game_id, "pred": pred}), on="game_id")
    return d[(d.season >= FIRST) & (d.season <= LAST) & d.result.notna()]


def total_feats(games, mfeats, shares=None):
    f = tm.build_features(games, tstats, qb_shares=shares)
    extra = [c for c in mfeats.columns if c.endswith("_sum")]
    return f.merge(mfeats[["game_id"] + extra], on="game_id", how="left")


def score_total(f, features, games):
    pred = tm.walk_forward_predict(f, features=features, predict_with={"wind_x": "clim_wind_x"})
    d = games[["game_id", "season", "total", "total_line"]].merge(
        pd.DataFrame({"game_id": f.game_id, "pred": pred.to_numpy()}), on="game_id")
    d = d.rename(columns={"total": "result", "total_line": "spread_line"})
    return d[(d.season >= FIRST) & (d.season <= LAST) & d.result.notna()]


def report(label, d, base=None, subset=None):
    e = d.result - d.pred
    keep = (d.result != d.spread_line) & (d.pred != d.spread_line)
    win = ((d.pred > d.spread_line) == (d.result > d.spread_line))[keep]
    txt = f"  {label:<44} MAE {e.abs().mean():.3f}  RMSE {np.sqrt((e**2).mean()):.4f}  vs line {win.mean():.1%}"
    if base is not None:
        b = base.set_index("game_id").loc[d.game_id]
        diff = (b.result - b.pred).to_numpy() ** 2 - e.to_numpy() ** 2
        by = pd.Series(diff).groupby(d.season.to_numpy()).mean()
        se = diff.std() / np.sqrt(len(diff))
        txt += f"  d(MSE) {diff.mean():+.3f} (se {se:.3f}), better in {(by > 0).sum()}/{len(by)} seasons"
        if subset is not None:
            m = d.game_id.isin(subset).to_numpy()
            txt += f" | on the {m.sum()} affected games d(MSE) {diff[m].mean():+.2f}"
    print(txt)


MF = mm.FEATURES + qb_form.FEATURES
print("A. STARTER CORRECTION")
fixed = qb_depth.played_starters(raw, players)
changed = set(raw.game_id[(raw.home_qb_id.fillna("") != fixed.home_qb_id.fillna("")) | (raw.away_qb_id.fillna("") != fixed.away_qb_id.fillna(""))])
print(f"  games with a corrected starter: {len(changed)} ({sum(g[:4] >= '2010' and g[:4] <= '2025' for g in changed)} in the window)")
f0, f1 = margin_feats(raw), margin_feats(fixed)
m0, m1 = score_margin(f0, MF, raw), score_margin(f1, MF, fixed)
t0f, t1f = total_feats(raw, f0), total_feats(fixed, f1)
t0, t1 = score_total(t0f, tm.FEATURES, raw), score_total(t1f, tm.FEATURES, fixed)
# games that follow a corrected one for either team in the same season are the ones that can change
print(" margin"); report("as listed", m0); report("corrected", m1, m0)
print(" total"); report("as listed", t0); report("corrected", t1, t0)
late = m1[m1.season >= 2024]
report("margin 2024-2025 only, as listed", m0[m0.season >= 2024]); report("margin 2024-2025 only, corrected", late, m0)
report("total 2024-2025 only, as listed", t0[t0.season >= 2024]); report("total 2024-2025 only, corrected", t1[t1.season >= 2024], t0)

print("\nB. EXTRA FACTS ABOUT THE STARTER (on corrected starters)")
s = qb_depth.side_table(fixed)
aff = {c: set(s.game_id[(s[f"home_{c}"] + s[f"away_{c}"]) > 0]) for c in ("new", "backup")}
aff["green"] = set(s.game_id[(s.home_starts < 8) | (s.away_starts < 8)])
aff["greenbk"] = set(s.game_id[((s.home_starts < 8) & (s.home_backup > 0)) | ((s.away_starts < 8) & (s.away_backup > 0))])
aff["third"] = set(s.game_id[((s.home_nth >= 3) & (s.home_backup > 0)) | ((s.away_nth >= 3) & (s.away_backup > 0))])
print(" margin")
for c in ("new", "green", "greenbk", "third"):
    report(f"+ qb_{c}_diff", score_margin(f1, MF + [f"qb_{c}_diff"], fixed), m1, aff[c])
report("+ new + greenbk", score_margin(f1, MF + ["qb_new_diff", "qb_greenbk_diff"], fixed), m1, aff["new"] | aff["greenbk"])
print(" total")
for c in ("form", "backup", "new", "green", "greenbk", "third"):
    report(f"+ qb_{c}_sum", score_total(t1f, tm.FEATURES + [f"qb_{c}_sum"], fixed), t1, aff.get(c))
report("+ form + backup", score_total(t1f, tm.FEATURES + ["qb_form_sum", "qb_backup_sum"], fixed), t1, aff["backup"])
report("+ form + backup + greenbk", score_total(t1f, tm.FEATURES + ["qb_form_sum", "qb_backup_sum", "qb_greenbk_sum"], fixed), t1, aff["backup"])

report("+ form + greenbk", score_total(t1f, tm.FEATURES + ["qb_form_sum", "qb_greenbk_sum"], fixed), t1, aff["greenbk"])
report("+ form + new", score_total(t1f, tm.FEATURES + ["qb_form_sum", "qb_new_sum"], fixed), t1, aff["new"])

print("\nC. CREDIT A GAME THE STARTER LEFT EARLY TO THE QUARTERBACKS WHO PLAYED IT (on corrected starters)")
share = qb_depth.starter_share(fixed, players)
g = fixed[fixed.result.notna()].sort_values(["gameday", "game_id"])
for cut in (0.5, 0.9):
    early = {k for k, v in share.items() if v < cut}
    nxt, flag = set(), {}
    for r in g.itertuples():
        for t in (r.home_team, r.away_team):
            if flag.get(t):
                nxt.add(r.game_id)
            flag[t] = (r.game_id, qb_depth.TEAM_MAP.get(t, t)) in early
    sh = qb_depth.dropback_shares(fixed, players, cut)
    fw = margin_feats(fixed, sh)
    report(f"starter share < {cut}: margin", score_margin(fw, MF, fixed), m1, nxt)
    report(f"starter share < {cut}: margin + qb_new_diff", score_margin(fw, MF + ["qb_new_diff"], fixed), m1, nxt)
    report(f"starter share < {cut}: total", score_total(total_feats(fixed, fw, sh), tm.FEATURES, fixed), t1, nxt)

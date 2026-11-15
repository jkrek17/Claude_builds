"""Walk-forward backtest of totals_model.py.  Reproduces every number in REPORT.md.

    python3 backtest.py [DATA_DIR]            # full report (about 1 minute)
    python3 backtest.py [DATA_DIR] --tune     # re-run the <=2009 hyper-parameter search

The closing total (total_line) is used ONLY here, as a benchmark and to grade bets.
"""
from __future__ import annotations

import copy
import math
import sys

import numpy as np
import pandas as pd

from nflmodel import totals_model as tm

DATA_DIR = "data/"
LO, HI = 2010, 2025          # reported out-of-sample window
BREAK_EVEN = 110 / 210       # 52.38% at -110
pd.set_option("display.width", 200)


def load(data_dir):
    games = pd.read_csv(data_dir + "/games.csv", low_memory=False)
    return games, tm.load_team_stats(data_dir)


def err(e):
    return f"MAE {e.abs().mean():6.3f}  RMSE {math.sqrt((e ** 2).mean()):6.3f}  bias {e.mean():+5.2f}"


def phi(z):
    return 0.5 * (1 + np.vectorize(math.erf)(np.asarray(z, float) / math.sqrt(2)))


# ------------------------------------------------------------------ bet grading
def bets(df, thr):
    """Bet the model's side when |pred - line| > thr.  Returns wins, losses, pushes."""
    d = df["pred_total"] - df["total_line"]
    sel = df[d.abs() > thr]
    d = d[sel.index]
    over = d > 0
    res = sel["actual_total"] - sel["total_line"]
    win = ((over & (res > 0)) | (~over & (res < 0))).sum()
    push = (res == 0).sum()
    return int(win), int(len(sel) - win - push), int(push)


def bet_row(w, l, p):
    n = w + l
    if n == 0:
        return "no bets"
    r = w / n
    se = math.sqrt(0.25 / n)
    roi = (w * 100 / 110 - l) / n
    return (f"{w:4d}-{l:4d} (push {p:3d})  win {100 * r:5.1f}%  se {100 * se:4.1f}  "
            f"z vs 52.4% {(r - BREAK_EVEN) / se:+5.2f}  ROI@-110 {100 * roi:+5.1f}%")


# ------------------------------------------------------------------ truncation
def truncation_test(games, stats, full, season, week):
    """Remove every row after (season, week) from both inputs; predictions for that
    week (and everything earlier) must be identical to the full-data run."""
    keep_g = (games.season < season) | ((games.season == season) & (games.week <= week))
    keep_s = (stats.season < season) | ((stats.season == season) & (stats.week <= week))
    tr = tm.predict_totals(games[keep_g], stats[keep_s]).set_index("game_id")
    fu = full.set_index("game_id").loc[tr.index]
    num = [c for c in tr.columns if c not in ("season", "week")]
    wk = (tr.season == season) & (tr.week == week)
    d_wk = float(np.nanmax(np.abs(tr.loc[wk, num].to_numpy(float) - fu.loc[wk, num].to_numpy(float))))
    d_all = float(np.nanmax(np.abs(tr[num].to_numpy(float) - fu[num].to_numpy(float))))
    return int(wk.sum()), d_wk, len(tr), d_all


def tamper_test(games, stats, full, season, week):
    """Scramble everything not knowable before kickoff (scores, lines, observed weather,
    team stats) for every game on or after the main game day of (season, week).
    Predictions for that day's games must not move.  (Cut by DAY, not week: a Thursday
    result is legitimately used for the following Sunday.)"""
    g = games.copy()
    wk = g[(g.season == season) & (g.week == week)]
    day = wk.gameday.mode().iloc[0]
    later = g.gameday >= day
    rng = np.random.default_rng(1)
    for c in ["away_score", "home_score", "result", "total", "spread_line", "total_line",
              "over_odds", "under_odds", "temp", "wind", "overtime"]:
        g.loc[later, c] = rng.normal(30, 10, later.sum()).round()
    s = stats.copy()
    later_s = s.game_id.isin(g.loc[later, "game_id"])
    for c in ["passing_epa", "rushing_epa", "attempts", "carries", "sacks_suffered"]:
        s.loc[later_s, c] = rng.normal(20, 10, later_s.sum()).round().clip(1)
    tp = tm.predict_totals(g, s).set_index("game_id")
    fu = full.set_index("game_id")
    ids = wk.game_id[wk.gameday == day]
    moved = float(np.abs(tp.pred_total - fu.pred_total)[g.loc[g.gameday > day, "game_id"]].max())
    return day, len(ids), float(np.abs(tp.loc[ids, "pred_total"] - fu.loc[ids, "pred_total"]).max()), moved


# ------------------------------------------------------------------------ tune
def tune(games, stats):
    """Coordinate search on seasons <= 2009 only.  Objective: RMSE of each rating
    system's pre-game expectation of the two-team sum of its own metric, 2002-2009."""
    g, s = games[games.season <= 2009], stats[stats.season <= 2009]
    p = tm._prep(g, s).set_index("game_id")
    act = {"pts": p.home_score + p.away_score, "epa": p.home_epa + p.away_epa,
           "plays": p.home_plays + p.away_plays}

    def ev(P):
        f = tm.build_features(g, s, P).set_index("game_id")
        v = f[f.season >= 2002]
        hat = {"pts": v.struct_total, "epa": 2 * v.mu_epa + v.off_epa + v.def_epa,
               "plays": 2 * v.mu_plays + v.pace}
        return {m: math.sqrt(((act[m].reindex(v.index) - hat[m]).dropna() ** 2).mean()) for m in act}

    grid = dict(k=[.005, .01, .015, .02, .03, .045, .06], kq=[0, .01, .02, .03, .05, .08],
                r=[.3, .45, .6, .75, .9, 1.0], rq=[.5, .7, .85, 1.0],
                km=[.0003, .0005, .001, .002, .004, .008])
    q0 = {"pts": [-3, -2, -1, 0, 1], "epa": [-.06, -.04, -.02, 0, .02], "plays": [-2, -1, 0, 1]}
    P = copy.deepcopy(tm.PARAMS)
    print("objective at delivered PARAMS:", {m: round(v, 4) for m, v in ev(P).items()})
    for par in ["k", "kq", "r", "rq", "km", "q0"]:
        vals = {m: (q0[m] if par == "q0" else grid[par]) for m in act}
        tab = {m: [] for m in act}
        for i in range(max(len(v) for v in vals.values())):
            T = copy.deepcopy(P)
            for m in act:
                T[m][par] = vals[m][min(i, len(vals[m]) - 1)]
            e = ev(T)
            for m in act:
                tab[m].append((T[m][par], round(e[m], 4)))
        for m in act:
            print(f"{m:5s} {par:3s}", sorted(set(tab[m])))


# ------------------------------------------------------------------------ main
def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    data_dir = args[0] if args else DATA_DIR
    games, stats = load(data_dir)
    if "--tune" in sys.argv:
        tune(games, stats)
        return

    full = tm.predict_totals(games, stats)
    assert full.game_id.is_unique and len(full) == (games.season >= tm.FIRST_PRED_SEASON).sum()
    extra = games[["game_id", "game_type", "home_score", "away_score", "total_line", "roof",
                   "temp", "wind", "gametime"]]
    df = full.merge(extra, on="game_id")
    df["actual_total"] = df.home_score + df.away_score
    oos = df[(df.season >= LO) & (df.season <= HI) & df.actual_total.notna() & df.total_line.notna()].copy()
    em, el = oos.actual_total - oos.pred_total, oos.actual_total - oos.total_line

    print(f"=== 1. HEADLINE, walk-forward {LO}-{HI}, {len(oos)} games (all played games; all have a line)")
    print("model        ", err(em))
    print("closing line ", err(el))
    print("struct only  ", err(oos.actual_total - oos.struct_total), " (2*league + ratings, no regression)")
    print("league avg   ", err(oos.actual_total - oos.base_pts))
    dd = em.abs() - el.abs()
    print(f"MAE gap model-line {dd.mean():+.3f} (paired se {dd.std() / math.sqrt(len(dd)):.3f})")
    print(f"corr(model, line) {np.corrcoef(oos.pred_total, oos.total_line)[0, 1]:.3f}   "
          f"corr(model, actual) {np.corrcoef(oos.pred_total, oos.actual_total)[0, 1]:.3f}   "
          f"corr(line, actual) {np.corrcoef(oos.total_line, oos.actual_total)[0, 1]:.3f}")
    print(f"SD of predictions: model {oos.pred_total.std():.2f}  line {oos.total_line.std():.2f}  "
          f"actual {oos.actual_total.std():.2f}")
    sk = lambda e: float(((e - e.mean()) ** 3).mean() / e.std(ddof=0) ** 3)
    print(f"residual SD: model {em.std():.2f}  line {el.std():.2f};  skew: model {sk(em):+.2f} line {sk(el):+.2f};"
          f"  median resid: model {em.median():+.2f} line {el.median():+.2f}")
    # does the model add anything to the line?  (in-sample OLS on the OOS predictions)
    X = np.column_stack([np.ones(len(oos)), oos.total_line, oos.pred_total])
    b, *_ = np.linalg.lstsq(X, oos.actual_total.to_numpy(), rcond=None)
    res = oos.actual_total.to_numpy() - X @ b
    cov = np.linalg.inv(X.T @ X) * (res @ res) / (len(oos) - 3)
    print(f"actual ~ a + b*line + c*model:  b={b[1]:.3f} (se {math.sqrt(cov[1, 1]):.3f})  "
          f"c={b[2]:.3f} (se {math.sqrt(cov[2, 2]):.3f})")

    print("\n=== 2. PER SEASON")
    print("season   n  MAE_model MAE_line RMSE_model RMSE_line   all bets (W-L, win%)   |diff|>2 (W-L, win%)")
    for s, d in oos.groupby("season"):
        a, b_ = d.actual_total - d.pred_total, d.actual_total - d.total_line
        w0, l0, _ = bets(d, 0)
        w2, l2, _ = bets(d, 2)
        print(f"{s}  {len(d):3d}   {a.abs().mean():6.2f}  {b_.abs().mean():6.2f}    {math.sqrt((a**2).mean()):6.2f}"
              f"    {math.sqrt((b_**2).mean()):6.2f}     {w0:3d}-{l0:3d} {100*w0/(w0+l0):5.1f}%"
              f"      {w2:3d}-{l2:3d} {100*w2/max(w2+l2,1):5.1f}%")

    print(f"\n=== 3. OVER/UNDER, bet the model's side at the closing total, {LO}-{HI} (break-even 52.4%)")
    for thr in [0, 1, 2, 3, 4, 5, 6]:
        print(f"|model-line| > {thr}:  {bet_row(*bets(oos, thr))}")
    d = oos.pred_total - oos.total_line
    for nm, sel in [("model says OVER ", oos[d > 0]), ("model says UNDER", oos[d < 0])]:
        print(f"{nm} (all):  {bet_row(*bets(sel, 0))}")
    res = oos.actual_total - oos.total_line
    print(f"blind UNDER every game: {bet_row(int((res < 0).sum()), int((res > 0).sum()), int((res == 0).sum()))}")

    print("\n=== 4. CALIBRATION")
    sd = em.std()
    print(f"(a) P(over closing line) = Phi((pred - line)/{sd:.2f}) vs observed (pushes excluded)")
    p = phi((oos.pred_total - oos.total_line) / sd)
    np_ = res != 0
    edges = [0, .35, .40, .45, .50, .55, .60, .65, 1.0]
    cut = pd.cut(p[np_], edges)
    t = pd.DataFrame({"p": p[np_], "over": (res[np_] > 0).astype(float)}).groupby(cut, observed=True).agg(
        n=("p", "size"), mean_pred=("p", "mean"), obs_over=("over", "mean"))
    t["se"] = np.sqrt(0.25 / t.n)
    print(t.round(3).to_string())
    print("(b) distribution shape: P(total > T) = Phi((pred - T)/SD) for fixed T, pooled over T in 30.5..60.5 step 5")
    for nm, pred, s_ in [("model", oos.pred_total, sd), ("line ", oos.total_line, el.std())]:
        P, O = [], []
        for T in np.arange(30.5, 61, 5):
            P.append(phi((pred - T) / s_))
            O.append((oos.actual_total > T).astype(float).to_numpy())
        P, O = np.concatenate(P), np.concatenate(O)
        cut = pd.cut(P, np.linspace(0, 1, 11))
        t = pd.DataFrame({"p": P, "o": O}).groupby(cut, observed=True).agg(n=("p", "size"), mean_pred=("p", "mean"), obs=("o", "mean"))
        print(nm, "centre, normal with its own residual SD")
        print(t.round(3).T.to_string())
    z = em / sd
    print("standardised model residual quantiles (normal: -1.645 -0.674 0 0.674 1.645):",
          np.round(np.quantile(z - z.mean(), [.05, .25, .5, .75, .95]), 3))

    print(f"\n=== 5. ABLATIONS, walk-forward {LO}-{HI} (informational; see REPORT for what was chosen when)")
    feat = tm.build_features(games, stats).merge(games[["game_id", "temp", "wind"]], on="game_id")
    feat["obs_wind_x"] = (feat.wind.fillna(tm.WIND_PRIOR) - tm.WIND_PRIOR) * (1 - feat.indoor)
    feat["obs_cold"] = ((feat.temp < 35) & (feat.indoor == 0)).astype(float)
    feat["wk"] = feat.week.clip(upper=18).astype(float)
    m = ((feat.season >= LO) & (feat.season <= HI) & feat.played).to_numpy()
    v = ((feat.season >= 2003) & (feat.season <= 2009) & feat.played).to_numpy()
    F = tm.FEATURES

    def show(name, fs, ft=feat, ridge=None):
        p_ = tm.walk_forward_predict(ft, fs, ridge)
        e, ev_ = (ft.actual_total - p_)[m], (ft.actual_total - p_)[v]
        print(f"{name:42s} {err(e)}   | 2003-09 RMSE {math.sqrt((ev_**2).mean()):.3f}")

    show("DELIVERED", F)
    show("ratings sum only (struct_total)", ["struct_total"])
    show("points ratings only", ["base_pts", "off_pts", "def_pts"])
    show("+ EPA/play ratings", ["base_pts", "off_pts", "def_pts", "off_epa", "def_epa"])
    show("+ pace", F[:6])
    show("+ indoor", F[:7])
    show("+ climatological wind", F[:8])
    show("drop points ratings", [f for f in F if f not in ("off_pts", "def_pts")])
    show("drop EPA ratings", [f for f in F if f not in ("off_epa", "def_epa")])
    show("drop pace", [f for f in F if f != "pace"])
    show("drop div_game", [f for f in F if f != "div_game"])
    show("add late-season outdoor (Dec-Feb)", F + ["late_outdoor"])
    show("add playoff flag", F + ["playoff"])
    show("add week number", F + ["wk"])
    show("no ridge (OLS)", F, ridge=0.0)
    P = copy.deepcopy(tm.PARAMS)
    for k in P:
        P[k]["kq"] = 0.0
    show("no QB term in ratings", F, tm.build_features(games, stats, P))
    for km in [0.0003, 0.008]:
        P = copy.deepcopy(tm.PARAMS)
        for k in P:
            P[k]["km"] = km
        show(f"league-average rate km={km}", F, tm.build_features(games, stats, P))
    show("UPPER BOUND: observed game-day wind/cold", F[:7] + ["obs_wind_x", "obs_cold", "div_game"])
    print("(upper bound uses weather observed at the game: NOT knowable in advance, NOT in the delivered model)")

    print("\n=== 6. LEAKAGE CHECKS")
    for season, week in [(2012, 5), (2019, 12), (2023, 17), (2026, 10)]:
        n, dw, na, da = truncation_test(games, stats, full, season, week)
        print(f"truncate after {season} wk{week:2d}: {n:2d} games that week, max|diff| all output columns = {dw:.2e};"
              f" all {na} earlier-or-equal games max|diff| = {da:.2e}")
    for season, week in [(2012, 5), (2019, 12), (2023, 17)]:
        day, n, dmax, moved = tamper_test(games, stats, full, season, week)
        print(f"scramble scores/lines/weather/stats on or after {day}: {n} games that day, max|pred diff| = {dmax:.2e}"
              f"  (later games do move, max {moved:.1f}: confirms the scramble bites)")
    sh = games.sample(frac=1, random_state=0)
    a = tm.predict_totals(sh, stats).set_index("game_id").pred_total
    print(f"row-order invariance (shuffled games): max|diff| = {np.abs(a - full.set_index('game_id').pred_total.loc[a.index]).max():.2e}")

    print("\n=== 7. 2026 TO DATE (not in headline) and upcoming")
    d26 = df[(df.season == 2026) & df.actual_total.notna()]
    print(f"{len(d26)} games: model {err(d26.actual_total - d26.pred_total)} | line {err(d26.actual_total - d26.total_line)}"
          f" | bets all: {bet_row(*bets(d26, 0))}")
    up = df[(df.season == 2026) & df.actual_total.isna()]
    print(f"unplayed games predicted: {len(up)}, NaN predictions: {int(up.pred_total.isna().sum())}, "
          f"total NaN pred_total in output: {int(full.pred_total.isna().sum())}")
    print(up[["game_id", "pred_total", "total_line", "qb_known"]].head(16).round(1).to_string(index=False))


if __name__ == "__main__":
    main()

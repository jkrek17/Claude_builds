"""Reproduces every number in REPORT.md from the raw nflverse files.

    python3 backtest.py [DATA_DIR]            # full backtest + sanity checks
    python3 backtest.py [DATA_DIR] --tune     # also re-run the 2003-2009 hyper-parameter search

The closing line is used here ONLY as a benchmark; margin_model never reads it.
"""
import math
import sys

import numpy as np
import pandas as pd

from nflmodel import margin_model as mm

DATA = "data/"
args = [a for a in sys.argv[1:] if not a.startswith("--")]
if args:
    DATA = args[0]
TUNE = "--tune" in sys.argv
LO, HI = 2010, 2025          # reported out-of-sample window
TLO, THI = 2003, 2009        # tuning / feature-selection window
pd.set_option("display.width", 200)


def hdr(s):
    print("\n" + "=" * 78 + "\n" + s + "\n" + "=" * 78)


def mae(e):
    return float(np.mean(np.abs(e)))


def rmse(e):
    return float(np.sqrt(np.mean(np.square(e))))


def american_to_prob(ml):
    ml = np.asarray(ml, dtype=float)
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where(ml < 0, -ml / (-ml + 100.0), 100.0 / (ml + 100.0))


games = pd.read_csv(DATA + "games.csv")
stats = mm.load_team_stats(DATA)

# ------------------------------------------------------------------ predictions
pred = mm.predict_margins(games, stats)
assert pred.game_id.is_unique
want = games[games.season >= mm.FIRST_PRED_SEASON]
assert len(pred) == len(want) and set(pred.game_id) == set(want.game_id)
assert pred.pred_margin.notna().all()
df = games.merge(pred, on="game_id", how="inner")
played = df[df.result.notna()].copy()
oos = played[(played.season >= LO) & (played.season <= HI)].copy()

hdr(f"1. ACCURACY, out of sample {LO}-{HI} (regular season + playoffs), n={len(oos)}")
e_m = oos.result - oos.pred_margin
e_l = oos.result - oos.spread_line
assert oos.spread_line.notna().all()
print(f"model  : MAE {mae(e_m):.3f}  RMSE {rmse(e_m):.3f}  mean error {e_m.mean():+.3f}")
print(f"closing: MAE {mae(e_l):.3f}  RMSE {rmse(e_l):.3f}  mean error {e_l.mean():+.3f}")
print(f"naive (always predict 0): MAE {mae(oos.result):.3f}  RMSE {rmse(oos.result):.3f}")
print(f"corr(model, closing spread) = {np.corrcoef(oos.pred_margin, oos.spread_line)[0, 1]:.3f}")
print(f"corr(model, result) = {np.corrcoef(oos.pred_margin, oos.result)[0, 1]:.3f}   "
      f"corr(closing, result) = {np.corrcoef(oos.spread_line, oos.result)[0, 1]:.3f}")
print(f"model picks straight-up winner: {np.mean(np.sign(oos.pred_margin) == np.sign(oos.result)):.3f}   "
      f"closing line (non-pick'em): "
      f"{np.mean(np.sign(oos.spread_line[oos.spread_line != 0]) == np.sign(oos.result[oos.spread_line != 0])):.3f}")
print(f"share of games where model is closer to the result than the line: "
      f"{np.mean(np.abs(e_m) < np.abs(e_l)):.3f}")
# does the model carry information the line lacks?
d = (oos.pred_margin - oos.spread_line).to_numpy()
y = e_l.to_numpy()
b = float(d @ y / (d @ d))
se_b = float(np.sqrt(np.sum((y - b * d) ** 2) / (len(y) - 1) / (d @ d)))
print(f"regress (result - line) on (model - line), no intercept: slope {b:+.3f} (se {se_b:.3f}); "
      f"0 = model adds nothing to the line")
X = np.column_stack([oos.spread_line, oos.pred_margin])
w = np.linalg.lstsq(X, oos.result.to_numpy(), rcond=None)[0]
print(f"regress result on [line, model]: line weight {w[0]:.3f}, model weight {w[1]:.3f}")

print("\nper season:")
rows = []
for s, x in oos.groupby("season"):
    rows.append(dict(season=s, n=len(x), model_mae=mae(x.result - x.pred_margin),
                     line_mae=mae(x.result - x.spread_line),
                     model_rmse=rmse(x.result - x.pred_margin),
                     line_rmse=rmse(x.result - x.spread_line)))
print(pd.DataFrame(rows).round(2).to_string(index=False))
t = played[(played.season >= TLO) & (played.season <= THI)]
print(f"\n(tuning window {TLO}-{THI}: model MAE {mae(t.result - t.pred_margin):.3f} "
      f"RMSE {rmse(t.result - t.pred_margin):.3f}; line MAE {mae(t.result - t.spread_line):.3f} "
      f"RMSE {rmse(t.result - t.spread_line):.3f})")
p26 = played[played.season == 2026]
if len(p26):
    print(f"(2026 to date, n={len(p26)}: model MAE {mae(p26.result - p26.pred_margin):.3f}, "
          f"line MAE {mae(p26.result - p26.spread_line):.3f})")

# ------------------------------------------------------------------ ATS
hdr(f"2. AGAINST THE SPREAD {LO}-{HI}: bet the side the model favours vs the closing line")
BE = 110.0 / 210.0
oos["edge"] = oos.pred_margin - oos.spread_line
oos["cover_home"] = np.sign(oos.result - oos.spread_line)      # +1 home covers, 0 push
oos["bet_win"] = np.where(oos.cover_home == 0, np.nan,
                          (np.sign(oos.edge) == oos.cover_home).astype(float))
THR = [0, 1, 2, 3, 4, 5]


def ats_row(x, thr):
    x = x[x.edge.abs() > thr]
    pushes = int(x.bet_win.isna().sum())
    x = x[x.bet_win.notna()]
    nb = len(x)
    wn = int(x.bet_win.sum())
    return nb, wn, pushes


rows = []
for thr in THR:
    nb, wn, pu = ats_row(oos, thr)
    pct = wn / nb
    se = math.sqrt(0.25 / nb)
    rows.append({"|edge|>": thr, "bets": nb, "wins": wn, "losses": nb - wn, "pushes": pu,
                 "win%": round(100 * pct, 2), "se%": round(100 * se, 2),
                 "z_vs_50": round((pct - 0.5) / se, 2),
                 "z_vs_52.38": round((pct - BE) / math.sqrt(BE * (1 - BE) / nb), 2),
                 "roi%@-110": round(100 * (wn * 100 / 110 - (nb - wn)) / nb, 2),
                 "clears_52.4": pct > BE})
print(pd.DataFrame(rows).to_string(index=False))
print("se% = binomial standard error at p=0.5; break-even at -110 is 52.38%")

print("\nper season (wins-losses, win%) by threshold:")
rows = []
for s, x in oos.groupby("season"):
    r = {"season": s}
    for thr in THR[:5]:
        nb, wn, _ = ats_row(x, thr)
        r[f">{thr}"] = f"{wn}-{nb - wn} ({100 * wn / max(nb, 1):.0f}%)"
    rows.append(r)
print(pd.DataFrame(rows).to_string(index=False))
for thr in THR[:5]:
    ss = [ats_row(x, thr) for _, x in oos.groupby("season")]
    print(f"  threshold >{thr}: seasons above 52.38%: {sum(w / n > BE for n, w, _ in ss)}/{len(ss)}; "
          f"above 50%: {sum(w / n > 0.5 for n, w, _ in ss)}/{len(ss)}")
print(f"mean |model - line| = {oos.edge.abs().mean():.2f} pts; sd(model - line) = {oos.edge.std():.2f}")

# ------------------------------------------------------------------ residual SD + calibration
hdr(f"3. RESIDUAL SD AND WIN-PROBABILITY CALIBRATION {LO}-{HI}")
res = (oos.result - oos.pred_margin).to_numpy()
print(f"residual SD (about the prediction, i.e. RMSE): {rmse(res):.3f}; "
      f"SD about residual mean: {res.std(ddof=1):.3f}; mean {res.mean():+.3f}")
print(f"margin_model.RESID_SD = {mm.RESID_SD}")
print(f"residual SD of the closing line on the same games: {rmse(e_l):.3f}")
by = pd.DataFrame(dict(a=np.abs(oos.pred_margin), r=res)).assign(
    b=lambda z: pd.cut(z.a, [0, 3, 7, 30]))
print("residual RMSE by |pred|:", by.groupby("b", observed=True).r.apply(rmse).round(2).to_dict())
z = res / rmse(res)
print("normality of residuals: share within 1 SD %.3f (normal .683), 2 SD %.3f (normal .954); "
      "excess kurtosis %+.2f" % (np.mean(np.abs(z) < 1), np.mean(np.abs(z) < 2),
                                 np.mean(z ** 4) - 3))

# walk-forward SD: for season S use the RMSE of out-of-sample residuals of seasons < S
allp = played[played.season >= mm.FIRST_PRED_SEASON]
sd_wf = {s: rmse((allp.result - allp.pred_margin)[allp.season < s]) for s in range(LO, HI + 1)}
nt = oos[oos.result != 0].copy()
yw = (nt.result > 0).astype(float).to_numpy()
p_const = mm.norm_cdf(nt.pred_margin / mm.RESID_SD)
p_wf = mm.norm_cdf(nt.pred_margin / nt.season.map(sd_wf))
hasml = nt.home_moneyline.notna() & nt.away_moneyline.notna()
ph = american_to_prob(nt.home_moneyline)
pa = american_to_prob(nt.away_moneyline)
p_mkt = ph / (ph + pa)
p_line = mm.norm_cdf(nt.spread_line / rmse(e_l))


def brier(p, y):
    return float(np.mean((p - y) ** 2))


def logloss(p, y):
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return float(-np.mean(y * np.log(p) + (1 - y) * np.log(1 - p)))


print(f"\nties excluded ({int((oos.result == 0).sum())}); n={len(nt)}; "
      f"with both moneylines: {int(hasml.sum())}; home win rate {yw.mean():.3f}")
m = hasml.to_numpy()
tab = [("model, Normal(pred, RESID_SD const)", p_const),
       ("model, walk-forward SD (prior seasons only)", np.asarray(p_wf)),
       ("closing spread through Normal(line, %.2f)" % rmse(e_l), p_line),
       ("de-vigged closing moneyline", p_mkt),
       ("constant = base rate", np.full(len(nt), yw.mean()))]
print(f"{'forecast':46s} {'Brier':>7s} {'logloss':>8s}   (games with moneyline, n={m.sum()})")
for name, pp in tab:
    print(f"{name:46s} {brier(pp[m], yw[m]):7.4f} {logloss(pp[m], yw[m]):8.4f}")
print("walk-forward SD by season:", {s: round(v, 2) for s, v in sd_wf.items()})

print("\nreliability by decile of model home-win probability (const SD):")
nt["p"] = p_const
nt["y"] = yw
nt["pm"] = p_mkt
nt["dec"] = pd.qcut(nt.p, 10, labels=False)
rel = nt.groupby("dec").agg(n=("y", "size"), p_lo=("p", "min"), p_hi=("p", "max"),
                            mean_pred=("p", "mean"), actual=("y", "mean"),
                            market_mean=("pm", "mean"))
rel["se"] = np.sqrt(rel.actual * (1 - rel.actual) / rel.n)
rel["z"] = (rel.actual - rel.mean_pred) / np.sqrt(rel.mean_pred * (1 - rel.mean_pred) / rel.n)
print(rel.round(3).to_string())
print("expected calibration error (decile, n-weighted): %.4f" %
      float(np.sum(rel.n * np.abs(rel.actual - rel.mean_pred)) / rel.n.sum()))
nt["decm"] = pd.qcut(nt.pm, 10, labels=False, duplicates="drop")
relm = nt[hasml].groupby("decm").agg(n=("y", "size"), mean_pred=("pm", "mean"), actual=("y", "mean"))
print("market ECE (decile): %.4f" %
      float(np.sum(relm.n * np.abs(relm.actual - relm.mean_pred)) / relm.n.sum()))
# cover-probability sanity: does P(cover) from Normal(pred - line, SD) mean anything?
pc = mm.norm_cdf((oos.pred_margin - oos.spread_line) / mm.RESID_SD)
np_ = oos.cover_home != 0
print("\nmodel-implied home-cover probability vs outcome (pushes excluded):")
cb = pd.DataFrame(dict(p=pc[np_], y=(oos.cover_home[np_] > 0).astype(float)))
cb["bin"] = pd.cut(cb.p, [0, .4, .45, .5, .55, .6, 1])
print(cb.groupby("bin", observed=True).agg(n=("y", "size"), mean_pred=("p", "mean"),
                                           actual=("y", "mean")).round(3).to_string())

# ------------------------------------------------------------------ ablations
hdr("4. ABLATIONS (walk-forward blend refit for each variant)")


def variant(params=None, feats=None, base=None):
    f = base if base is not None else mm.compute_features(games, stats, params)
    p, _ = mm.blend_walk_forward(f, feats)
    out = []
    for lo, hi in ((TLO, THI), (LO, HI)):
        k = (f.season >= lo) & (f.season <= hi) & f.result.notna()
        out += [mae((f.result - p)[k]), rmse((f.result - p)[k])]
    return out


base = mm.compute_features(games, stats)
F = mm.FEATURES
rows = [["FULL MODEL"] + variant(base=base)]
for dname in F:
    rows.append([f"drop {dname}"] + variant(base=base, feats=[x for x in F if x != dname]))
rows.append(["Elo + HFA only"] + variant(base=base, feats=["elo_diff", "hfa"]))
rows.append(["Elo + HFA + QB"] + variant(base=base, feats=["elo_diff", "hfa", "qb_diff"]))
rows.append(["EPA + QB + HFA (no Elo)"] + variant(base=base, feats=["pass_diff", "rush_diff", "qb_diff", "hfa"]))
rows.append(["static HFA (hfa_k=0)"] + variant(dict(hfa_k=0.0)))
rows.append(["faster HFA drift (hfa_k=0.003)"] + variant(dict(hfa_k=0.003)))
rows.append(["Elo without MOV autocorr. correction"] + variant(dict(elo_autocorr=False)))
ab = pd.DataFrame(rows, columns=["variant", f"MAE {TLO}-{THI}", f"RMSE {TLO}-{THI}",
                                 f"MAE {LO}-{HI}", f"RMSE {LO}-{HI}"])
print(ab.round(3).to_string(index=False))
print(f"Feature selection and tuning used the {TLO}-{THI} columns only; "
      f"{LO}-{HI} columns are shown for transparency.")
_, wts = mm.blend_walk_forward(base)
print("\nblend weights by season fitted (each uses earlier seasons only):")
print(pd.DataFrame(wts).T.loc[[2003, 2010, 2015, 2020, 2025, 2026]].round(3).to_string())
print("\nHFA state (points) at first game of season:",
      {int(s): round(float(x[x.hfa > 0].hfa.iloc[0]), 2) for s, x in base.groupby("season")
       if s in (2003, 2010, 2015, 2019, 2021, 2023, 2025, 2026)})

# ------------------------------------------------------------------ leakage tests
hdr("5. LEAKAGE / TRUNCATION TESTS")
print(f"sanity: closing-line MAE {mae(e_l):.2f} (expected ~10-10.5), model MAE {mae(e_m):.2f} "
      f"-> model is {'WORSE' if mae(e_m) > mae(e_l) else 'BETTER (suspect leakage!)'} than the market")
full = pred.set_index("game_id")
num = [c for c in pred.columns if c not in ("game_id", "qb_assumed")]
cuts = ["2012-10-21", "2016-12-11", "2019-09-08", "2021-01-17", "2023-11-23", "2025-10-05"]
rng = np.random.default_rng(0)
all_ok = True
for cut in cuts:
    res_line = [f"cut {cut}:"]
    # (a) remove every row dated after the cut
    g_a = games[games.gameday <= cut]
    gid_keep = set(g_a.game_id)
    s_a = stats[stats.game_id.isin(gid_keep)]
    # (b) keep the schedule but blank results/stats of the cut date and later
    g_b = games.copy()
    late = g_b.gameday >= cut
    g_b.loc[late, ["home_score", "away_score", "result", "total"]] = np.nan
    s_b = stats[stats.game_id.isin(set(g_b.game_id[~late]))]
    # (c) keep rows, scramble later results and stats (garbage must not flow backwards)
    g_c = games.copy()
    late_c = (g_c.gameday >= cut) & g_c.result.notna()
    fake = rng.integers(-40, 41, late_c.sum()).astype(float)
    g_c.loc[late_c, "result"] = fake
    g_c.loc[late_c, "home_score"] = 30 + fake
    g_c.loc[late_c, "away_score"] = 30.0
    s_c = stats.copy()
    lm = s_c.game_id.isin(set(g_c.game_id[late_c]))
    s_c.loc[lm, ["passing_epa", "rushing_epa"]] = rng.normal(0, 30, (int(lm.sum()), 2))
    chk = games[(games.gameday <= cut) & (games.season >= mm.FIRST_PRED_SEASON)].game_id
    n_day = int((games.gameday == cut).sum())
    for tag, gg, ss in (("rows removed", g_a, s_a), ("results blanked", g_b, s_b),
                        ("results scrambled", g_c, s_c)):
        pp = mm.predict_margins(gg, ss).set_index("game_id")
        diff = float(np.nanmax(np.abs(pp.loc[chk, num].to_numpy() - full.loc[chk, num].to_numpy())))
        same_nan = bool((pp.loc[chk, num].isna().to_numpy() == full.loc[chk, num].isna().to_numpy()).all())
        ok = diff < 1e-9 and same_nan
        all_ok &= ok
        res_line.append(f"{tag}: max|diff|={diff:.1e} {'OK' if ok else 'FAIL'};")
        if tag == "results scrambled":
            later = games[(games.gameday > cut) & (games.season >= mm.FIRST_PRED_SEASON)].game_id
            dl = float(np.nanmax(np.abs(pp.loc[later, "pred_margin"].to_numpy()
                                        - full.loc[later, "pred_margin"].to_numpy())))
            res_line.append(f"(later games DO change, max {dl:.1f} pts, as they should)")
    print(" ".join(res_line), f"[{len(chk)} games compared, {n_day} on the cut date]")
print("TRUNCATION TEST:", "PASS at all cut points" if all_ok else "FAIL")
# shuffle canary: destroying the line column must not change anything
g_s = games.copy()
for c in ("spread_line", "total_line", "home_moneyline", "away_moneyline", "total",
          "away_spread_odds", "home_spread_odds", "under_odds", "over_odds", "overtime"):
    g_s[c] = rng.permutation(g_s[c].to_numpy())
pp = mm.predict_margins(g_s, stats).set_index("game_id")
print("market columns shuffled: max|diff| = %.1e (model never reads the line)" %
      float(np.nanmax(np.abs(pp.loc[full.index, num].to_numpy() - full[num].to_numpy()))))

# ------------------------------------------------------------------ upcoming games
hdr("6. UNPLAYED GAMES")
un = df[df.result.isna()]
print(f"{len(un)} unplayed games, all with predictions: {bool(un.pred_margin.notna().all())}; "
      f"QB assumed (= team's last starter) in {int(un.qb_assumed.sum())}")
nxt = un.sort_values(["gameday", "game_id"]).head(16)
print(nxt[["game_id", "gameday", "pred_margin", "spread_line", "home_win_prob", "elo_diff",
           "pass_diff", "rush_diff", "qb_diff", "hfa", "rest_diff", "qb_assumed"]].round(2).to_string(index=False))

# ------------------------------------------------------------------ tuning (optional)
if TUNE:
    hdr(f"7. HYPER-PARAMETER SEARCH on {TLO}-{THI} RMSE only (coordinate descent)")

    def score(p):
        f = mm.compute_features(games, stats, p)
        pr, _ = mm.blend_walk_forward(f, ridge=p["ridge"])
        k = (f.season >= TLO) & (f.season <= THI) & f.result.notna()
        return rmse((f.result - pr)[k])

    grid = dict(elo_k=[16, 20, 24, 28, 34, 40], elo_regress=[0.25, 0.33, 0.4, 0.5],
                epa_k=[0.01, 0.015, 0.02, 0.03, 0.045, 0.06, 0.1],
                epa_regress=[0.0, 0.15, 0.3, 0.45], qb_decay=[0.85, 0.9, 0.94, 0.97, 1.0],
                qb_n0=[2, 4, 6, 8, 10, 16], qb_prior_k=[0.01, 0.02, 0.05],
                ridge=[0, 50, 200, 500, 1000], hfa_k=[0, 0.0005, 0.001, 0.002, 0.004])
    cur = dict(mm.PARAMS)
    print(f"shipped params RMSE: {score(cur):.4f}")
    for k, vals in grid.items():
        out = []
        for v in vals:
            q = dict(cur)
            q[k] = v
            out.append((round(score(q), 4), v))
        print(f"{k:12s} shipped={mm.PARAMS[k]}: ", out)
    print("NOTE hfa_k: the 2003-2009 window prefers no drift (HFA was stable then). hfa_k=0.001 "
          "was set by judgement, not tuned.")

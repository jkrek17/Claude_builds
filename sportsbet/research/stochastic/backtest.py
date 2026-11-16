"""Backtest of the stochastic game model: python3 research/stochastic/backtest.py DATA_DIR [OUT_CSV]

DATA_DIR holds the nflverse files that the nflmodel command caches in ~/.cache/nflmodel.

The filter parameters were tuned on 2003-2017 (tune.py). Nothing here was tuned on 2018 onward, which
is the judging window; 2010-2017 is printed for reference and marked as the design window. Every
number is walk-forward. Prices are the closing consensus lines and odds in nflverse games.csv.
"""
from __future__ import annotations

import sys

import numpy as np
import pandas as pd

from nflmodel import (bayes_ratings as br, gamesim as gs, margin_model as mm, qb_form, scoredist,
                      totals_model as tm)
from nflmodel.card import LINE_SD_MARGIN, LINE_SD_TOTAL

WINDOWS = (("judging 2018-2026", 2018, 2026), ("latest 2025-2026", 2025, 2026), ("design 2010-2017", 2010, 2017))
MLO, MHI = scoredist.MARGIN_RANGE
TLO, THI = scoredist.TOTAL_RANGE
KM, KT = np.arange(MLO, MHI + 1), np.arange(TLO, THI + 1)
EPS = 1e-5  # floor on a single number's probability, so a simulated zero cannot give an infinite log score


def dec(american):
    a = np.asarray(american, dtype=float)
    return np.where(a > 0, 1 + a / 100.0, 1 + 100.0 / np.abs(a))


def smooth(p):
    p = p + EPS
    return p / p.sum(-1, keepdims=True)


def side_probs(pmf_rows, lines, lo):
    """Per game: P(outcome > line), P(push), P(outcome < line)."""
    k = np.arange(pmf_rows.shape[1]) + lo
    above = (pmf_rows * (k[None, :] > lines[:, None] + 1e-9)).sum(1)
    push = (pmf_rows * (np.abs(k[None, :] - lines[:, None]) < 1e-9)).sum(1)
    return above, push, 1 - above - push


def bets(label, p_a, p_b, won_a, push, dec_a, dec_b, min_ev):
    """Bet the side with the better expected value when it beats `min_ev` at the posted price."""
    ev_a = p_a * (dec_a - 1) - p_b
    ev_b = p_b * (dec_b - 1) - p_a
    pick_a = ev_a >= ev_b
    ev = np.where(pick_a, ev_a, ev_b)
    take = (ev > min_ev) & np.isfinite(ev)
    win = np.where(pick_a, won_a, ~won_a & ~push)[take]
    psh = push[take]
    d = np.where(pick_a, dec_a, dec_b)[take]
    profit = np.where(psh, 0.0, np.where(win, d - 1, -1.0))
    n = int(take.sum())
    if n == 0:
        return f"    {label:<28} no bets"
    w, lost = int((win & ~psh).sum()), int((~win & ~psh).sum())
    se = profit.std() / np.sqrt(n)
    return (f"    {label:<28} {n:5d} bets  {w}-{lost}  win {w / max(w + lost, 1):.1%}  "
            f"return {profit.mean():+.1%} (SE {se:.1%})")


def main(data_dir: str, out_csv: str | None = None) -> None:
    games = pd.read_csv(f"{data_dir}/games.csv")
    team, players, tstats = mm.load_team_stats(data_dir), qb_form.load_player_stats(data_dir), tm.load_team_stats(data_dir)
    scoring = gs.load_scoring(data_dir)

    rows = gs.fit_means(gs.team_rows(games, team, players, tstats, scoring))
    table = gs.game_table(rows)
    sim = gs.simulate_table(table, rows, games)
    sim_flat = gs.simulate_table(table.assign(var_home=table.var_home.mean(), var_away=table.var_away.mean()), rows, games)

    g = games.merge(mm.predict_margins(games, team, players)[["game_id", "pred_margin"]], on="game_id")
    g = g.merge(tm.predict_totals(games, tstats)[["game_id", "pred_total"]], on="game_id")
    g = g.merge(table[["game_id", "mu_home", "mu_away", "var_home", "var_away"]], on="game_id")
    depth = br.qb_depth(games)
    g = g.merge(depth, on="game_id")
    g = g.set_index("game_id").loc[sim["game_id"]].reset_index()
    keep = (g.result.notna() & g.spread_line.notna() & g.total_line.notna()).to_numpy()
    g = g[keep].reset_index(drop=True)
    M_exact = sim["margin"][keep]
    SIM_SD = np.sqrt((M_exact * KM ** 2).sum(1) - (M_exact * KM).sum(1) ** 2)
    M_raw, T_raw = smooth(M_exact), smooth(sim["total"][keep])
    M_flat = smooth(sim_flat["margin"][keep])
    seasons = g.season.to_numpy()
    res, tot = g.result.to_numpy(float), g.total.to_numpy(float)
    spread, tline = g.spread_line.to_numpy(float), g.total_line.to_numpy(float)

    # walk-forward calibration of the simulated distributions, and the current model's keyed curves
    M_cal, T_cal = M_raw.copy(), T_raw.copy()
    M_cur, T_cur = np.zeros_like(M_raw), np.zeros_like(T_raw)
    M_mkt, T_mkt = np.zeros_like(M_raw), np.zeros_like(T_raw)
    M_norm = scoredist.discrete_normal(g.pred_margin, mm.RESID_SD, MLO, MHI)
    done = games[games.result.notna()]
    for s in sorted(set(seasons)):
        te, tr = seasons == s, seasons < s
        if tr.sum() >= 500:
            M_cal[te] = gs.apply_factors(M_raw[te], gs.calibration_factors(M_raw[tr], res[tr], MLO, True))
            T_cal[te] = gs.apply_factors(T_raw[te], gs.calibration_factors(T_raw[tr], tot[tr], TLO, False))
        past = done[done.season < s]
        fm = scoredist.key_factors(past.spread_line, past.result, LINE_SD_MARGIN, MLO, MHI, True)
        ft = scoredist.key_factors(past.total_line, past.total, LINE_SD_TOTAL, TLO, THI, False)
        for i in np.flatnonzero(te):
            M_cur[i] = scoredist.keyed_pmf(g.pred_margin[i], mm.RESID_SD, fm, MLO, MHI)
            T_cur[i] = scoredist.keyed_pmf(g.pred_total[i], tm.RESID_SD, ft, TLO, THI)
            M_mkt[i] = scoredist.keyed_pmf(scoredist.balanced_center(spread[i], LINE_SD_MARGIN, fm, MLO, MHI),
                                           LINE_SD_MARGIN, fm, MLO, MHI)
            T_mkt[i] = scoredist.keyed_pmf(scoredist.balanced_center(tline[i], LINE_SD_TOTAL, ft, TLO, THI),
                                           LINE_SD_TOTAL, ft, TLO, THI)
    M_cur, T_cur, M_mkt, T_mkt = smooth(M_cur), smooth(T_cur), smooth(M_mkt), smooth(T_mkt)

    sim_margin = (g.mu_home - g.mu_away).to_numpy()
    sim_total = (g.mu_home + g.mu_away).to_numpy()
    home_won = res > 0
    ml_ok = g.home_moneyline.notna().to_numpy() & g.away_moneyline.notna().to_numpy()
    ih, ia = 1 / dec(g.home_moneyline), 1 / dec(g.away_moneyline)
    p_mkt = ih / (ih + ia)

    def win_prob(M):
        return M[:, KM > 0].sum(1) + 0.5 * M[:, KM == 0].sum(1)

    models = {"current model": (M_cur, T_cur), "simulation, raw": (M_raw, T_raw), "simulation, calibrated": (M_cal, T_cal)}

    for title, lo, hi in WINDOWS:
        w = (seasons >= lo) & (seasons <= hi)
        n = int(w.sum())
        print(f"\n================ {title}: {n} games ================")
        print("POINT FORECASTS (mean absolute error / root mean square error)")
        for label, pm, pt in (("closing line", spread, tline), ("current model", g.pred_margin.to_numpy(), g.pred_total.to_numpy()),
                              ("simulation", sim_margin, sim_total)):
            em, et = (res - pm)[w], (tot - pt)[w]
            print(f"    {label:<24} margin {np.abs(em).mean():.3f} / {np.sqrt((em ** 2).mean()):.3f}   "
                  f"total {np.abs(et).mean():.3f} / {np.sqrt((et ** 2).mean()):.3f}")

        print("WINNER (ties count half; Brier and log loss: lower is better)")
        wm = w & ml_ok & (res != 0)
        rows_ = [("market (moneylines, vig removed)", p_mkt)] + [(k, win_prob(v[0])) for k, v in models.items()]
        for label, p in rows_:
            p = np.clip(p, 1e-6, 1 - 1e-6)
            y = home_won[wm].astype(float)
            q = p[wm]
            print(f"    {label:<34} right {((q > .5) == (y > .5)).mean():.1%}  Brier {((q - y) ** 2).mean():.4f}  "
                  f"log loss {-(y * np.log(q) + (1 - y) * np.log(1 - q)).mean():.4f}")

        print("CALIBRATION OF THE WIN CHANCE (favourite's chance by the model, and how often it won)")
        for label in ("current model", "simulation, calibrated"):
            p = win_prob(models[label][0])
            fav = np.maximum(p, 1 - p)
            fav_won = np.where(p >= .5, res > 0, res < 0)
            cells = []
            for a, b in ((.5, .55), (.55, .6), (.6, .65), (.65, .7), (.7, .8), (.8, 1.01)):
                m = w & (fav >= a) & (fav < b) & (res != 0)
                if m.sum():
                    cells.append(f"{a:.0%}-{min(b, 1):.0%}: said {fav[m].mean():.1%}, won {fav_won[m].mean():.1%} (n={m.sum()})")
            print(f"    {label}")
            for c in cells:
                print(f"        {c}")

        print("AGAINST THE CLOSING SPREAD (home covers when result > spread)")
        ds_h, ds_a = dec(g.home_spread_odds.fillna(-110)), dec(g.away_spread_odds.fillna(-110))
        for label, (M, _) in models.items():
            a, p, b = side_probs(M, spread, MLO)
            print(f"  {label}")
            for name, ev in (("every game", -1.0), ("beats the vig", 0.0), ("beats it by 3%+", 0.03), ("beats it by 6%+", 0.06)):
                print(bets(name, a[w], b[w], (res > spread)[w], (res == spread)[w], ds_h[w], ds_a[w], ev))
        print("AGAINST THE CLOSING TOTAL")
        do, du = dec(g.over_odds.fillna(-110)), dec(g.under_odds.fillna(-110))
        for label, (_, T) in models.items():
            a, p, b = side_probs(T, tline, TLO)
            print(f"  {label}")
            for name, ev in (("every game", -1.0), ("beats the vig", 0.0), ("beats it by 3%+", 0.03), ("beats it by 6%+", 0.06)):
                print(bets(name, a[w], b[w], (tot > tline)[w], (tot == tline)[w], do[w], du[w], ev))
        print("MONEYLINE AT CLOSING PRICES")
        dh, da = dec(g.home_moneyline), dec(g.away_moneyline)
        for label, (M, _) in models.items():
            a, b = M[:, KM > 0].sum(1), M[:, KM < 0].sum(1)
            wmm = w & ml_ok
            print(f"  {label}")
            for name, ev in (("beats the vig", 0.0), ("beats it by 3%+", 0.03), ("beats it by 6%+", 0.06)):
                print(bets(name, a[wmm], b[wmm], (res > 0)[wmm], (res == 0)[wmm], dh[wmm], da[wmm], ev))

        print("WHOLE DISTRIBUTION (average log chance given to the exact result; higher is better)")
        im, it = (np.clip(res, MLO, MHI) - MLO).astype(int), (np.clip(tot, TLO, THI) - TLO).astype(int)
        r = np.arange(len(g))
        for label, M, T in (("market line + key numbers", M_mkt, T_mkt), ("current model, normal curve", smooth(M_norm), None),
                            ("current model + key numbers", M_cur, T_cur), ("simulation, raw", M_raw, T_raw),
                            ("simulation, calibrated", M_cal, T_cal), ("simulation, same band every game", M_flat, None)):
            tt = "" if T is None else f"   total {np.log(T[r, it])[w].mean():.4f}"
            print(f"    {label:<34} margin {np.log(M[r, im])[w].mean():.4f}{tt}")

        print("KEY NUMBERS (how often a game ended on this margin, and the chance each model gave it)")
        for k in (3, 7, 10, 6, 14, 4, 1, 0):
            sel = (KM == k) | (KM == -k)
            cells = "  ".join(f"{lab.split(',')[-1].strip() if 'sim' in lab else 'current'} {M[w][:, sel].sum(1).mean():.1%}"
                              for lab, (M, _) in models.items())
            print(f"    by {k:2d}: actual {(np.abs(res[w]) == k).mean():.1%}   {cells}")
        print("PUSHES AND HALF POINTS (games with a whole-number closing spread: chance of landing exactly on it)")
        for k in (3, 7, 10, 6):
            m = w & (np.abs(spread) == k)
            if m.sum() < 20:
                continue
            hit = (res[m] == spread[m]).mean()
            cells = "  ".join(f"{name} {side_probs(M[m], spread[m], MLO)[1].mean():.1%}"
                              for name, M in (("market+key", M_mkt), ("current", M_cur), ("sim raw", M_raw), ("sim calibrated", M_cal)))
            print(f"    spread {k:2d}: n={m.sum():4d}  actual {hit:.1%} (SE {np.sqrt(hit * (1 - hit) / m.sum()):.1%})   {cells}")

        print("DO THE UNCERTAINTY BANDS MEAN ANYTHING? (games sorted by the simulation's own margin spread)")
        sd = SIM_SD
        err = res - sim_margin
        order = np.argsort(sd[w])
        for name, part in zip(("narrowest third", "middle third", "widest third"), np.array_split(order, 3)):
            print(f"    {name:<16} simulated spread {sd[w][part].mean():.2f}   actual error spread {np.sqrt((err[w][part] ** 2).mean()):.2f}"
                  f"   closing line's error {np.sqrt(((res - spread)[w][part] ** 2).mean()):.2f}")
        c = np.corrcoef(sd[w], np.abs(err[w]))[0, 1]
        print(f"    correlation of simulated spread with the size of the miss: {c:+.3f} (SE {1 / np.sqrt(n):.3f})")
        early = g.week.to_numpy() <= 4
        for name, m in (("weeks 1-4", w & early), ("week 5 on", w & ~early)):
            print(f"    {name:<10} n={m.sum():4d}  simulated spread {sd[m].mean():.2f}  actual {np.sqrt((err[m] ** 2).mean()):.2f}"
                  f"  current model's error {np.sqrt(((res - g.pred_margin.to_numpy())[m] ** 2).mean()):.2f}")

        print("QUARTERBACK DEPTH (team points: actual minus forecast, by which starter of the season is playing)")
        cur_h, cur_a = (g.pred_total + g.pred_margin) / 2, (g.pred_total - g.pred_margin) / 2
        book_h, book_a = (tline + spread) / 2, (tline - spread) / 2
        parts = []
        for side, cur, book, mu, pts in (("home", cur_h, book_h, g.mu_home, g.home_score), ("away", cur_a, book_a, g.mu_away, g.away_score)):
            parts.append(pd.DataFrame({"string": g[f"{side}_qb_string"], "changed": g[f"{side}_qb_changed"],
                                       "actual": pts, "current": cur, "sim": mu, "book": book, "w": w}))
        tr_ = pd.concat(parts)
        tr_ = tr_[tr_.w]
        for name, m in (("1st starter", tr_.string == 1), ("2nd starter", tr_.string == 2), ("3rd or later", tr_.string >= 3),
                        ("3rd or later, first start", (tr_.string >= 3) & (tr_.changed == 1))):
            d = tr_[m]
            if len(d) < 5:
                continue
            se = d.actual.std() / np.sqrt(len(d))
            print(f"    {name:<26} n={len(d):4d}  closing line {(d.actual - d.book).mean():+.2f}  current {(d.actual - d.current).mean():+.2f}"
                  f"  simulation {(d.actual - d.sim).mean():+.2f}  (SE {se:.2f})")

    print("\nRATING BANDS (points channel, 2018-2026: share of team scores inside the filter's own band)")
    ok = rows.points.notna() & (rows.season >= 2018) & rows.pts_match.notna()
    z = ((rows.points - rows.pts_match) / np.sqrt(rows.pts_var + br.PARAMS["pts"]["obs_sd"] ** 2))[ok]
    print(f"    68% band holds {(z.abs() < 1).mean():.1%}, 90% band holds {(z.abs() < 1.645).mean():.1%}, z spread {z.std():.3f}")
    rr = rows[ok]
    for name, m in (("week 1-2", rr.week <= 2), ("week 3-8", (rr.week > 2) & (rr.week <= 8)), ("week 9 on", rr.week > 8),
                    ("quarterback changed", rr.qb_changed == 1)):
        print(f"    {name:<20} rating uncertainty (sd, points) {np.sqrt(rr.pts_var[m]).mean():.2f}   n={m.sum()}")

    print("\nSIMULATION SETTINGS BY SEASON (fitted on earlier seasons only)")
    for s, sh in sim["shapes"].items():
        if s >= 2018:
            print(f"    {s}: drives {sh['drives']}, team score spread {sh['resid_sd']:.2f}, 7-point TD {sh['p7']:.3f}, 8-point {sh['p8']:.3f}, "
                  f"overtime by 3 {sh['ot']['three']:.2f} / by 6 {sh['ot']['six']:.2f} / tie {sh['ot']['tie']:.3f}")
    print("\nSCORE MODEL WEIGHTS FOR 2026")
    for k, v in rows.attrs["coefs"][2026].items():
        print(f"    {k:<12} {v:+.3f}")

    print("\nLOOK-AHEAD CHECK (drop every game after a date; earlier forecasts must not change)")
    full = table.set_index("game_id")
    for cut in ("2019-12-01", "2024-10-06"):
        g2 = games[games.gameday <= cut]
        ids = g2.game_id
        r2 = gs.fit_means(gs.team_rows(g2, team[team.game_id.isin(ids)], players[players.game_id.isin(ids)],
                                       tstats[tstats.game_id.isin(ids)], scoring[scoring.game_id.isin(ids)]))
        t2 = gs.game_table(r2).set_index("game_id")
        d = np.nanmax(np.abs(full.loc[t2.index, ["mu_home", "mu_away", "var_home", "var_away"]].to_numpy()
                             - t2[["mu_home", "mu_away", "var_home", "var_away"]].to_numpy()))
        sh1, sh2 = gs.fit_shape(rows, games, int(cut[:4])), gs.fit_shape(r2, g2, int(cut[:4]))
        same = all(abs(sh1[k] - sh2[k]) < 1e-9 for k in ("drives", "p7", "p8", "fg_slope", "cov"))
        print(f"    cut {cut}: largest change {d:.1e}; simulation settings unchanged: {same}")

    if out_csv:
        a, p, b = side_probs(M_cal, spread, MLO)
        o, tp, u = side_probs(T_cal, tline, TLO)
        pd.DataFrame({"game_id": g.game_id, "season": seasons, "week": g.week, "result": res, "total": tot,
                      "spread_line": spread, "total_line": tline, "current_margin": g.pred_margin, "current_total": g.pred_total,
                      "sim_margin": sim_margin, "sim_total": sim_total, "sim_home_win": win_prob(M_cal),
                      "sim_home_cover": a, "sim_spread_push": p, "sim_over": o, "sim_total_push": tp,
                      "sim_margin_sd": SIM_SD}).round(4).to_csv(out_csv, index=False)


if __name__ == "__main__":
    main(sys.argv[1].rstrip("/"), sys.argv[2] if len(sys.argv) > 2 else None)

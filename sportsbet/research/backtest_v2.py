"""Backtest of the current models: python3 research/backtest_v2.py DATA_DIR

DATA_DIR holds nflverse games.csv, stats_team_week_*.csv and stats_player_week_*.csv (the nflmodel
command caches them in ~/.cache/nflmodel). Everything is walk-forward and scored on 2010 to 2025.
"""
from __future__ import annotations

import sys

import numpy as np
import pandas as pd

from nflmodel import margin_model as mm, qb_depth, qb_form, scoredist, totals_model as tm
from nflmodel.card import LINE_SD_MARGIN, LINE_SD_TOTAL

FIRST, LAST = 2010, 2025


def window(df):
    return df[(df.season >= FIRST) & (df.season <= LAST) & df.result.notna()]


def record(pred, line, actual):
    keep = (actual != line) & (pred != line)
    wins = ((pred > line) == (actual > line))[keep]
    n = int(keep.sum())
    return f"{int(wins.sum())}-{n - int(wins.sum())} ({wins.mean():.1%}, SE {np.sqrt(.25 / n):.1%})"


def line(label, pred, actual, market):
    e = actual - pred
    versus = "" if market is None else f"  vs line {record(pred, market, actual)}"
    print(f"  {label:<34} MAE {e.abs().mean():.3f}  RMSE {np.sqrt((e ** 2).mean()):.3f}{versus}")


def main(data_dir: str) -> None:
    games = pd.read_csv(f"{data_dir}/games.csv")
    team, players = mm.load_team_stats(data_dir), qb_form.load_player_stats(data_dir)
    tstats = tm.load_team_stats(data_dir)

    listed = games
    # Played games use the quarterback who really started; a game the starter left early is credited
    # to the quarterbacks who played it (research/backup_qb).
    games, shares = qb_depth.prepare(listed, players)

    print(f"MARGIN, {FIRST} to {LAST}")
    base = window(listed.merge(mm.predict_margins(listed, team)[["game_id", "pred_margin"]], on="game_id"))
    full = window(games.merge(mm.predict_margins(games, team, players, qb_shares=shares)[["game_id", "pred_margin"]], on="game_id"))
    print(f"  games: {len(full)}")
    line("closing spread", full.spread_line, full.result, None)
    line("model without quarterback form", base.pred_margin, base.result, base.spread_line)
    line("model with quarterback form and depth", full.pred_margin, full.result, full.spread_line)
    d = (base.result - base.pred_margin) ** 2 - (full.result - full.pred_margin) ** 2
    by_season = d.groupby(full.season.to_numpy()).mean()
    print(f"  quarterback form and depth lowered squared error in {(by_season > 0).sum()} of {len(by_season)} seasons")

    print(f"\nTOTAL, {FIRST} to {LAST}")
    cols = ["game_id", "pred_total", "indoor"]
    kw = dict(player_stats=players, qb_shares=shares)
    plain = window(listed.merge(tm.predict_totals(listed, tstats)[cols], on="game_id"))
    clim = window(games.merge(tm.predict_totals(games, tstats, **kw)[cols], on="game_id"))
    obs = window(games.merge(tm.predict_totals(games, tstats, wind="observed", **kw)[cols], on="game_id"))
    line("closing total", clim.total_line, clim.total, None)
    line("model without quarterback form and depth", plain.pred_total, plain.total, plain.total_line)
    d = (plain.total - plain.pred_total) ** 2 - (clim.total - clim.pred_total) ** 2
    by_season = d.groupby(clim.season.to_numpy()).mean()
    print(f"  quarterback form and depth lowered squared error in {(by_season > 0).sum()} of {len(by_season)} seasons")
    line("model, stadium wind climatology", clim.pred_total, clim.total, clim.total_line)
    line("model, wind observed at the game*", obs.pred_total, obs.total, obs.total_line)
    print("  * a perfect forecast: the upper bound of what a kickoff wind forecast can add")

    print("\nUNDERS IN WIND (outdoor games, observed wind, pushes excluded)")
    wind = pd.to_numeric(obs.wind, errors="coerce")
    for lo, hi in ((FIRST, LAST), (FIRST, 2017), (2018, LAST)):
        for mph in (15, 20):
            w = obs[(wind >= mph) & (obs.indoor == 0) & (obs.total != obs.total_line)
                    & (obs.season >= lo) & (obs.season <= hi)]
            under = (w.total < w.total_line).mean()
            print(f"  {lo}-{hi}  wind >= {mph} mph: {len(w):4d} games, under {under:.1%} (SE {np.sqrt(.25 / len(w)):.1%})")

    print("\nKEY NUMBERS (games lined within half a point of k; chance the favourite wins by exactly k)")
    done = games[games.result.notna() & games.spread_line.notna()]
    lo, hi = scoredist.MARGIN_RANGE
    for k in (3, 7, 10, 6, 14, 4, 1):
        test = done[(done.season >= FIRST) & ((done.spread_line.abs() - k).abs() <= 0.5)]
        fit = done[done.season < FIRST]
        factors = scoredist.key_factors(fit.spread_line, fit.result, LINE_SD_MARGIN, lo, hi, True)
        actual = (test.result * np.sign(test.spread_line) == k).mean()
        keyed = np.mean([scoredist.keyed_pmf(abs(c), LINE_SD_MARGIN, factors, lo, hi)[k - lo] for c in test.spread_line])
        plain = np.mean([scoredist.discrete_normal(abs(c), LINE_SD_MARGIN, lo, hi)[0][k - lo] for c in test.spread_line])
        print(f"  k={k:2d}  n={len(test):4d}  actual {actual:.1%}  key-number distribution {keyed:.1%}  normal curve {plain:.1%}")

    print("\nLOOK-AHEAD CHECK (remove every game after a date; earlier predictions must not change)")
    def both(g, t_, ts_, p_):
        g, sh = qb_depth.prepare(g, p_)
        return (mm.predict_margins(g, t_, p_, qb_shares=sh).set_index("game_id").pred_margin,
                tm.predict_totals(g, ts_, player_stats=p_, qb_shares=sh).set_index("game_id").pred_total)

    games = listed
    full_m, full_t = both(games, team, tstats, players)
    for cut in ("2012-10-21", "2019-12-01", "2024-10-06"):
        g2 = games[games.gameday <= cut]
        keep = g2.game_id
        m2, t2 = both(g2, team[team.game_id.isin(keep)], tstats[tstats.game_id.isin(keep)], players[players.game_id.isin(keep)])
        print(f"  cut {cut}: max change margin {np.abs(full_m.loc[m2.index] - m2).max():.1e}, total {np.abs(full_t.loc[t2.index] - t2).max():.1e}")
        g3 = games.copy()  # blank the cut day's own results too: same-day games must not inform each other
        late = g3.gameday >= cut
        g3.loc[late, ["home_score", "away_score", "result", "total", "wind", "temp"]] = np.nan
        ids = g3.loc[g3.gameday == cut, "game_id"]
        gone = g3.loc[late, "game_id"]
        m3, t3 = both(g3, team[~team.game_id.isin(gone)], tstats[~tstats.game_id.isin(gone)], players[~players.game_id.isin(gone)])
        print(f"      same-day check ({len(ids)} games): margin {np.abs(full_m.loc[ids] - m3.loc[ids]).max():.1e}, total {np.abs(full_t.loc[ids] - t3.loc[ids]).max():.1e}")


if __name__ == "__main__":
    main(sys.argv[1].rstrip("/"))

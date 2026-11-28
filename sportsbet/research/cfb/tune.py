"""Tune the college model on the tune seasons only (2015-2019, 2021-2022). Never touches 2023 on.

Step 1 picks the rating parameters by margin MAE against results (not by betting return).
Step 2 runs the 24 betting configurations (2 markets x 3 edge thresholds x 4 segments) with those
parameters and logs every one. Usage:  python research/cfb/tune.py [DATA_DIR]
"""
from __future__ import annotations

import itertools
import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

from cfbmodel import backtest, dist, pbp, predict

TUNE = [2015, 2016, 2017, 2018, 2019, 2021, 2022]
GRID = {"prior_games": [2.0, 4.0, 8.0], "decay": [0.95, 0.97, 1.0], "regress": [0.2, 0.5]}
EDGES = [0.02, 0.05, 0.08]
OUT = Path(__file__).resolve().parent


def games_frame(seasons, data_dir: Path) -> pd.DataFrame:
    """Conference names per game from the play-by-play (FBS games only matter for betting)."""
    rows = []
    for s in seasons:
        p = pbp.load(s, data_dir)
        rows.append(p.groupby("game_id", as_index=False).agg(
            home_conf=("home_team_conference", "first"), away_conf=("away_team_conference", "first")))
    return pd.concat(rows, ignore_index=True)


def predictions(params: dict, seasons, data_dir: Path) -> pd.DataFrame:
    """Each season is fitted on every earlier season from 2014 (the first with play-by-play), 2020 left out."""
    return pd.concat([predict.season(s, data_dir, dict(params, train_seasons=[y for y in range(2014, s) if y != 2020]))
                      for s in seasons], ignore_index=True)


def betting_games(preds: pd.DataFrame, confs: pd.DataFrame) -> pd.DataFrame:
    g = preds[(preds.season_type == "regular") & preds.home_fbs & preds.away_fbs].merge(confs, on="game_id")
    """Closing lines from the play-by-play, else ESPN's; ESPN's opener and prices only go with its close."""
    g = g.rename(columns={"spread": "spread_close", "over_under": "total_close"})
    detail = ["spread_open", "total_open", "spread_price_home", "spread_price_away",
              "total_price_over", "total_price_under"]
    for col in detail:
        if col not in g:
            g[col] = np.nan
    for close, espn, cols in (("spread_close", "spread_close_espn", detail[0:1] + detail[2:4]),
                              ("total_close", "total_close_espn", detail[1:2] + detail[4:6])):
        if espn not in g:
            continue
        use_espn = g[close].isna() & g[espn].notna()
        same = g[espn].notna() & (g[close] == g[espn])
        g.loc[use_espn, close] = g.loc[use_espn, espn]
        g.loc[~(use_espn | same), cols] = np.nan
    return g


def main(data_dir: Path) -> None:
    t0 = time.time()
    confs = games_frame(TUNE, data_dir)
    log = []
    best = None
    for pg, dc, rg in itertools.product(*GRID.values()):
        params = {"prior_games": pg, "decay": dc, "regress": rg}
        pr = betting_games(predictions(params, TUNE, data_dir), confs)
        mae = float((pr.home_points - pr.away_points - pr.margin).abs().mean())
        tmae = float((pr.home_points + pr.away_points - pr.total).abs().mean())
        log.append(dict(params, margin_mae=mae, total_mae=tmae, n=len(pr)))
        print(params, round(mae, 3), round(tmae, 3), flush=True)
        if best is None or mae < best[0]:
            best = (mae, params, pr)
    pd.DataFrame(log).to_csv(OUT / "tune_params.csv", index=False)
    _, params, g = best
    line_mae = float((g.home_points - g.away_points + g.spread_close).abs().mean())
    # model against the line on the same games (those with a closing line)
    s, t = g[g.spread_close.notna()], g[g.total_close.notna()]
    on_line = {"n_spread": len(s), "model_margin_mae": float((s.home_points - s.away_points - s.margin).abs().mean()),
               "line_margin_mae": float((s.home_points - s.away_points + s.spread_close).abs().mean()),
               "n_total": len(t), "model_total_mae": float((t.home_points + t.away_points - t.total).abs().mean()),
               "line_total_mae": float((t.home_points + t.away_points - t.total_close).abs().mean())}
    print("chosen", params, "line margin MAE", round(line_mae, 3), flush=True)
    print("on games with a line", {k: round(v, 3) for k, v in on_line.items()}, flush=True)

    rows = []
    for season in TUNE:  # distributions fitted on earlier seasons only
        hist = g[g.season < season].dropna(subset=["spread_close", "total_close", "home_points"])
        fit = dist.fit(hist) if len(hist) > 300 else dist.fit(g[g.season == season].dropna(
            subset=["spread_close", "total_close", "home_points"]))
        part = g[g.season == season]
        for market, edge, seg in itertools.product(["spread", "total"], EDGES, backtest.SEGMENTS):
            rows.append(dict(season=season, market=market, edge_min=edge, segment=seg,
                             bets=backtest.bets(part[["game_id", "margin", "total"]], part.drop(columns=["margin", "total"]), market, edge, seg, fit)))
    results = []
    for (market, edge, seg), grp in itertools.groupby(
            sorted(rows, key=lambda r: (r["market"], r["edge_min"], r["segment"])),
            key=lambda r: (r["market"], r["edge_min"], r["segment"])):
        b = pd.concat([r["bets"] for r in grp], ignore_index=True)
        s = backtest.summary(b)
        results.append(dict(market=market, edge_min=edge, segment=seg, n=s["n"], win_rate=s["win_rate"],
                            roi_110=s["roi_110"], seasons_positive=sum(1 for x in s["by_season"]
                                                                       if (x["roi_110"] or -1) > 0)))
    res = pd.DataFrame(results)
    res.to_csv(OUT / "tune_results.csv", index=False)
    print(res.to_string(index=False))
    json.dump({"params": params, "model_margin_mae": best[0], "line_margin_mae": line_mae,
               "model_total_mae": float((g.home_points + g.away_points - g.total).abs().mean()),
               "on_line": on_line, "minutes": round((time.time() - t0) / 60, 1)}, open(OUT / "tune_summary.json", "w"), indent=2)


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / ".cache" / "cfbmodel")

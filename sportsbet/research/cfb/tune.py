"""Tune the college model on the tune seasons only (2015-2019, 2021-2022). Never touches 2023 on.

Step 1 picks the rating parameters by margin RMSE against results (not by betting return); MAE is
reported beside it. Step 2 runs the 24 betting configurations (2 markets x 3 edge thresholds x 4
segments) with those parameters and logs every one.

Each tune season's distribution (widths and the shrinkage betas of ``dist.fit_shrink``) is fitted on
earlier seasons only, 2014 always among them: 2015's on 2014 alone. 2014 has no earlier season, so
its model numbers come from the points fit on 2014 itself (the fit that predicts 2015); the tune
seasons' numbers are out of sample. Usage:  python research/cfb/tune.py [DATA_DIR]
"""
from __future__ import annotations

import itertools
import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

from cfbmodel import backtest, card, dist, pbp, predict

FIRST = 2014  # first season with play-by-play: in every season's training, never tuned on
TUNE =[2015, 2016, 2017, 2018, 2019, 2021, 2022]
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


def first_season(params: dict, data_dir: Path) -> pd.DataFrame:
    """2014's FBS games with lines, scores and model numbers from the points fit on 2014 (it predicts 2015)."""
    m = predict.model(FIRST + 1, data_dir, dict(params, train_seasons=[FIRST]))
    return card.paired_predictions(m["history"], m["coef"])


def rmse(e) -> float:
    e = np.asarray(e, dtype=float)
    e = e[np.isfinite(e)]
    return float(np.sqrt(np.mean(e ** 2)))


def mae(e) -> float:
    e = np.asarray(e, dtype=float)
    e = e[np.isfinite(e)]
    return float(np.mean(np.abs(e)))


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
        em = pr.home_points - pr.away_points - pr.margin
        et = pr.home_points + pr.away_points - pr.total
        row = dict(params, margin_rmse=rmse(em), margin_mae=mae(em), total_rmse=rmse(et), total_mae=mae(et),
                   n=len(pr))
        log.append(row)
        print(params, {k: round(row[k], 3) for k in ("margin_rmse", "margin_mae", "total_rmse", "total_mae")},
              flush=True)
        if best is None or row["margin_rmse"] < best[0]["margin_rmse"]:
            best = (row, params, pr)
    pd.DataFrame(log).to_csv(OUT / "tune_params.csv", index=False)
    chosen, params, g = best
    lm = g.home_points - g.away_points + g.spread_close
    # model against the line on the same games (those with a closing line)
    s, t = g[g.spread_close.notna()], g[g.total_close.notna()]
    on_line = {"n_spread": len(s),
               "model_margin_rmse": rmse(s.home_points - s.away_points - s.margin),
               "line_margin_rmse": rmse(s.home_points - s.away_points + s.spread_close),
               "model_margin_mae": mae(s.home_points - s.away_points - s.margin),
               "line_margin_mae": mae(s.home_points - s.away_points + s.spread_close),
               "n_total": len(t),
               "model_total_rmse": rmse(t.home_points + t.away_points - t.total),
               "line_total_rmse": rmse(t.home_points + t.away_points - t.total_close),
               "model_total_mae": mae(t.home_points + t.away_points - t.total),
               "line_total_mae": mae(t.home_points + t.away_points - t.total_close)}
    print("chosen", params, "line margin RMSE", round(rmse(lm), 3), "MAE", round(mae(lm), 3), flush=True)
    print("on games with a line", {k: round(v, 3) for k, v in on_line.items()}, flush=True)

    cols = ["season", "spread_close", "total_close", "home_points", "away_points", "margin", "total"]
    known = pd.concat([first_season(params, data_dir)[cols], g[cols]], ignore_index=True)
    rows, betas, probs = [], {}, []
    for season in TUNE:  # distributions fitted on earlier seasons only, 2014 always among them
        hist = known[known.season < season].dropna(subset=["spread_close", "total_close", "home_points"])
        fit = dist.fit(hist)
        betas[season] = {"beta_margin": fit["beta_margin"], "beta_total": fit["beta_total"],
                         "train_seasons": sorted(int(x) for x in hist.season.unique())}
        print(season, "betas", round(fit["beta_margin"], 3), round(fit["beta_total"], 3), flush=True)
        part = g[g.season == season]
        for market in ("spread", "total"):  # every game's chance on the picked side
            b = backtest.bets(part[["game_id", "margin", "total"]], part.drop(columns=["margin", "total"]),
                              market, -1.0, "all", fit)
            probs.append(b.assign(market=market))
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
    allp = pd.concat(probs, ignore_index=True)
    chances = {m: {"n": int(len(x)), "share_above_65": float((x.prob > 0.65).mean()),
                   "max": float(x.prob.max()), "hit_rate": float(x.result.mean())}
               for m, x in allp.groupby("market")}
    print("chances on the picked side", chances, flush=True)
    json.dump({"params": params, "selected_by": "margin_rmse",
               "model_margin_rmse": chosen["margin_rmse"], "model_margin_mae": chosen["margin_mae"],
               "line_margin_rmse": rmse(lm), "line_margin_mae": mae(lm),
               "model_total_rmse": chosen["total_rmse"], "model_total_mae": chosen["total_mae"],
               "on_line": on_line, "betas": betas, "picked_side_chances": chances,
               "minutes": round((time.time() - t0) / 60, 1)}, open(OUT / "tune_summary.json", "w"), indent=2)


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / ".cache" / "cfbmodel")

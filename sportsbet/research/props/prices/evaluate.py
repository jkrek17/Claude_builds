"""Score the props model against real sportsbook prices.

Usage: python research/props/prices/evaluate.py ODDS_DIR [minutes]

For each season the model is fitted on earlier seasons only, then asked for the chance of going over
each posted line. It knows what was knowable half an hour before kickoff: the closing game line and
who was active. Results are compared with the book's own no-vig chance and with betting at the
posted prices.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from nflmodel import props as P

sys.path.insert(0, str(Path(__file__).parent))
from parse import norm  # noqa: E402

MY_BOOKS = ["fanduel", "fanatics"]


def dec(american: pd.Series) -> pd.Series:
    a = american.astype(float)
    return np.where(a > 0, 1 + a / 100, 1 + 100 / a.abs())


def main_lines(lines: pd.DataFrame) -> pd.DataFrame:
    """One line per book, prop and player: two-sided, the most evenly priced (drops alternate lines)."""
    td = lines[lines["prop"] == "any_td"].dropna(subset=["over_price"])
    ou = lines[lines["prop"] != "any_td"].dropna(subset=["over_price", "under_price", "line"]).copy()
    ou["skew"] = (1 / dec(ou["over_price"]) - 1 / dec(ou["under_price"])).__abs__()
    ou = ou.sort_values("skew").drop_duplicates(["game_id", "book", "prop", "name_key"])
    return pd.concat([ou.drop(columns="skew"), td], ignore_index=True)


def predictions(data_dir: str, first: int) -> tuple[pd.DataFrame, dict]:
    games = pd.read_csv(Path(data_dir) / "games.csv")
    lineup = None if os.environ.get("NO_LINEUP") else P.load_lineups(data_dir, games)
    panel = P.build_panel(P.load_offense(data_dir), games, P.load_snaps(data_dir), P.load_rookie_seasons(data_dir), lineup)
    panel["name_key"] = panel["player_display_name"].map(norm)
    rows, models = [], {}
    for season in range(first, int(panel["season"].max()) + 1):
        train, test = panel[panel["season"] < season], panel[panel["season"] == season]
        for prop in P.PROPS:
            m = models[(season, prop.key)] = P.PropModel(prop).fit(train)
            t = test[P.eligible(test, prop) & test["position"].isin(list(m.models)).to_numpy()]
            rows.append(pd.DataFrame({"game_id": t["game_id"].values, "name_key": t["name_key"].values,
                                      "position": t["position"].values, "season": season, "week": t["week"].values,
                                      "prop": prop.key, "mu": m.expected(t), "scale": m.spread_scale(t),
                                      "actual": t[prop.target].to_numpy(float), "fav": t["fav"].values}))
    return pd.concat(rows, ignore_index=True), models


def add_model(df: pd.DataFrame, models: dict) -> pd.DataFrame:
    cache: dict = {}
    out = np.zeros(len(df))
    for i, r in enumerate(df.itertuples()):
        m = models[(r.season, r.prop)]
        key = (r.season, r.prop, r.position, round(r.mu, 4), round(r.scale, 4))
        if m.prop.kind == "count":
            pmf = cache.get(key)
            if pmf is None:
                pmf = cache[key] = m.pmf(r.position, r.mu)
            out[i] = pmf[int(np.floor(r.line)) + 1:].sum()
        else:
            q = cache.get(key)
            if q is None:
                q = cache[key] = m.quantiles(r.position, r.mu, r.scale)
            out[i] = 1.0 - np.interp(r.line, q, P.QUANTILES, left=0.0, right=1.0)
    return df.assign(p_model=np.clip(out, 0.005, 0.995))


def logit(p):
    return np.log(p / (1 - p))


def blend_weight(y: np.ndarray, p_book: np.ndarray, p_model: np.ndarray) -> tuple[float, float]:
    """Weight on (model - book) in a logistic fit with the book's chance as an offset, and its standard error.

    The fit has its own intercept, so a general tilt (unders winning more often than priced) is not
    credited to the model."""
    x = np.column_stack([np.ones(len(y)), logit(p_model) - logit(p_book)])
    off = logit(p_book)
    b = np.zeros(2)
    for _ in range(30):
        p = 1 / (1 + np.exp(-(off + x @ b)))
        h = (x * (p * (1 - p))[:, None]).T @ x
        b += np.linalg.solve(h, x.T @ (y - p))
    return float(b[1]), float(np.sqrt(np.linalg.inv(h)[1, 1]))


def bets(d: pd.DataFrame, edge: float) -> pd.DataFrame:
    """One bet per line on the side whose model edge at the posted price exceeds ``edge``; returns per-bet profit."""
    ev_o = d["p_model"] * d["dec_over"] - 1
    ev_u = (1 - d["p_model"]) * d["dec_under"] - 1
    side_over = ev_o >= ev_u.fillna(-9)
    ev = np.where(side_over, ev_o, ev_u)
    win = np.where(side_over, d["actual"] > d["line"], d["actual"] < d["line"])
    push = (d["actual"] == d["line"]).to_numpy()
    price = np.where(side_over, d["dec_over"], d["dec_under"])
    profit = np.where(push, 0.0, np.where(win, price - 1, -1.0))
    b = d.assign(ev=ev, side=np.where(side_over, "over", "under"), win=win, push=push, profit=profit)
    return b[(b["ev"] > edge) & ~b["push"]]


def roi_line(b: pd.DataFrame) -> str:
    if len(b) == 0:
        return "no bets"
    # Standard error clustered by game: bets on the same game rise and fall together.
    dev = (b["profit"] - b["profit"].mean()).groupby(b["game_id"]).sum()
    se = float(np.sqrt((dev ** 2).sum()) / len(b)) if len(b) > 1 else float("nan")
    return (f"{len(b):>6} bets  win {b['win'].mean():.1%}  ROI {b['profit'].mean():+.1%} (±{1.96 * se:.1%})  "
            f"over share {np.mean(b['side'] == 'over'):.0%}")


def main() -> None:
    root = Path(sys.argv[1])
    minutes = sys.argv[2] if len(sys.argv) > 2 else "30"
    for name in filter(None, os.environ.get("DROP_FEATURES", "").split(",")):   # robustness runs
        for cols in P.FEATURES.values():
            if name in cols:
                cols.remove(name)
    lines = main_lines(pd.read_csv(root / f"lines_{minutes}.csv.gz"))
    first = int(lines["game_id"].str[:4].astype(int).min())
    preds, models = predictions(str(Path.home() / ".cache" / "nflmodel"), first)
    d = lines.merge(preds, on=["game_id", "name_key", "prop"], how="inner")
    print(f"posted lines {len(lines)}, matched to a model prediction {len(d)} ({len(d) / len(lines):.0%}); "
          f"games {d['game_id'].nunique()}, seasons {sorted(d['season'].unique())}")
    d = add_model(d, models)
    d["dec_over"] = dec(d["over_price"])
    d["dec_under"] = np.where(d["under_price"].notna(), dec(d["under_price"].fillna(100)), np.nan)
    two = d["under_price"].notna()
    d["p_book"] = np.where(two, (1 / d["dec_over"]) / (1 / d["dec_over"] + 1 / d["dec_under"]), np.nan)
    d["y"] = (d["actual"] > d["line"]).astype(float)
    d["push"] = d["actual"] == d["line"]
    d.to_csv(root / f"scored_{minutes}.csv.gz", index=False)

    print("\n== Who forecasts better: the model or the book's no-vig chance? (two-sided lines, one consensus row per player)")
    c = d[two & ~d["push"]].groupby(["game_id", "name_key", "prop", "season"], as_index=False).agg(
        p_book=("p_book", "median"), line=("line", "median"), books=("book", "nunique"))
    one = d[two & ~d["push"]].merge(c[["game_id", "name_key", "prop", "line"]], on=["game_id", "name_key", "prop", "line"])
    one = one.groupby(["game_id", "name_key", "prop", "season"], as_index=False).agg(
        p_book=("p_book", "mean"), p_model=("p_model", "first"), y=("y", "first"), line=("line", "first"),
        mu=("mu", "first"))
    print(f"{'prop':<11}{'n':>7}{'over rate':>10}{'Brier model':>12}{'Brier book':>11}{'Brier 50/50':>12}{'model weight':>14}")
    for key, g in list(one.groupby("prop", sort=False)) + [("ALL", one)]:
        w, se = blend_weight(g["y"].to_numpy(), g["p_book"].to_numpy(), g["p_model"].to_numpy())
        print(f"{key:<11}{len(g):>7}{g['y'].mean():>10.3f}{np.mean((g['p_model'] - g['y']) ** 2):>12.4f}"
              f"{np.mean((g['p_book'] - g['y']) ** 2):>11.4f}{0.25:>12.4f}{w:>9.3f} ±{1.96 * se:.3f}")
    print("model weight: 0 means the model adds nothing to the book's price, 1 means the model's chance should replace it.")

    print("\n== Model's median against the line (two-sided, consensus)")
    for key, g in one.groupby("prop", sort=False):
        lean_over = g["p_model"] > 0.5
        hit = np.where(lean_over, g["y"] == 1, g["y"] == 0)
        print(f"  {key:<11} model leans over on {lean_over.mean():.0%} of lines; its side wins {hit.mean():.1%} (n={len(g)})")

    for label, sel in (("FanDuel", d["book"] == "fanduel"), ("Fanatics", d["book"] == "fanatics"),
                       ("best price across all books", None)):
        if sel is None:
            x = d.copy()
            # best price per side at the consensus line
            x = x.merge(c[["game_id", "name_key", "prop", "line"]], on=["game_id", "name_key", "prop", "line"], how="inner")
            td = d[d["prop"] == "any_td"]
            x = pd.concat([x, td])
            x = x.groupby(["game_id", "name_key", "prop", "season", "line"], as_index=False).agg(
                dec_over=("dec_over", "max"), dec_under=("dec_under", "max"), p_model=("p_model", "first"),
                actual=("actual", "first"))
            x["game_id"] = x["game_id"].astype(str)
        else:
            x = d[sel]
        if x.empty:
            continue
        print(f"\n== Betting the model's side at {label} prices (1 unit per bet)")
        for edge in (0.0, 0.05, 0.10, 0.20):
            print(f"  all props, model edge > {edge:.0%}: {roi_line(bets(x, edge))}")
        for key in [p.key for p in P.PROPS if p.key in set(x['prop'])]:
            g = x[x["prop"] == key]
            print(f"  {key:<11} edge > 0%: {roi_line(bets(g, 0.0))}")
            print(f"  {'':<11} edge > 10%: {roi_line(bets(g, 0.10))}")
        for s in sorted(x["season"].unique()):
            print(f"  season {s}, edge > 5%: {roi_line(bets(x[x['season'] == s], 0.05))}")
        ou = x[x["prop"] != "any_td"]
        b = bets(ou, 0.05)
        print(f"  yardage and count lines only, edge > 5%: {roi_line(b)}")
        for side in ("over", "under"):
            print(f"    {side}s: {roi_line(b[b['side'] == side])}")
        blind = ou.assign(p_model=0.0)
        print(f"  for reference, betting every under blindly: {roi_line(bets(blind, -1.0))}")


if __name__ == "__main__":
    main()

"""Walk-forward test of the props model: fit on every earlier season, score the next one.

Usage: python research/props/backtest.py [data_dir] [first_test_season]

Scores, per prop: mean absolute error, CRPS (lower is better; a score for the whole distribution),
and calibration (how often the result lands inside the 50% and 80% bands and above the median).
Two reference forecasts use the same spread machinery so the comparison isolates the expected value:
"slow avg" is the player's long-run decayed average, and "position" is one number per position.
Also rewrites src/nflmodel/props_backtest.json, the summary the web page shows.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

from nflmodel import props as P


RNG = np.random.default_rng(0)


def crps_quantiles(q: np.ndarray, y: float) -> float:
    """CRPS of a forecast given by quantiles at P.QUANTILES (pinball-loss average)."""
    d = y - q
    return float(2 * np.mean(np.where(d >= 0, P.QUANTILES * d, (P.QUANTILES - 1) * d)))


def crps_pmf(p: np.ndarray, y: float) -> float:
    cdf = np.cumsum(p)
    return float(np.sum((cdf - (np.arange(len(p)) >= y)) ** 2))


def score(model: P.PropModel, d: pd.DataFrame, mu: np.ndarray) -> dict:
    y = d[model.prop.target].to_numpy(float)
    pos = d["position"].to_numpy()
    crps, in50, in80, over, pit = [], [], [], [], []
    for yi, mi, po in zip(y, mu, pos):
        if model.prop.kind == "count":
            p = model.pmf(po, mi)
            crps.append(crps_pmf(p, yi))
            cdf = np.cumsum(p)
            lo = cdf[int(yi) - 1] if yi >= 1 else 0.0
            pit.append(lo + RNG.random() * (cdf[min(int(yi), len(cdf) - 1)] - lo))   # randomised PIT for counts
        else:
            q = model.quantiles(po, mi)
            crps.append(crps_quantiles(q, yi))
            lo, hi = float(np.mean(q < yi)), float(np.mean(q <= yi))     # a tie (often at zero) is spread out
            pit.append(lo + RNG.random() * (hi - lo) if hi - lo > 0.01 else float(np.interp(yi, q, P.QUANTILES, left=0.0, right=1.0)))
    pit = np.array(pit)
    return {"n": len(y), "mae": float(np.mean(np.abs(y - mu))), "crps": float(np.mean(crps)),
            "bias": float(np.mean(mu - y)), "in50": float(np.mean((pit > 0.25) & (pit < 0.75))),
            "in80": float(np.mean((pit > 0.10) & (pit < 0.90))), "over_med": float(np.mean(pit > 0.5)),
            "pit": pit}


def reference(model: P.PropModel, train: pd.DataFrame, test: pd.DataFrame, col: str | None) -> np.ndarray:
    """Expected value from a single column (or the position mean when ``col`` is None), scaled to be unbiased on train."""
    out = np.zeros(len(test))
    for pos in model.prop.positions:
        tr, te = train[train["position"] == pos], (test["position"] == pos).to_numpy()
        ybar = tr[model.prop.target].mean()
        if col is None:
            out[te] = ybar
        else:
            x = tr[col].to_numpy(float)
            b = np.polyfit(x, tr[model.prop.target].to_numpy(float), 1)
            out[te] = np.maximum(np.polyval(b, test.loc[te, col].to_numpy(float)), model.models[pos]["floor"])
    return out


REF_COL = {"pass_yds": "passing_yards_slow", "pass_tds": "passing_tds_slow", "pass_att": "attempts_slow",
           "pass_cmp": "completions_slow", "rush_yds": "rushing_yards_slow", "rec_yds": "receiving_yards_slow",
           "receptions": "receptions_slow", "any_td": "skill_tds_slow"}


def run(panel: pd.DataFrame, first: int, last: int, quiet: bool = False) -> pd.DataFrame:
    rows, td_p, td_y = [], [], []
    for season in range(first, last + 1):
        train, test = panel[panel["season"] < season], panel[panel["season"] == season]
        for prop in P.PROPS:
            tr = train[P.eligible(train, prop)]
            te = test[P.eligible(test, prop)]
            if te.empty:
                continue
            m = P.PropModel(prop).fit(train)
            for name, mu in (("model", m.expected(te)), ("slow avg", reference(m, tr, te, REF_COL[prop.key])),
                             ("position", reference(m, tr, te, None))):
                s = score(m, te, mu)
                s.pop("pit")
                rows.append({"season": season, "prop": prop.key, "forecast": name, **s})
                if prop.key == "any_td" and name == "model":
                    td_p.extend(1 - np.array([m.pmf(po, mi)[0] for po, mi in zip(te["position"], mu)]))
                    td_y.extend((te[prop.target] > 0).astype(float))
    res = pd.DataFrame(rows)
    if not quiet:
        report(res, np.array(td_p), np.array(td_y))
    return res


def report(res: pd.DataFrame, td_p: np.ndarray, td_y: np.ndarray) -> None:
    w = lambda g, c: np.average(g[c], weights=g["n"])
    print(f"{'prop':<11}{'forecast':<10}{'n':>7}{'MAE':>8}{'CRPS':>8}{'bias':>7}{'in50%':>7}{'in80%':>7}{'>med':>7}")
    for prop in P.PROPS:
        for name in ("model", "slow avg", "position"):
            g = res[(res["prop"] == prop.key) & (res["forecast"] == name)]
            print(f"{prop.key:<11}{name:<10}{g['n'].sum():>7}{w(g, 'mae'):>8.2f}{w(g, 'crps'):>8.3f}"
                  f"{w(g, 'bias'):>7.2f}{w(g, 'in50'):>7.3f}{w(g, 'in80'):>7.3f}{w(g, 'over_med'):>7.3f}")
    print("\nSeasons where the model's CRPS beats the slow average:")
    for prop in P.PROPS:
        a = res[(res["prop"] == prop.key) & (res["forecast"] == "model")].set_index("season")["crps"]
        b = res[(res["prop"] == prop.key) & (res["forecast"] == "slow avg")].set_index("season")["crps"]
        print(f"  {prop.key:<11}{int((a < b).sum())} of {len(a)}   skill {100 * (1 - a.mean() / b.mean()):.1f}%")
    print("\nAnytime TD reliability (forecast chance vs how often it happened):")
    edges = [0, .1, .2, .3, .4, .5, .6, .7, 1.0]
    for lo, hi in zip(edges[:-1], edges[1:]):
        s = (td_p >= lo) & (td_p < hi)
        if s.sum():
            print(f"  {lo:.0%}-{hi:.0%}: n={s.sum():>6}  forecast {td_p[s].mean():.3f}  observed {td_y[s].mean():.3f}")
    print(f"  Brier {np.mean((td_p - td_y) ** 2):.4f}   base-rate Brier {np.mean((td_y.mean() - td_y) ** 2):.4f}")


def load_panel(data_dir: Path) -> pd.DataFrame:
    games = pd.read_csv(data_dir / "games.csv")
    d = str(data_dir)
    return P.build_panel(P.load_offense(d), games, P.load_snaps(d), P.load_rookie_seasons(d))


def summary(res: pd.DataFrame) -> dict:
    """Per-prop numbers for the web page and README."""
    out = {}
    for prop in P.PROPS:
        g = res[(res["prop"] == prop.key) & (res["forecast"] == "model")].set_index("season")
        b = res[(res["prop"] == prop.key) & (res["forecast"] == "slow avg")].set_index("season")
        w = lambda f, c: float(np.average(f[c], weights=f["n"]))
        out[prop.key] = {"n": int(g["n"].sum()), "mae": round(w(g, "mae"), 3), "mae_baseline": round(w(b, "mae"), 3),
                         "crps": round(w(g, "crps"), 4), "crps_baseline": round(w(b, "crps"), 4),
                         "coverage_50": round(w(g, "in50"), 3), "coverage_80": round(w(g, "in80"), 3),
                         "seasons_better": int((g["crps"] < b["crps"]).sum()), "seasons": int(len(g))}
    return out


if __name__ == "__main__":
    data_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / ".cache" / "nflmodel"
    first = int(sys.argv[2]) if len(sys.argv) > 2 else 2015
    panel = load_panel(data_dir)
    last = int(panel["season"].max())
    res = run(panel, first, last)
    import json
    dest = Path(P.__file__).with_name("props_backtest.json")
    dest.write_text(json.dumps({"first": first, "last": last, "props": summary(res)}, indent=1) + "\n")
    print(f"\nwrote {dest}")
